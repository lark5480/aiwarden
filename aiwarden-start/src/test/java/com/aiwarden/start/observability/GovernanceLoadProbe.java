package com.aiwarden.start.observability;

import com.aiwarden.agent.orchestration.ChatOrchestrator;
import com.aiwarden.agent.orchestration.ChatStreamSink;
import com.aiwarden.agent.orchestration.OrchestrationResult;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.governance.audit.AuditLogWriter;
import com.aiwarden.governance.metering.CallMeteringPayload;
import com.aiwarden.governance.outbox.OutboxWriter;
import com.aiwarden.governance.quota.BudgetService;
import com.aiwarden.governance.quota.QuotaGuard;
import com.aiwarden.governance.quota.QuotaService;
import com.aiwarden.governance.tracing.TraceSupport;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.governance.visibility.VisibilitySetCalculator;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import io.micrometer.tracing.Span;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 压测探针（**不参与 `mvn verify`**：类名不以 Test 结尾，且运行 minutes 级）。
 *
 * <p>手动执行：{@code mvn -pl aiwarden-start -am test -Dtest=GovernanceLoadProbe
 * -Dsurefire.failIfNoSpecifiedTests=false -Ddocker.client.strategy=...}。
 * 结果同时打印与写入 {@code aiwarden-start/target/m4-load-report.txt}。
 *
 * <p><b>测什么</b>：
 * <ol>
 *   <li>治理税逐环节（组件级微计时，µs 精度）：可见集 / 检索下推 / 计量事件 / 配额（Redis Lua）/
 *       审计留痕 / OTel span——每项 warmup + 采样，报 P50 / P95 / P99；</li>
 *   <li>端到端吞吐与尾延迟：并发 1/2/4/8/16/32 各跑固定请求数，报 RPS、P50/P95/P99 与错误数；
 *       连接池 / CPU 饱和处即吞吐拐点；</li>
 *   <li>机器规格与 JVM 堆——数字必须带规格，否则不可比（ADR-011 / PRD §12）。</li>
 * </ol>
 *
 * <p><b>口径边界（必须写进报告）</b>：单机 + Testcontainers 的 PostgreSQL/Redis（与生产容器同镜像），
 * 应用与依赖同机竞争 CPU/IO；模型是确定性 Mock（不产生真实推理延迟）。因此数字代表
 * **治理与编排的固定开销与容量边界**，不是线上容量承诺。
 */
@Testcontainers
@Tag("load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "aiwarden.outbox.relay.enabled=false",
        "aiwarden.reconcile.enabled=false",
        "management.tracing.export.otlp.enabled=false"
})
class GovernanceLoadProbe {

    private static final long TENANT = 980L;
    private static final long USER = 98001L;
    private static final String QUERY = "退款政策的说明是什么？";
    private static final int WARMUP = 50;
    private static final int SAMPLES = 200;
    private static final int REQUEST_PER_ROUND = 80;
    private static final int[] CONCURRENCIES = {1, 2, 4, 8, 16, 32};

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmbeddingClient embeddingClient;
    @Autowired
    private VisibilitySetCalculator visibilitySetCalculator;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private OutboxWriter outboxWriter;
    @Autowired
    private QuotaGuard quotaGuard;
    @Autowired
    private BudgetService budgetService;
    @Autowired
    private AuditLogWriter auditLogWriter;
    @Autowired
    private TraceSupport traceSupport;
    @Autowired
    private ChatOrchestrator orchestrator;

