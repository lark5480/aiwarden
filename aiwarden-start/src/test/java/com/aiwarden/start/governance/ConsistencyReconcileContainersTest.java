package com.aiwarden.start.governance;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.governance.ConsistencyRepairResponse;
import com.aiwarden.contract.governance.ConsistencyReportResponse;
import com.aiwarden.governance.reconcile.ConsistencyReconciler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 切片④对账验证（FR-ING-06）：发现 → 报告可查 → 指标暴露 → 显式修复 → 复扫归零。
 *
 * <p>制造一致性缺口：正常写入产物后<b>绕过删除链路</b>直接软删文档（删除事件「丢失」的替身）
 * + 一条 PROCESSING 卡死 10 分钟的账本。只依赖 PG 容器（无 Kafka）。
 *
 * <p>2026-10-19 审计补上的<b>摄入停摆判据</b>（事件永久失败 / 文档超时）在这里有回归：
 * 第三个用例覆盖“FAILED 事件 + 文档超时、账本没有行”的原始缺口；后三个用例覆盖
 * 删除事件不重驱动、旧版本索引事件不重驱动、旧版本账本不误报。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class ConsistencyReconcileContainersTest {

    /** 身份头取值（与 {@link #seedInconsistentData()} 写入的 tenant_id 一致）。 */
    private static final String TENANT_ID = "301";
    private static final String USER_ID = "1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private ConsistencyReconciler reconciler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;
    /** 本用例播种的停摆文档 / 知识库，用于 {@code @AfterEach} 自清理。 */
    private Long stuckDocId;
    private Long stuckKbId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    @Test
    void detectsResidue_thenReportMetricAndExplicitRepair() throws Exception {
        long docId = seedInconsistentData();

        // ① 手动触发扫描（POST /scan）→ 报告可查：不一致数 ≥ 2（残留文档 + 卡死任务）
        HttpResponse<String> scanResponse = send("POST", "/api/v1/admin/consistency/scan");
        assertThat(scanResponse.statusCode()).isEqualTo(200);
        ConsistencyReportResponse report = objectMapper.readValue(
                scanResponse.body(), ConsistencyReportResponse.class);
        assertThat(report.mismatchCount()).isGreaterThanOrEqualTo(2);
        assertThat(report.detailsJson()).contains(String.valueOf(docId));

        Map<String, Object> details = objectMapper.readValue(report.detailsJson(), Map.class);
        assertThat(((Number) details.get("orphanVectors")).longValue()).isGreaterThanOrEqualTo(1L);
        // 摄入停摆清单（三条子判据的并集）：这里命中的是账本 PROCESSING 超时
        assertThat(stuckDocIds(details)).contains(999001L);

        // M4 / FR-ADM-03：最近 N 轮扫描可查——空态上下文（近 N 轮是否在扫、每轮 mismatch）由此而来
        HttpResponse<String> reportsResponse = send("GET", "/api/v1/admin/consistency/reports?limit=5");
        assertThat(reportsResponse.statusCode()).isEqualTo(200);
        var reports = objectMapper.readTree(reportsResponse.body());
        assertThat(reports.size()).isGreaterThanOrEqualTo(1);
        assertThat(reports.get(0).path("reportId").asLong()).isEqualTo(report.reportId());
        assertThat(reports.get(0).path("mismatchCount").asInt()).isGreaterThanOrEqualTo(2);

        // ② 指标暴露（Gauge 为最近一轮扫描的当前值）
        assertThat(meterRegistry.get("aiwarden_vector_orphan_total").gauge().value())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("aiwarden_ingest_stuck_total").gauge().value())
                .isGreaterThanOrEqualTo(1.0);

        // ③ 显式修复（POST /repair）→ 清掉卡死行 → 复扫归零（对账可见修复闭环）
        HttpResponse<String> repairResponse = send("POST", "/api/v1/admin/consistency/repair");
        assertThat(repairResponse.statusCode()).isEqualTo(200);
        ConsistencyRepairResponse repaired = objectMapper.readValue(
                repairResponse.body(), ConsistencyRepairResponse.class);
        assertThat(repaired.repairedDocuments()).isGreaterThanOrEqualTo(1);

        jdbcTemplate.update("DELETE FROM t_ingest_ledger WHERE doc_id = 999001");
        ConsistencyReconciler.ReconcileSummary after = reconciler.runOnce();
        assertThat(after.mismatchCount()).isZero();
        assertThat(after.orphanVectors()).isZero();
        assertThat(after.stuckIngest()).isZero();
    }

    /**
     * P1-01 回归（2026-10-19 审计发现的静默缺口）：outbox 事件重试到上限置 FAILED 后，
     * 文档停在 PENDING、**账本一行都没有**——当时的判据（只看 ledger 的 PROCESSING 超时）看不到它，
     * 表现为「不报错、不进不一致清单、指标恒 0」。现在摄入停摆判据必须命中它。
     *
     * <p>同时自证两件事：① 三条子判据按 doc_id **去重**（同一文档既「事件永久失败」又「文档超时」
     * 只算一条，reason 取优先级最高的 OUTBOX_FAILED）；② 重驱动 ≠ 已收敛——repair 把事件置回
     * PENDING 后文档仍停摆，reason 退化为 DOCUMENT_OVERDUE，只有摄入真的完成才归零。
     */
    @Test
    void detectsStuckIngest_whenOutboxEventPermanentlyFailed_thenRedriveDoesNotFakeConvergence() throws Exception {
        long docId = seedStuckIngestViaFailedEvent();

        HttpResponse<String> scanResponse = send("POST", "/api/v1/admin/consistency/scan");
        assertThat(scanResponse.statusCode()).isEqualTo(200);
        ConsistencyReportResponse report = objectMapper.readValue(
                scanResponse.body(), ConsistencyReportResponse.class);
        Map<String, Object> details = objectMapper.readValue(report.detailsJson(), Map.class);

        // ① 命中：该文档进了不一致清单，且 mismatchCount 因它而 ≥ 1
        assertThat(report.mismatchCount()).isGreaterThanOrEqualTo(1);
        assertThat(stuckDocIds(details)).contains(docId);
        assertThat(stuckReason(details, docId)).isEqualTo("OUTBOX_FAILED");
        assertThat(((Number) details.get("failedOutboxEvents")).longValue()).isGreaterThanOrEqualTo(1L);
        // 去重自证：同一个 doc 在清单里只有一条（否则 DOCUMENT_OVERDUE 会再算一遍）
        assertThat(stuckDocIds(details).stream().filter(id -> id == docId).count()).isEqualTo(1L);
        // ② 指标：aiwarden_ingest_stuck_total 确实被本轮抬起来（注册名；导出名会被剥掉 _total）
        assertThat(meterRegistry.get("aiwarden_ingest_stuck_total").gauge().value())
                .isGreaterThanOrEqualTo(1.0);

        // ③ 显式修复：重驱动 FAILED 事件（FAILED → PENDING、retry_count 归零）
        HttpResponse<String> repairResponse = send("POST", "/api/v1/admin/consistency/repair");
        assertThat(repairResponse.statusCode()).isEqualTo(200);
        ConsistencyRepairResponse repaired = objectMapper.readValue(
                repairResponse.body(), ConsistencyRepairResponse.class);
        assertThat(repaired.redrivenEvents()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ':' || retry_count FROM t_outbox_event WHERE aggregate_id = ? AND type = 'document.index.requested'",
                String.class, docId)).isEqualTo("PENDING:0");

        // ④ 重驱动不等于收敛：文档仍停摆，reason 退化为 DOCUMENT_OVERDUE、永久失败事件数归零
        ConsistencyReconciler.ReconcileSummary afterRedrive = reconciler.runOnce();
        assertThat(afterRedrive.failedOutboxEvents()).isZero();
        ConsistencyReportResponse stillStuck = objectMapper.readValue(
                send("POST", "/api/v1/admin/consistency/scan").body(), ConsistencyReportResponse.class);
        Map<String, Object> stillDetails = objectMapper.readValue(
                stillStuck.detailsJson(), Map.class);
        assertThat(stuckDocIds(stillDetails)).contains(docId);
        assertThat(stuckReason(stillDetails, docId)).isEqualTo("DOCUMENT_OVERDUE");

        // ⑤ 摄入真正完成（消费者替身）后，该文档不再出现在停摆清单里
        jdbcTemplate.update("UPDATE t_document SET status = 'INDEXED', updated_at = now() WHERE id = ?", docId);
        ConsistencyReconciler.ReconcileSummary converged = reconciler.runOnce();
        Map<String, Object> convergedDetails = objectMapper.readValue(
                reconciler.latestReport().orElseThrow().detailsJson(), Map.class);
        assertThat(converged.mismatchCount()).isLessThan(afterRedrive.mismatchCount());
        assertThat(stuckDocIds(convergedDetails)).doesNotContain(docId);

        // ⑥ 假阳性守卫：文档已收敛后，事件即使呈 FAILED 也不算停摆（否则「已被消费的事件被标失败」会误报）
        jdbcTemplate.update("""
                UPDATE t_outbox_event SET status = 'FAILED'
                WHERE aggregate_id = ? AND type = 'document.index.requested'
                """, docId);
        ConsistencyReconciler.ReconcileSummary afterPhantom = reconciler.runOnce();
        Map<String, Object> phantomDetails = objectMapper.readValue(
                reconciler.latestReport().orElseThrow().detailsJson(), Map.class);
        assertThat(afterPhantom.failedOutboxEvents()).as("已收敛文档的 FAILED 事件不计入未收敛数").isZero();
        assertThat(stuckDocIds(phantomDetails)).doesNotContain(docId);
    }

    /**
     * C1 回归（2026-10-19 审计）：删除事件 FAILED 后同名重传，旧删除事件不能被当成当前停摆，
     * 也不能被 repair 重驱动——否则消费者会 purge 掉重传后的文档产物。
     */
    @Test
    void doesNotRedriveFailedDeleteEvent_whenDocumentWasReuploaded() throws Exception {
        long docId = seedDocument("kb-reuploaded", "doc-reuploaded", 2, "PENDING");
        seedFailedOutboxEvent(docId, "document.delete.requested", 1);

        ConsistencyReportResponse report = scanReport();
        Map<String, Object> details = parseDetails(report);
        assertThat(stuckDocIds(details)).contains(docId);
        assertThat(stuckReason(details, docId)).isEqualTo("DOCUMENT_OVERDUE");
        assertThat(((Number) details.get("failedOutboxEvents")).longValue()).isZero();

        ConsistencyRepairResponse repaired = repairResponse();
        assertThat(repaired.repairedDocuments()).isZero();
        assertThat(repaired.redrivenEvents()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_outbox_event WHERE aggregate_id = ? AND type = 'document.delete.requested'",
                String.class, docId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_document WHERE id = ?", String.class, docId)).isEqualTo("PENDING");
    }

    /** C2 回归：旧版本索引事件不能被当成当前停摆，更不能被重驱动后覆盖新版本产物。 */
    @Test
    void doesNotRedriveFailedIndexEvent_ofSupersededVersion() throws Exception {
        long docId = seedDocument("kb-old-version", "doc-old-version", 2, "PENDING");
        seedFailedOutboxEvent(docId, "document.index.requested", 1);

        Map<String, Object> details = parseDetails(scanReport());
        assertThat(stuckReason(details, docId)).isEqualTo("DOCUMENT_OVERDUE");
        assertThat(((Number) details.get("failedOutboxEvents")).longValue()).isZero();

        ConsistencyRepairResponse repaired = repairResponse();
        assertThat(repaired.redrivenEvents()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_outbox_event WHERE aggregate_id = ? AND type = 'document.index.requested'",
                String.class, docId)).isEqualTo("FAILED");
    }

    /** C3 回归：旧版本账本 PROCESSING 超时、当前版本已 INDEXED 时，不应报成当前摄入停摆。 */
    @Test
    void ignoresProcessingLedger_ofSupersededVersion() throws Exception {
        long docId = seedDocument("kb-stale-ledger", "doc-stale-ledger", 2, "INDEXED");
        seedProcessingLedger(docId, 1);

        ConsistencyReportResponse report = scanReport();
        Map<String, Object> details = parseDetails(report);
        assertThat(stuckDocIds(details)).doesNotContain(docId);
        assertThat(report.mismatchCount()).isZero();
    }

    /** 播种一个指定版本/状态的文档（不写事件/账本），供 C1/C2/C3 回归使用。 */
    private long seedDocument(String kbName, String docName, int version, String status) {
        Long kbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (301, ?)
                RETURNING id
                """, Long.class, kbName);
        Long docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status, updated_at)
                VALUES (301, ?, ?, 'placeholder', ?, ?, now() - interval '10 minutes')
                RETURNING id
                """, Long.class, kbId, docName, version, status);
        stuckDocId = docId;
        stuckKbId = kbId;
        return docId;
    }

    private void seedFailedOutboxEvent(long docId, String type, int payloadVersion) {
        jdbcTemplate.update("""
                INSERT INTO t_outbox_event (tenant_id, aggregate_id, type, payload, status, retry_count)
                VALUES (301, ?, ?, jsonb_build_object('docId', ?::bigint, 'version', ?), 'FAILED', 5)
                """, docId, type, docId, payloadVersion);
    }

    private void seedProcessingLedger(long docId, int version) {
        jdbcTemplate.update("""
                INSERT INTO t_ingest_ledger (doc_id, version, tenant_id, status, updated_at)
                VALUES (?, ?, 301, 'PROCESSING', now() - interval '10 minutes')
                """, docId, version);
    }

    private ConsistencyReportResponse scanReport() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/admin/consistency/scan");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ConsistencyReportResponse.class);
    }

    private ConsistencyRepairResponse repairResponse() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/admin/consistency/repair");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ConsistencyRepairResponse.class);
    }

    private Map<String, Object> parseDetails(ConsistencyReportResponse report) throws Exception {
        return objectMapper.readValue(report.detailsJson(), Map.class);
    }

    /** 停摆场景替身：文档 PENDING 超时 + 一条永久失败的索引事件，**没有账本行**（缺口正是这个形态）。 */
    private long seedStuckIngestViaFailedEvent() {
        Long kbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (301, 'kb-stuck-ingest')
                RETURNING id
                """, Long.class);
        Long docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, status, updated_at)
                VALUES (301, ?, 'doc-stuck', 'placeholder', 'PENDING', now() - interval '10 minutes')
                RETURNING id
                """, Long.class, kbId);
        // payload 用 jsonb_build_object 而不是把占位符写进 JSON 字面量（后者会被当成第二个参数）
        jdbcTemplate.update("""
                INSERT INTO t_outbox_event (tenant_id, aggregate_id, type, payload, status, retry_count)
                VALUES (301, ?, 'document.index.requested',
                        jsonb_build_object('docId', ?::bigint, 'version', 1), 'FAILED', 5)
                """, docId, docId);
        stuckDocId = docId;
        stuckKbId = kbId;
        return docId;
    }

    /** 本用例自己播的数据自己清掉——否则「文档 PENDING 超时」会污染同类的另一个用例（实测踩过）。 */
    @AfterEach
    void clearSeededData() {
        if (stuckDocId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM t_outbox_event WHERE aggregate_id = ?", stuckDocId);
        jdbcTemplate.update("DELETE FROM t_ingest_ledger WHERE doc_id = ?", stuckDocId);
        jdbcTemplate.update("DELETE FROM t_document WHERE id = ?", stuckDocId);
        if (stuckKbId != null) {
            jdbcTemplate.update("DELETE FROM t_knowledge_base WHERE id = ?", stuckKbId);
        }
        stuckDocId = null;
        stuckKbId = null;
    }

    /** detailsJson.stuckIngest 里的 doc_id 列表。 */
    private List<Long> stuckDocIds(Map<String, Object> details) {
        return stuckRows(details).stream()
                .map(row -> ((Number) row.get("doc_id")).longValue())
                .toList();
    }

    /** 指定文档的停摆原因（子判据优先级即 reason 的取值）。 */
    private String stuckReason(Map<String, Object> details, long docId) {
        return stuckRows(details).stream()
                .filter(row -> ((Number) row.get("doc_id")).longValue() == docId)
                .map(row -> String.valueOf(row.get("reason")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("停摆清单里没有 docId=" + docId));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stuckRows(Map<String, Object> details) {
        return (List<Map<String, Object>>) details.get("stuckIngest");
    }

    /** 漏洞场景替身：文档已软删但产物残留（删除事件丢失）+ 一条卡死 10 分钟的账本。 */
    private long seedInconsistentData() {
        Long kbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (301, 'kb-reconcile')
                RETURNING id
                """, Long.class);
        Long docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content)
                VALUES (301, ?, 'doc-reconcile', 'placeholder')
                RETURNING id
                """, Long.class, kbId);
        Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                VALUES (301, ?, 1, 0, '残留切片', '{}'::jsonb)
                RETURNING id
                """, Long.class, docId);
        jdbcTemplate.update("""
                INSERT INTO t_vector (chunk_id, embedding, meta)
                VALUES (?, ('[' || repeat('0.1,', 1535) || '0.1]')::vector, '{}'::jsonb)
                """, chunkId);
        // 绕过 DELETE API：只改文档状态、不产生 outbox 删除事件
        jdbcTemplate.update(
                "UPDATE t_document SET deleted_at = now(), status = 'DELETED' WHERE id = ?", docId);
        jdbcTemplate.update("""
                INSERT INTO t_ingest_ledger (doc_id, version, tenant_id, status, updated_at)
                VALUES (999001, 1, 301, 'PROCESSING', now() - interval '10 minutes')
                """);
        return docId;
    }

    /**
     * 带身份头发请求。
     *
     * <p>2026-10-10 起 B 端管理端点统一要求「租户 + 主体」齐备（见 {@code AdminAccess}）——
     * 本测试原先裸调 {@code /consistency/*}，新纪律下会 400，故补上头（这也是把口径固化进测试的意义）。
     */
    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(TenantContext.TENANT_ID_HEADER, TENANT_ID)
                .header(PrincipalContext.USER_ID_HEADER, USER_ID)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
