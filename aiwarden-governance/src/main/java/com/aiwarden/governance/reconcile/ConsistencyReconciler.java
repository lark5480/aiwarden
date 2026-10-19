package com.aiwarden.governance.reconcile;

import com.aiwarden.core.event.DocumentEventTypes;
import com.aiwarden.core.spi.VectorStore;
import com.aiwarden.governance.ingest.IngestLedgerGuard;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一致性对账（FR-ING-06，P1「有 SLO 的可对账」的落点）：
 * 扫描「已删除文档的残留向量 / 切片」与「摄入链路停摆的文档」，
 * 落 {@code t_reconcile_report} + 更新指标。
 *
 * <p><b>只读发现，不自动改数据</b>——修复走显式动作（{@link #repair()}，见 ADR-006）：
 * 自动修复会让不一致清单永远为空，而对账的价值正是「发现并记录、修复可追溯」。
 *
 * <p><b>摄入停摆判据是三条子判据的并集（按 doc_id 去重，不重复计数）</b>——只查账本会漏掉
 * 「事件永久失败、账本行根本没建起来」的形态（实测：outbox 重试到上限置 FAILED 后文档停在
 * PENDING 不动，ledger 为 0 行，当时的判据看不到它）：
 * ① {@code t_outbox_event} **当前版本的索引事件** {@code FAILED}（relay 发到 Kafka 永久失败，
 *    消费者收不到；删除事件 / 旧版本事件不算，见 {@link #REDRIVABLE_FAILED_INDEX_EVENT}）；
 * ② {@code t_ingest_ledger} 的 {@code PROCESSING} 超时（只算当前版本、当前未收敛的文档）；
 * ③ {@code t_document} 停在 {@code PENDING}/{@code PROCESSING} 超时且未软删（事件丢失 / 消费停摆）。
 *
 * <p><b>重驱动只做“当前版本索引事件”</b>：删除事件重驱动会重新 purge（可能删掉同名重传的新文档）；
 * 旧版本索引事件重驱动会覆盖新版本产物。判据 / 计数 / repair 三处共用同一条件，
 * 消费端 {@code DocumentEventConsumer} 还会按当前文档版本再拒一次。
 *
 * <p>指标（最近一轮扫描的当前值，Gauge）：{@code aiwarden_vector_orphan_total}（孤儿向量数）、
 * {@code aiwarden_ingest_stuck_total}（摄入停摆的文档数，即上面并集的去重结果）。
 */
@Component
public class ConsistencyReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyReconciler.class);

    /**
     * 可重驱动的失败事件条件（判据 / 计数 / repair 三处共用同一份 SQL，避免口径分叉）：
     * 只认**当前版本、文档存在且未收敛的索引事件**。
     *
     * <p>为什么不是所有 {@code document.%}：删除事件被重驱动会重新执行删除（可能删掉同名重传的新文档）；
     * 旧版本索引事件被重驱动会把旧版本产物写回并删除新版本切片。幂等键 {@code (docId, version)}
     * 只能挡住重复投递，挡不住“旧事件晚到”。删除的后果由「残留切片 / 孤儿向量」判据负责。
     *
     * <p>消费端另有 {@code DocumentEventConsumer} 的当前版本守卫，作为第二道防线。
     */
    private static final String REDRIVABLE_FAILED_INDEX_EVENT = """
            o.status = 'FAILED'
            AND o.type = '%s'
            AND EXISTS (
                SELECT 1 FROM t_document d
                WHERE d.id = o.aggregate_id
                  AND d.version = (o.payload ->> 'version')::int
                  AND d.status IN ('PENDING', 'PROCESSING')
            )
            """.formatted(DocumentEventTypes.INDEX_REQUESTED);

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final IngestLedgerGuard ledgerGuard;
    private final ObjectMapper objectMapper;
    private final boolean scanEnabled;
    private final AtomicLong orphanVectors = new AtomicLong();
    private final AtomicLong stuckIngest = new AtomicLong();

    public ConsistencyReconciler(JdbcTemplate jdbcTemplate,
                                 VectorStore vectorStore,
                                 IngestLedgerGuard ledgerGuard,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry,
                                 @Value("${aiwarden.reconcile.enabled:true}") boolean scanEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.ledgerGuard = ledgerGuard;
        this.objectMapper = objectMapper;
        this.scanEnabled = scanEnabled;
        Gauge.builder("aiwarden_vector_orphan_total", orphanVectors, AtomicLong::get)
                .description("最近一轮对账发现的孤儿向量数（已删除文档的残留向量）")
                .register(meterRegistry);
        Gauge.builder("aiwarden_ingest_stuck_total", stuckIngest, AtomicLong::get)
                .description("最近一轮对账发现的摄入停摆文档数（事件永久失败 / 账本卡死 / 文档超时未收敛）")
                .register(meterRegistry);
    }

    /** 定时兜底入口（默认 5 分钟）；测试环境全局关闭，由测试直接调 {@link #runOnce()}。 */
    @Scheduled(fixedDelayString = "${aiwarden.reconcile.interval-ms:300000}")
    public void scheduledScan() {
        if (!scanEnabled) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("对账扫描失败（下轮重试）", e);
        }
    }

    /** 单轮扫描：发现 → 落报告 → 更新指标。 */
    @Transactional
    public ReconcileSummary runOnce() {
        List<Map<String, Object>> staleDocuments = jdbcTemplate.queryForList("""
                SELECT c.doc_id, c.tenant_id, count(*) AS stale_chunks
                FROM t_chunk c
                JOIN t_document d ON d.id = c.doc_id
                WHERE d.deleted_at IS NOT NULL
                GROUP BY c.doc_id, c.tenant_id
                ORDER BY c.doc_id
                """);
        Long orphanVectorCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_vector v
                JOIN t_chunk c ON v.chunk_id = c.id
                JOIN t_document d ON d.id = c.doc_id
                WHERE d.deleted_at IS NOT NULL
                """, Long.class);
        List<Map<String, Object>> stuckIngestRows = findStuckIngest();
        Long failedOutboxCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_outbox_event o
                WHERE
                """ + REDRIVABLE_FAILED_INDEX_EVENT, Long.class);

        long orphanVectorsValue = orphanVectorCount == null ? 0 : orphanVectorCount;
        long failedOutboxEvents = failedOutboxCount == null ? 0 : failedOutboxCount;
        int mismatchCount = staleDocuments.size() + stuckIngestRows.size();

        orphanVectors.set(orphanVectorsValue);
        stuckIngest.set(stuckIngestRows.size());

        String detailsJson = objectMapper.writeValueAsString(Map.of(
                "staleDocuments", staleDocuments,
                "orphanVectors", orphanVectorsValue,
                "stuckIngest", stuckIngestRows,
                "failedOutboxEvents", failedOutboxEvents,
                "scannedAt", OffsetDateTime.now().toString()));

        Long reportId = jdbcTemplate.queryForObject("""
                INSERT INTO t_reconcile_report (type, window_start, window_end, mismatch_count, detail_ref)
                VALUES ('CONSISTENCY', now() - interval '5 minutes', now(), ?, ?)
                RETURNING id
                """, Long.class, mismatchCount, detailsJson);

        log.info("对账完成：不一致 {}（残留文档 {}，孤儿向量 {}，摄入停摆 {}，其中当前版本失败索引事件 {}）",
                mismatchCount, staleDocuments.size(), orphanVectorsValue,
                stuckIngestRows.size(), failedOutboxEvents);
        return new ReconcileSummary(reportId, mismatchCount, staleDocuments.size(),
                orphanVectorsValue, stuckIngestRows.size(), failedOutboxEvents);
    }

    /**
     * 摄入停摆判据：三条子判据的并集，**按 doc_id 去重**（同一文档既「事件永久失败」又「停在 PENDING」
     * 只算一条不一致，否则 mismatchCount 会重复计数）。
     * reason 取优先级最高的一条——OUTBOX_FAILED（可直接重驱动）&gt; LEDGER_PROCESSING（消费没收敛）
     * &gt; DOCUMENT_OVERDUE（事件根本没到消费者）。
     *
     * <p><b>事件 `FAILED` 不单独成立</b>：只在该文档**尚未收敛**（状态仍是 PENDING/PROCESSING）时才计——
     * 否则一条已被成功消费过的索引事件被标成 FAILED 就是假阳性（2026-10-19 现场注入验证）。
     * 文档行不在 / 已终态（INDEXED / DELETED / FAILED）都不进入 OUTBOX_FAILED：
     * 没有可重驱动的当前版本目标。
     *
     * <p><b>只认当前版本的索引事件</b>：删除事件不进入（它的后果由「残留切片 / 孤儿向量」两条判据负责）；
     * 旧版本索引事件也不进入（{@code payload.version} 必须等于 {@code t_document.version}，
     * 否则重驱动旧事件会把旧版本产物写回并删掉新版本切片）。
     *
     * <p><b>账本 `PROCESSING` 只算当前版本、当前未收敛的文档</b>：旧版本卡死（当前版本已 INDEXED）是历史残留，
     * 不是当前摄入停摆；已删除文档的删除卡死由残留判据负责。孤儿账本（文档行不存在）仍保留为可见异常。
     */
    private List<Map<String, Object>> findStuckIngest() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT doc_id, tenant_id, reason, event_id FROM (
                    SELECT o.aggregate_id AS doc_id, o.tenant_id AS tenant_id,
                           'OUTBOX_FAILED' AS reason, o.id AS event_id, 1 AS precedence
                    FROM t_outbox_event o
                    WHERE
                    """ + REDRIVABLE_FAILED_INDEX_EVENT + """
                    UNION ALL
                    SELECT l.doc_id, l.tenant_id, 'LEDGER_PROCESSING', NULL, 2
                    FROM t_ingest_ledger l
                    WHERE l.status = 'PROCESSING' AND l.updated_at < now() - interval '5 minutes'
                      AND (
                          NOT EXISTS (SELECT 1 FROM t_document d WHERE d.id = l.doc_id)
                          OR EXISTS (
                              SELECT 1 FROM t_document d
                              WHERE d.id = l.doc_id
                                AND d.version = l.version
                                AND d.deleted_at IS NULL
                                AND d.status IN ('PENDING', 'PROCESSING')
                          )
                      )
                    UNION ALL
                    SELECT d.id, d.tenant_id, 'DOCUMENT_OVERDUE', NULL, 3
                    FROM t_document d
                    WHERE d.deleted_at IS NULL AND d.status IN ('PENDING', 'PROCESSING')
                      AND d.updated_at < now() - interval '5 minutes'
                ) s
                ORDER BY precedence, doc_id, event_id
                """);
        Map<Long, Map<String, Object>> byDoc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byDoc.putIfAbsent(((Number) row.get("doc_id")).longValue(), row);
        }
        return List.copyOf(byDoc.values());
    }

    /**
     * 显式修复（对账「一键重试」）两类动作，都幂等可重跑：
     * ① 残留的已删除文档补做产物清理——与删除链路消费者同语义（先向量后切片，账本置 DELETED）；
     * ② **当前版本、文档存在且未收敛的索引类事件**重驱动（{@code FAILED → PENDING}、retry_count 归零），
     *    交回 relay 重发；消费端按 {@code (docId, version)} 幂等仲裁，并有当前版本守卫。
     *
     * <p><b>不重驱动删除事件</b>：删除事件被放回会重新执行 purge（清全部版本切片/向量），
     * 同名重传后的新文档可能被误删；删除的后果由残留切片 / 孤儿向量判据负责。
     *
     * <p><b>不重驱动旧版本索引事件</b>：旧版本事件晚到会把旧版本产物写回、并删掉新版本切片；
     * 幂等键只能挡重复投递，挡不住“旧事件晚到”。条件见 {@link #REDRIVABLE_FAILED_INDEX_EVENT}。
     *
     * <p>边界：只覆盖**留有可重驱动 FAILED 索引事件行**的停摆。事件行压根没写进来的停摆
     * （ledger 与 outbox 都无行）仍需人工重新上传，这里不替业务伪造事件。
     */
    @Transactional
    public RepairOutcome repair() {
        List<Long> docIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT c.doc_id
                FROM t_chunk c
                JOIN t_document d ON d.id = c.doc_id
                WHERE d.deleted_at IS NOT NULL
                ORDER BY c.doc_id
                """, Long.class);
        for (Long docId : docIds) {
            vectorStore.deleteByDoc(docId);
            jdbcTemplate.update("DELETE FROM t_chunk WHERE doc_id = ?", docId);
            ledgerGuard.markDeleted(docId);
        }
        int redrivenEvents = jdbcTemplate.update("""
                UPDATE t_outbox_event o SET status = 'PENDING', retry_count = 0
                WHERE
                """ + REDRIVABLE_FAILED_INDEX_EVENT);
        if (!docIds.isEmpty() || redrivenEvents > 0) {
            log.info("对账修复完成：清理残留文档 {} 个，重驱动当前版本失败索引事件 {} 条",
                    docIds.size(), redrivenEvents);
        }
        return new RepairOutcome(docIds.size(), redrivenEvents);
    }

    /** 最近一次报告（无报告时空 Optional）。 */
    public Optional<ReportRow> latestReport() {
        List<ReportRow> rows = jdbcTemplate.query("""
                SELECT id, mismatch_count, detail_ref, created_at
                FROM t_reconcile_report
                WHERE type = 'CONSISTENCY'
                ORDER BY id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new ReportRow(rs.getLong("id"), rs.getInt("mismatch_count"),
                        rs.getString("detail_ref"),
                        String.valueOf(rs.getObject("created_at", OffsetDateTime.class))));
        return rows.stream().findFirst();
    }

    /**
     * 最近 N 轮扫描（M4 / FR-ADM-03 空态上下文）：
     * 「不一致清单为空」与「系统没在扫」必须能区分——后者近 N 轮为空、前者报告在持续新增但 mismatch=0。
     */
    public List<ReportRow> recentReports(int limit) {
        return jdbcTemplate.query("""
                SELECT id, mismatch_count, detail_ref, created_at
                FROM t_reconcile_report
                WHERE type = 'CONSISTENCY'
                ORDER BY id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ReportRow(rs.getLong("id"), rs.getInt("mismatch_count"),
                        rs.getString("detail_ref"),
                        String.valueOf(rs.getObject("created_at", OffsetDateTime.class))),
                Math.min(Math.max(limit, 1), 100));
    }

    /** 单轮扫描结论。 */
    public record ReconcileSummary(Long reportId, int mismatchCount, int staleDocuments,
                                   long orphanVectors, long stuckIngest, long failedOutboxEvents) {
    }

    /** 显式修复结论：清理的残留文档数 + 重驱动的永久失败事件数。 */
    public record RepairOutcome(int repairedDocuments, int redrivenEvents) {
    }

    /** 报告读取模型。 */
    public record ReportRow(long id, int mismatchCount, String detailsJson, String createdAt) {
    }
}