    @Test
    void probe() throws Exception {
        seed();
        List<String> lines = new ArrayList<>();
        lines.add("# M4 压测原始输出（GovernanceLoadProbe）");
        lines.add("MACHINE logicalProcessors=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMb=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024))
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " note=physicalSpec i7-7700(4C/8T)/32GB");
        lines.add("ENV postgres=" + postgres.getDockerImageName() + " redis=" + redis.getDockerImageName()
                + " mode=in-process orchestrator, mock model, same-host containers");

        // ---- 治理税逐环节（µs） ----
        long[] visibility = measureWithIndex(i ->
                visibilitySetCalculator.calculate(TENANT, USER, 10L, null));
        lines.add(statsLine("TAX visibility-set-calc", visibility));

        VisibilitySet visible = visibilitySetCalculator.calculate(TENANT, USER, 10L, null);
        long[] retrieval = TenantContext.callWithTenant(String.valueOf(TENANT),
                () -> measureWithIndex(i -> retrievalService.search(visible, QUERY, 10)));
        lines.add(statsLine("TAX filter-pushdown(retrieval)", retrieval));

        String payloadJson = objectMapper.writeValueAsString(
                new CallMeteringPayload("load-probe", null, "load-session", 1, 1, 100, 0, null));
        long[] metering = measureWithIndex(i ->
                outboxWriter.append(TENANT, 980_000L + i, "llm.call.recorded", payloadJson));
        lines.add(statsLine("TAX metering-outbox-append", metering));

        long[] quota = measureWithIndex(i ->
                quotaGuard.reserveForToolInvocation(TENANT, "load-quota-" + i, 100L));
        lines.add(statsLine("TAX quota-reserve(redis-lua)", quota));

        long[] audit = measureWithIndex(i ->
                auditLogWriter.append(TENANT, String.valueOf(USER), "LOAD_PROBE",
                        "load-" + i, "SUCCEEDED", "load-probe detail"));
        lines.add(statsLine("TAX audit-append(REQUIRES_NEW)", audit));

        long[] span = measureWithIndex(i -> {
            Span s = traceSupport.startSpan("load.span");
            s.tag("aiwarden.probe", "1");
            traceSupport.end(s);
        });
        lines.add(statsLine("TAX otel-span-create-end", span));

        // ---- 端到端吞吐 / 尾延迟 ----
        int orderSeq = 100_000;
        // 预热（不计入）：让 JIT / 连接池 / Redis 连接就绪
        for (int i = 0; i < 10; i++) {
            runOne("warm-" + i, "ORDER-" + (orderSeq++));
        }
        for (int concurrency : CONCURRENCIES) {
            Round round = runRound(concurrency, orderSeq);
            orderSeq += REQUEST_PER_ROUND;
            lines.add(roundLine("THROUGHPUT concurrency=" + concurrency, round));
        }

        // ---- 组件级治理总开销（相加口径；工具链路每请求一次） ----
        double totalUs = micros(p50(visibility)) + micros(p50(retrieval)) + micros(p50(metering))
                + micros(p50(quota)) + micros(p50(audit)) + micros(p50(span));
        lines.add("TOTAL governance-components-p50-us=" + fmt(totalUs)
                + " note=visibility+retrieval+metering+quota+audit+span（相加口径；工具链路每请求一次；不含模型/业务逻辑）");

        Path out = Path.of("target", "m4-load-report.txt");
        Files.createDirectories(out.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            lines.forEach(writer::println);
        }
        lines.forEach(System.out::println);
        assertThat(Files.exists(out)).isTrue();
    }

    // ---- 端到端轮次 ----

