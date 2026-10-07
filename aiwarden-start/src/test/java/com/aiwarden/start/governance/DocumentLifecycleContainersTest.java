package com.aiwarden.start.governance;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.CreateKnowledgeBaseRequest;
import com.aiwarden.contract.knowledge.DocumentDeleteResponse;
import com.aiwarden.contract.knowledge.DocumentStatusResponse;
import com.aiwarden.contract.knowledge.DocumentUploadResponse;
import com.aiwarden.contract.knowledge.KnowledgeBaseResponse;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.contract.knowledge.RetrievalSearchRequest;
import com.aiwarden.contract.knowledge.RetrievalSearchResponse;
import com.aiwarden.contract.knowledge.UploadDocumentRequest;
import com.aiwarden.core.event.DocumentEventTypes;
import com.aiwarden.governance.outbox.OutboxRelay;
import com.aiwarden.governance.outbox.OutboxWriter;
import com.aiwarden.knowledge.ingest.DocumentEventPayload;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 切片③端到端验证（HTTP 全链路）：<b>上传 → 索引 → 断言产物 → 删除 → 清理</b>（P1 生命周期）。
 *
 * <p>覆盖：① 上传/删除接口与 outbox 同事务（HTTP 201 → relay 发布 → 消费 → 索引落库）；
 * ② 版本化重传（FR-ING-03：旧版本切片退役）；③ 跨租户访问 404（FR-KB-01，不暴露存在性）。
 *
 * <p>确定性编排：relay {@code @Scheduled} 全局关闭，测试直接调 {@code pollOnce()}；
 * 进度用状态接口轮询（文档状态与账本状态同为终态才算完成）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class DocumentLifecycleContainersTest {

    private static final String TENANT_A = "100";
    private static final String TENANT_B = "200";

    /** 600 字符 → 256 + 256 + 88 = 3 块。 */
    private static final String CONTENT_600 = "0123456789".repeat(60);
    private static final int EXPECTED_CHUNKS = 3;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Autowired
    private Environment environment;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    @Test
    void uploadIndexAndDelete_coverP1Lifecycle() throws Exception {
        long kbId = createKnowledgeBase(TENANT_A, "kb-lifecycle");

        DocumentUploadResponse uploaded = upload(TENANT_A, kbId, "doc-lifecycle", CONTENT_600);
        assertThat(uploaded.status()).isEqualTo("PENDING");
        assertThat(uploaded.version()).isEqualTo(1);

        outboxRelay.pollOnce();
        awaitStatus(TENANT_A, uploaded.docId(), 1, "INDEXED");

        assertThat(chunkCount(uploaded.docId())).isEqualTo(EXPECTED_CHUNKS);
        assertThat(vectorCount(uploaded.docId())).isEqualTo(EXPECTED_CHUNKS);
        Integer dims = jdbcTemplate.queryForObject("""
                SELECT vector_dims(embedding) FROM t_vector WHERE meta->>'docId' = ? LIMIT 1
                """, Integer.class, String.valueOf(uploaded.docId()));
        assertThat(dims).isEqualTo(1536);

        // ADR-007 下推地基：meta 含可见集字段（tenantId 必含），且 chunk / vector 两侧一致
        String metaTenant = jdbcTemplate.queryForObject(
                "SELECT meta->>'tenantId' FROM t_vector WHERE meta->>'docId' = ? LIMIT 1",
                String.class, String.valueOf(uploaded.docId()));
        assertThat(metaTenant).isEqualTo("100");
        Integer metaMismatch = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_chunk c JOIN t_vector v ON v.chunk_id = c.id
                WHERE c.meta IS DISTINCT FROM v.meta
                """, Integer.class);
        assertThat(metaMismatch).isZero();

        // P4a 计量落库（与 Outbox 共用消费骨架）：索引处理已写入 3 条嵌入计量事件，发布并等待落库
        outboxRelay.pollOnce();
        awaitMetering(100L, EXPECTED_CHUNKS);

        DocumentDeleteResponse deleted = delete(TENANT_A, uploaded.docId());
        assertThat(deleted.deleteTaskId()).isEqualTo(uploaded.docId() + ":1");

        outboxRelay.pollOnce();
        awaitStatus(TENANT_A, uploaded.docId(), 1, "DELETED");

        assertThat(chunkCount(uploaded.docId())).isZero();
        assertThat(vectorCount(uploaded.docId())).isZero();
        Boolean softDeleted = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM t_document WHERE id = ?", Boolean.class, uploaded.docId());
        assertThat(softDeleted).isTrue();
    }

    @Test
    void reUpload_createsNewVersion_andRetiresOldChunks() throws Exception {
        long kbId = createKnowledgeBase(TENANT_A, "kb-versioning");

        DocumentUploadResponse v1 = upload(TENANT_A, kbId, "doc-versioned", CONTENT_600);
        outboxRelay.pollOnce();
        awaitStatus(TENANT_A, v1.docId(), 1, "INDEXED");

        DocumentUploadResponse v2 = upload(TENANT_A, kbId, "doc-versioned", CONTENT_600);
        assertThat(v2.docId()).isEqualTo(v1.docId());
        assertThat(v2.version()).isEqualTo(2);

        outboxRelay.pollOnce();
        awaitStatus(TENANT_A, v2.docId(), 2, "INDEXED");

        // FR-ING-03：新版本就绪后，旧版本切片与向量失效
        assertThat(chunkCountOfVersion(v1.docId(), 1)).isZero();
        assertThat(chunkCountOfVersion(v1.docId(), 2)).isEqualTo(EXPECTED_CHUNKS);
        assertThat(vectorCount(v1.docId())).isEqualTo(EXPECTED_CHUNKS);
    }

    /**
     * P1 验收（FR-ING-05 口径：单次确定性断言）：删除收敛后，该文档不得出现在任何命中里。
     * 本用例取 5 轮样本全部 < 5s（SLO 层面的 P95 形式化度量留压测报告，两者不混写）。
     */
    @Test
    void deleteTakesEffectWithinFiveSeconds_acrossFiveRounds() throws Exception {
        long kbId = createKnowledgeBase(TENANT_A, "kb-delete-slo");
        String firstChunk = CONTENT_600.substring(0, 256);

        for (int round = 1; round <= 5; round++) {
            DocumentUploadResponse uploaded = upload(TENANT_A, kbId, "doc-r" + round, CONTENT_600);
            outboxRelay.pollOnce();
            awaitStatus(TENANT_A, uploaded.docId(), 1, "INDEXED");

            // 删除前：用第一块文本查询（确定性嵌入下距离≈0）必须命中该文档
            assertThat(search(TENANT_A, firstChunk))
                    .anyMatch(hit -> hit.docId() == uploaded.docId());

            long deleteStartNanos = System.nanoTime();
            delete(TENANT_A, uploaded.docId());
            outboxRelay.pollOnce();
            awaitStatus(TENANT_A, uploaded.docId(), 1, "DELETED");

            long elapsedMs = (System.nanoTime() - deleteStartNanos) / 1_000_000;
            assertThat(search(TENANT_A, firstChunk))
                    .noneMatch(hit -> hit.docId() == uploaded.docId());
            assertThat(elapsedMs).isLessThan(5_000L);
        }
    }

    /**
     * M1 验收②（端到端版）：16 线程并发投递同一 (docId, version) 的索引事件，
     * 消费端唯一键仲裁保证只处理一次——向量数 == 切片数（无重复写入）。
     */
    @Test
    void concurrentIndexEvents_produceExactChunkCountOnce() throws Exception {
        String topic = DocumentEventTypes.INDEX_REQUESTED;
        String groupId = "aiwarden-ingest";
        long baseline = committedOffset(topic, groupId);

        long kbId = createKnowledgeBase(TENANT_A, "kb-concurrent");
        DocumentUploadResponse uploaded = upload(TENANT_A, kbId, "doc-concurrent", CONTENT_600);
        String payload = objectMapper.writeValueAsString(
                new DocumentEventPayload(uploaded.docId(), uploaded.version()));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    outboxWriter.append(100L, uploaded.docId(), topic, payload);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        outboxRelay.pollOnce();
        // 并发 16 条 + 上传原生 1 条，全部消费完（1 条处理 + 16 条幂等跳过）
        awaitCommittedOffset(topic, groupId, baseline + threads + 1);

        assertThat(chunkCount(uploaded.docId())).isEqualTo(EXPECTED_CHUNKS);
        assertThat(vectorCount(uploaded.docId())).isEqualTo(EXPECTED_CHUNKS);
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_ingest_ledger WHERE doc_id = ?", Integer.class, uploaded.docId());
        assertThat(ledgerRows).isEqualTo(1);
    }

    @Test
    void crossTenantAccess_returns404() throws Exception {
        long kbId = createKnowledgeBase(TENANT_A, "kb-tenant-a");
        DocumentUploadResponse uploaded = upload(TENANT_A, kbId, "doc-private", CONTENT_600);

        HttpResponse<String> statusByOther = send("GET",
                "/api/v1/documents/" + uploaded.docId() + "/status", TENANT_B, null);
        assertThat(statusByOther.statusCode()).isEqualTo(404);

        HttpResponse<String> deleteByOther = send("DELETE",
                "/api/v1/documents/" + uploaded.docId(), TENANT_B, null);
        assertThat(deleteByOther.statusCode()).isEqualTo(404);

        HttpResponse<String> uploadByOther = send("POST",
                "/api/v1/knowledge-bases/" + kbId + "/documents", TENANT_B,
                objectMapper.writeValueAsString(new UploadDocumentRequest("doc-x", "content")));
        assertThat(uploadByOther.statusCode()).isEqualTo(404);
    }

    /**
     * 检索层租户隔离：<b>隔离自身（存在性 404）与隔离检索内容是两件事</b>——前者已由
     * {@link #crossTenantAccess_returns404} 覆盖，后者必须单独断言，否则「检索忘了带租户条件」
     * 这类缺陷不会被任何用例发现。
     *
     * <p>这是个**能失败的断言**：确定性嵌入下，用租户 A 的切片原文查询，最近邻就是它自己；
     * 一旦下推过滤丢掉租户条件，租户 B 必然命中此处。
     *
     * <p>租户 B 先建立<b>自己的可见集</b>再查询——排除「空可见集 → 403」路径，
     * 直击「可见集非空但无该内容」的命中边界（ADR-008 空集拒绝后，无可见 KB 的租户
     * 查任何内容都会在计算阶段被拒，不再是 200 空列表）。
     */
    @Test
    void crossTenantRetrieval_returnsNoHits() throws Exception {
        long kbId = createKnowledgeBase(TENANT_A, "kb-tenant-search");
        DocumentUploadResponse uploaded = upload(TENANT_A, kbId, "doc-search", CONTENT_600);
        outboxRelay.pollOnce();
        awaitStatus(TENANT_A, uploaded.docId(), 1, "INDEXED");

        String firstChunk = CONTENT_600.substring(0, 256);

        // 先证明确实可被检索到——排除「因索引未生效而 0 命中」的假阳性
        assertThat(search(TENANT_A, firstChunk))
                .anyMatch(hit -> hit.docId() == uploaded.docId());

        // 租户 B 有自己的可见 KB（可见集非空）
        createKnowledgeBase(TENANT_B, "kb-tenant-b-search");

        // 同一段文本，换租户查：不得命中
        assertThat(search(TENANT_B, firstChunk)).isEmpty();
    }

    // ---- helpers ----

    private List<RetrievalHit> search(String tenant, String query) throws Exception {
        // M2：检索入口要求主体（ADR-008）——统一以「无组织归属」主体查询；
        // 本类建的知识库均为公共（org_id 为空），无组织主体即可见。
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/retrieval/search"))
                .header(TenantContext.TENANT_ID_HEADER, tenant)
                .header(PrincipalContext.USER_ID_HEADER, "9901")
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(new RetrievalSearchRequest(query, 10, null)),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), RetrievalSearchResponse.class).hits();
    }

    private void awaitMetering(long tenantId, int atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM t_llm_call_log WHERE tenant_id = ? AND tool = 'embedding'",
                    Integer.class, tenantId);
            if (count != null && count >= atLeast) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("等待计量落库超时（期望 >= " + atLeast + " 条）");
    }

    private long committedOffset(String topic, String groupId) throws Exception {
        try (AdminClient admin = adminClient()) {
            return offsetFor(admin, topic, groupId);
        }
    }

    private void awaitCommittedOffset(String topic, String groupId, long atLeast) throws Exception {
        try (AdminClient admin = adminClient()) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (offsetFor(admin, topic, groupId) >= atLeast) {
                    return;
                }
                Thread.sleep(200);
            }
            throw new AssertionError("消费组 " + groupId + " 未在 30s 内在 " + topic
                    + " 上提交到 offset " + atLeast);
        }
    }

    private long offsetFor(AdminClient admin, String topic, String groupId) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        return offsets.entrySet().stream()
                .filter(entry -> entry.getKey().topic().equals(topic))
                .mapToLong(entry -> entry.getValue().offset())
                .sum();
    }

    private AdminClient adminClient() {
        return AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    private int chunkCount(long docId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_chunk WHERE doc_id = ?", Integer.class, docId);
    }

    private int chunkCountOfVersion(long docId, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_chunk WHERE doc_id = ? AND version = ?", Integer.class, docId, version);
    }

    private int vectorCount(long docId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_vector WHERE meta->>'docId' = ?", Integer.class, String.valueOf(docId));
    }

    private void awaitStatus(String tenant, long docId, int expectedVersion, String expectedStatus)
            throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        String lastSeen = null;
        while (System.currentTimeMillis() < deadline) {
            DocumentStatusResponse status = getStatus(tenant, docId);
            lastSeen = status.version() + "/" + status.status() + "/" + status.ledgerStatus();
            if (status.version() == expectedVersion
                    && expectedStatus.equals(status.status())
                    && expectedStatus.equals(status.ledgerStatus())) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("等待 v" + expectedVersion + " 状态 " + expectedStatus
                + " 超时，最后看到：" + lastSeen);
    }

    private long createKnowledgeBase(String tenant, String name) throws Exception {
        HttpResponse<String> response = post("/api/v1/knowledge-bases", tenant,
                new CreateKnowledgeBaseRequest(name));
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), KnowledgeBaseResponse.class).id();
    }

    private DocumentUploadResponse upload(String tenant, long kbId, String name, String content) throws Exception {
        HttpResponse<String> response = post("/api/v1/knowledge-bases/" + kbId + "/documents", tenant,
                new UploadDocumentRequest(name, content));
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), DocumentUploadResponse.class);
    }

    private DocumentDeleteResponse delete(String tenant, long docId) throws Exception {
        HttpResponse<String> response = send("DELETE", "/api/v1/documents/" + docId, tenant, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), DocumentDeleteResponse.class);
    }

    private DocumentStatusResponse getStatus(String tenant, long docId) throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/v1/documents/" + docId + "/status", tenant, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), DocumentStatusResponse.class);
    }

    private HttpResponse<String> post(String path, String tenant, Object body) throws Exception {
        return send("POST", path, tenant, objectMapper.writeValueAsString(body));
    }

    private HttpResponse<String> send(String method, String path, String tenant, String jsonBody)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (tenant != null) {
            builder.header(TenantContext.TENANT_ID_HEADER, tenant);
        }
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
