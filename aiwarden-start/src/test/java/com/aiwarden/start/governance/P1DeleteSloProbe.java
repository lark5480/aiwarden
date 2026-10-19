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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 删除 SLO 探针（2026-10-19 补测，回应 STATUS §3「P95 未量化」）。
 *
 * <p><b>为什么不能直接用 {@code DocumentLifecycleContainersTest} 的 5 轮用例算 P95</b>：
 * 那个用例手动调 {@code outboxRelay.pollOnce()}，绕过了生产环境 {@code @Scheduled} 的 2s 轮询等待；
 * 5 个样本也撑不起分位数。本探针把「删除 → 检索 0 命中」放回**真实调度节奏**下计时：
 * relay 调度开启、{@code fixedDelay=2000ms}（生产默认），全程不手动触发 relay。
 *
 * <p><b>机制自证</b>：探针从不调用 {@code pollOnce()}——{@code INDEXED} 状态能到达，本身就证明
 * relay 定时任务在工作；如果调度没生效，{@code awaitStatus(INDEXED)} 会超时而不是静默通过。
 *
 * <p><b>计时终点</b>：HTTP DELETE 返回后，按文档原文检索，直到目标 docId **不再出现在任何命中里**
 * （不是只看状态变成 DELETED——P1 的承诺是「检索不到」，状态只是中间量）。
 *
 * <p><b>不在默认 {@code mvn verify} 里跑</b>：类名不以 Test 结尾，默认 surefire includes 不匹配；
 * 手动命令见 {@code docs/STATUS.md} §2「复现口径」。30 轮 ≈ 2–4 分钟（受 2s 调度节奏约束）。
 */
@Tag("slo")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        // 与生产默认一致：调度开启、轮询间隔 2000ms（根 pom 的 surefire 全局把它关掉，这里显式打开）
        "aiwarden.outbox.relay.enabled=true",
        "aiwarden.outbox.relay.poll-interval-ms=2000"
})
class P1DeleteSloProbe {

    private static final String TENANT = "100";
    private static final String PRINCIPAL = "9901";

    /** 600 字符 → 3 块；每轮前置唯一前缀，保证「按原文检索」时目标是最近邻而不是同质样本里的随机一条。 */
    private static final String CONTENT = "0123456789".repeat(60);

    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = 30;
    private static final long SLO_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 50L;
    private static final long ROUND_TIMEOUT_MS = 15_000L;

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
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired(required = false)
    private TaskScheduler taskScheduler;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    /**
     * 测试结束先停后台任务：否则 Testcontainers 先停 Kafka、relay 仍在 {@code send().get(10s)} 重试，
     * Surefire 会等 30s 才杀 fork（日志里是一条看着像失败的 "Surefire is going to kill self fork JVM"）。
     * 停 listener + 调度器都在容器停止之前完成，退出就干净了。
     */
    @AfterEach
    void stopBackgroundWork() {
        listenerRegistry.stop();
        if (taskScheduler instanceof ThreadPoolTaskScheduler scheduler) {
            scheduler.shutdown();
        }
    }

    @Test
    void p95DeleteTakesEffectWithinFiveSeconds() throws Exception {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        long kbId = createKnowledgeBase("kb-p1-delete-slo");

        // 预热：容器/JIT/HNSW 图的首轮效应不计入样本（与 M4 压测报告的 warmup 口径一致）
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            double ms = measureOneRound(kbId, "warm-" + i);
            System.out.printf(Locale.ROOT, "[warmup %d] %.1f ms%n", i + 1, ms);
        }

        List<Double> samples = new ArrayList<>(MEASURED_ROUNDS);
        for (int i = 0; i < MEASURED_ROUNDS; i++) {
            double ms = measureOneRound(kbId, "slo-" + i);
            samples.add(ms);
            System.out.printf(Locale.ROOT, "[round %02d] %.1f ms%n", i + 1, ms);
        }

        List<Double> sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
        double p50 = percentile(sorted, 0.50);
        double p95 = percentile(sorted, 0.95);
        double max = sorted.get(sorted.size() - 1);
        long over = samples.stream().filter(v -> v >= SLO_MS).count();

        String summary = String.format(Locale.ROOT,
                "P1 delete SLO: rounds=%d warmup=%d p50=%.1fms p95=%.1fms max=%.1fms over5s=%d "
                        + "relay=@Scheduled(fixedDelay=2000ms) tenant=%s",
                MEASURED_ROUNDS, WARMUP_ROUNDS, p50, p95, max, over, TENANT);
        System.out.println(summary);

        // 原始输出留档（与 M4 探针同一做法：target 下覆盖写，方便逐项复核）
        Path out = Path.of("target", "p1-delete-slo.txt");
        Files.createDirectories(out.getParent());
        List<String> lines = new ArrayList<>();
        lines.add(summary);
        lines.add("machine: " + System.getProperty("os.name") + " / availableProcessors="
                + Runtime.getRuntime().availableProcessors() + " / maxHeapMB="
                + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        lines.add("samples-ms: " + samples.stream()
                .map(v -> String.format(Locale.ROOT, "%.1f", v)).toList());
        Files.write(out, lines, StandardCharsets.UTF_8);

        assertThat(p95)
                .as("P1 SLO：删除后 5s 内检索 0 命中；30 轮 P95 必须 < %d ms（实测 %.1f ms，max %.1f ms）"
                        + "，逐轮样本：%s", SLO_MS, p95, max, samples)
                .isLessThan(SLO_MS);
    }

    /** 一轮：上传 → 等 INDEXED（调度自证）→ 确认删除前可命中 → DELETE → 轮询到检索 0 命中。 */
    private double measureOneRound(long kbId, String name) throws Exception {
        String content = "p1-slo-" + name + " " + CONTENT;
        String query = content.substring(0, 256);

        DocumentUploadResponse uploaded = upload(TENANT, kbId, name, content);
        awaitStatus(TENANT, uploaded.docId(), 1, "INDEXED");

        // 删除前必须命中——否则「删除后 0 命中」可能只是索引没生效的假阳性
        assertThat(search(TENANT, query))
                .as("删除前应能按原文检索到 %s", name)
                .anyMatch(hit -> hit.docId() == uploaded.docId());

        long startNanos = System.nanoTime();
        delete(TENANT, uploaded.docId());

        long deadline = startNanos + ROUND_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (search(TENANT, query).stream().noneMatch(hit -> hit.docId() == uploaded.docId())) {
                return (System.nanoTime() - startNanos) / 1_000_000.0;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("删除后 " + ROUND_TIMEOUT_MS + "ms 仍能检索到 docId=" + uploaded.docId()
                + "（name=" + name + "）");
    }

    /** nearest-rank：ceil(p * n) - 1（与 M4 压测报告同一口径）。 */
    private static double percentile(List<Double> sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
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

    private long createKnowledgeBase(String name) throws Exception {
        HttpResponse<String> response = post("/api/v1/knowledge-bases", TENANT,
                new CreateKnowledgeBaseRequest(name));
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), KnowledgeBaseResponse.class).id();
    }

    private DocumentUploadResponse upload(String tenant, long kbId, String name, String content)
            throws Exception {
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
        HttpResponse<String> response = send("GET", "/api/v1/documents/" + docId + "/status", tenant, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), DocumentStatusResponse.class);
    }

    private List<RetrievalHit> search(String tenant, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/retrieval/search"))
                .header(TenantContext.TENANT_ID_HEADER, tenant)
                .header(PrincipalContext.USER_ID_HEADER, PRINCIPAL)
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