    private Round runRound(int concurrency, int orderBase) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Long> latenciesNanos = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < REQUEST_PER_ROUND; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    long startNanos = System.nanoTime();
                    String outcome = TenantContext.callWithTenant(String.valueOf(TENANT), () -> {
                        PrincipalContext.setPrincipal(String.valueOf(USER), null);
                        try {
                            OrchestrationResult result = orchestrator.chat(
                                    new ChatAskRequest("帮我建工单 ORDER-" + (orderBase + index)
                                            + "，客户投诉退款慢", "load-" + orderBase + "-" + index, null, null),
                                    ChatStreamSink.NOOP, new java.util.concurrent.atomic.AtomicBoolean(false));
                            return result.outcome();
                        } finally {
                            PrincipalContext.clear();
                        }
                    });
                    if (!"answer".equals(outcome)) {
                        errors.incrementAndGet();
                    }
                    latenciesNanos.add(System.nanoTime() - startNanos);
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            }));
        }
        start.countDown();
        long wallStart = System.nanoTime();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.MINUTES);
        }
        long wallNanos = System.nanoTime() - wallStart;
        pool.shutdownNow();
        return new Round(REQUEST_PER_ROUND, wallNanos, latenciesNanos, errors.get());
    }

    private void runOne(String session, String orderRef) throws Exception {
        TenantContext.callWithTenant(String.valueOf(TENANT), () -> {
            PrincipalContext.setPrincipal(String.valueOf(USER), null);
            try {
                orchestrator.chat(new ChatAskRequest("帮我建工单 " + orderRef + "，客户投诉退款慢",
                        session, null, null), ChatStreamSink.NOOP,
                        new java.util.concurrent.atomic.AtomicBoolean(false));
            } finally {
                PrincipalContext.clear();
            }
            return null;
        });
    }

    // ---- 采样与统计 ----

    private long[] measureWithIndex(IntConsumer action) {
        for (int i = 0; i < WARMUP; i++) {
            action.accept(-1_000_000 - i);
        }
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            action.accept(i);
            samples[i] = System.nanoTime() - start;
        }
        return samples;
    }

    private String statsLine(String stage, long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return stage + " p50-us=" + fmt(micros(percentile(sorted, 0.50)))
                + " p95-us=" + fmt(micros(percentile(sorted, 0.95)))
                + " p99-us=" + fmt(micros(percentile(sorted, 0.99)))
                + " mean-us=" + fmt(meanMicros(sorted))
                + " max-us=" + fmt(micros(sorted[sorted.length - 1]))
                + " n=" + samples.length + " warmup=" + WARMUP;
    }

    private String roundLine(String prefix, Round round) {
        long[] sorted = round.latencies().stream().mapToLong(Long::longValue).sorted().toArray();
        double seconds = round.wallNanos() / 1_000_000_000.0;
        double rps = round.requests() / seconds;
        return prefix
                + " n=" + round.requests()
                + " errors=" + round.errors()
                + " wall-s=" + fmt(seconds)
                + " rps=" + fmt(rps)
                + " p50-ms=" + fmt(percentile(sorted, 0.50) / 1_000_000.0)
                + " p95-ms=" + fmt(percentile(sorted, 0.95) / 1_000_000.0)
                + " p99-ms=" + fmt(percentile(sorted, 0.99) / 1_000_000.0)
                + " max-ms=" + fmt(percentile(sorted, 1.0) / 1_000_000.0);
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static long p50(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return percentile(sorted, 0.50);
    }

    private static double micros(long nanos) {
        return nanos / 1_000.0;
    }

    private static double meanMicros(long[] sorted) {
        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return sum / 1_000.0 / sorted.length;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private void seed() {
        Long kbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (?, 'load-kb') RETURNING id
                """, Long.class, TENANT);
        Long docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, 'load-doc', ?, 1, 'INDEXED') RETURNING id
                """, Long.class, TENANT, kbId, QUERY);
        String metaJson = objectMapper.writeValueAsString(Map.of(
                "tenantId", TENANT, "kbId", kbId, "docId", docId, "version", 1));
        Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                VALUES (?, ?, 1, 0, ?, ?::jsonb) RETURNING id
                """, Long.class, TENANT, docId, QUERY, metaJson);
        jdbcTemplate.update("""
                INSERT INTO t_vector (chunk_id, embedding, meta) VALUES (?, ?::vector, ?::jsonb)
                """, chunkId, PgVectorLiteral.of(embeddingClient.embed(QUERY)), metaJson);
        budgetService.set(TENANT, QuotaService.currentPeriod(), 1_000_000_000L);
    }

    private record Round(int requests, long wallNanos, List<Long> latencies, int errors) {
    }
}
