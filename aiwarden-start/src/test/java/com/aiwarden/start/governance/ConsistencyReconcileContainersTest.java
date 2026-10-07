package com.aiwarden.start.governance;

import com.aiwarden.contract.governance.ConsistencyRepairResponse;
import com.aiwarden.contract.governance.ConsistencyReportResponse;
import com.aiwarden.governance.reconcile.ConsistencyReconciler;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 切片④对账验证（FR-ING-06）：发现 → 报告可查 → 指标暴露 → 显式修复 → 复扫归零。
 *
 * <p>制造一致性缺口：正常写入产物后<b>绕过删除链路</b>直接软删文档（删除事件「丢失」的替身）
 * + 一条 PROCESSING 卡死 10 分钟的账本。只依赖 PG 容器（无 Kafka）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class ConsistencyReconcileContainersTest {

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
        assertThat(((Number) details.get("stuckProcessing")).longValue()).isGreaterThanOrEqualTo(1L);

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
        assertThat(after.stuckProcessing()).isZero();
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

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
