package com.aiwarden.governance.reconcile;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一致性对账（FR-ING-06，P1「有 SLO 的可对账」的落点）：
 * 扫描「已删除文档的残留向量 / 切片」与「超时未收敛的账本（PROCESSING 卡死）」，
 * 落 {@code t_reconcile_report} + 更新指标。
 *
 * <p><b>只读发现，不自动改数据</b>——修复走显式动作（{@link #repair()}，见 ADR-006）：
 * 自动修复会让不一致清单永远为空，而对账的价值正是「发现并记录、修复可追溯」。
 *
 * <p>指标（最近一轮扫描的当前值，Gauge）：{@code aiwarden_vector_orphan_total}（孤儿向量数）、
 * {@code aiwarden_ingest_stuck_total}（超时未收敛的账本数）。
 */
@Component
public class ConsistencyReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyReconciler.class);

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final IngestLedgerGuard ledgerGuard;
    private final ObjectMapper objectMapper;
    private final boolean scanEnabled;
    private final AtomicLong orphanVectors = new AtomicLong();
    private final AtomicLong stuckProcessing = new AtomicLong();

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
        Gauge.builder("aiwarden_ingest_stuck_total", stuckProcessing, AtomicLong::get)
                .description("最近一轮对账发现的超时未收敛任务数（PROCESSING 卡死）")
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
        Long stuckCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_ingest_ledger
                WHERE status = 'PROCESSING' AND updated_at < now() - interval '5 minutes'
                """, Long.class);

        long orphanVectorsValue = orphanVectorCount == null ? 0 : orphanVectorCount;
        long stuckValue = stuckCount == null ? 0 : stuckCount;
        int mismatchCount = staleDocuments.size() + (int) stuckValue;

        orphanVectors.set(orphanVectorsValue);
        stuckProcessing.set(stuckValue);

        String detailsJson = objectMapper.writeValueAsString(Map.of(
                "staleDocuments", staleDocuments,
                "orphanVectors", orphanVectorsValue,
                "stuckProcessing", stuckValue,
                "scannedAt", OffsetDateTime.now().toString()));

        Long reportId = jdbcTemplate.queryForObject("""
                INSERT INTO t_reconcile_report (type, window_start, window_end, mismatch_count, detail_ref)
                VALUES ('CONSISTENCY', now() - interval '5 minutes', now(), ?, ?)
                RETURNING id
                """, Long.class, mismatchCount, detailsJson);

        log.info("对账完成：不一致 {}（残留文档 {}，孤儿向量 {}，超时未收敛 {}）",
                mismatchCount, staleDocuments.size(), orphanVectorsValue, stuckValue);
        return new ReconcileSummary(reportId, mismatchCount, staleDocuments.size(),
                orphanVectorsValue, stuckValue);
    }

    /**
     * 修复（对账「一键重试」的 M1 最小版）：对残留的已删除文档显式补做产物清理——
     * 与删除链路的消费者同语义（先向量后切片，账本置 DELETED），幂等可重跑。
     */
    @Transactional
    public int repair() {
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
        if (!docIds.isEmpty()) {
            log.info("对账修复完成：清理残留文档 {} 个", docIds.size());
        }
        return docIds.size();
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

    /** 单轮扫描结论。 */
    public record ReconcileSummary(Long reportId, int mismatchCount, int staleDocuments,
                                   long orphanVectors, long stuckProcessing) {
    }

    /** 报告读取模型。 */
    public record ReportRow(long id, int mismatchCount, String detailsJson, String createdAt) {
    }
}
