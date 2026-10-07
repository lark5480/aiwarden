package com.aiwarden.start.governance;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.governance.metering.CallMeteringPayload;
import com.aiwarden.governance.outbox.OutboxWriter;
import com.aiwarden.governance.quota.BudgetService;
import com.aiwarden.governance.quota.QuotaGuard;
import com.aiwarden.governance.quota.QuotaService;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.governance.visibility.VisibilitySetCalculator;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Arrays;
import java.util.Map;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 验收 ⑥：四项治理税开销输出（ADR-011 度量口径）——可见集计算 / filter 下推 / 计量事件 / 配额检查。
 *
 * <p><b>口径</b>（组件级微计时：warmup 50 + 采样 200，报告 P50 / P95 / max）：
 * ① 可见集计算 = {@code VisibilitySetCalculator.calculate} 全程；② filter 下推 =
 * {@code RetrievalService.search} 全程（不含①）；③ 计量事件 = {@code OutboxWriter.append}
 * （写 outbox 行；Kafka 发送在 Relay 异步、不计入——如实标注）；④ 配额检查 =
 * {@code QuotaGuard.reserveForToolInvocation}（预算点查 + Redis Lua）。
 *
 * <p><b>数据规模如实标注</b>：微基准用小数据（点查/小 SQL）——数字代表治理环节的**固定开销**，
 * 不代表大表下的绝对耗时（后者属 M4 压测报告）。判定用宽松上界（防灾难性退化），
 * 不做硬性能门禁（CI 机器差异大）。
 *
 * <p><b>不用 PER_CLASS</b>：{@code @TestInstance(PER_CLASS)} 会让实例化（及上下文加载）发生在
 * Testcontainers 启动容器之前，{@code @DynamicPropertySource} 求值时报「Mapped port … not started」
 * ——seed 直接放在测试方法内即可。
 */
@Testcontainers
@SpringBootTest(properties = "spring.flyway.enabled=true")
class GovernanceTaxContainersTest {

    private static final long TENANT = 970L;
    private static final long USER = 9701L;
    private static final String QUERY = "治理税基准查询";
    private static final long WARMUP = 50;
    private static final int SAMPLES = 200;
    /** 宽松上界（ADR-011）：只防灾难性退化，不做性能门禁。 */
    private static final double P95_UPPER_BOUND_MS = 50.0;

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

    @BeforeEach
    void seed() {
        long kbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (?, 'tax-bench-kb') RETURNING id
                """, Long.class, TENANT);
        long docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, 'tax-bench-doc', 'seed', 1, 'INDEXED') RETURNING id
                """, Long.class, TENANT, kbId);
        String metaJson = objectMapper.writeValueAsString(Map.of(
                "tenantId", TENANT, "kbId", kbId, "docId", docId, "version", 1));
        Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                VALUES (?, ?, 1, 0, ?, ?::jsonb) RETURNING id
                """, Long.class, TENANT, docId, QUERY, metaJson);
        jdbcTemplate.update("""
                INSERT INTO t_vector (chunk_id, embedding, meta) VALUES (?, ?::vector, ?::jsonb)
                """, chunkId, PgVectorLiteral.of(embeddingClient.embed(QUERY)), metaJson);

        // 配额检查走完整链路（预算启用：预算点查 + Redis Lua）
        budgetService.set(TENANT, QuotaService.currentPeriod(), 10_000_000L);
    }

    @Test
    void governanceTax_fourStages_reportWithMachineSpec() {
        System.out.println("GOVERNANCE-TAX-SPEC: logicalProcessors="
                + Runtime.getRuntime().availableProcessors()
                + " jvmMaxHeapMb=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024))
                + " note=physicalSpec i7-7700(4C/8T)/32GB per ADR-011");

        // ① 可见集计算
        long[] visibilitySet = measureWithIndex(i ->
                visibilitySetCalculator.calculate(TENANT, USER, 10L, null));
        reportAndAssert("visibility-set-calc", visibilitySet);

        // ② filter 下推（检索全程：构建下推参数 + SQL 执行；不含 ①）
        // 检索入口与 TenantContext 交叉校验（ADR-008 运行时保证）：上下文在**计时区间之外**
        // 一次性设置——若放进 measureWithIndex 的闭包里，每样本都会把 set/restore 计入被测开销。
        VisibilitySet visible = visibilitySetCalculator.calculate(TENANT, USER, 10L, null);
        TenantContext.runWithTenant(String.valueOf(TENANT), () -> {
            long[] pushdown = measureWithIndex(i -> retrievalService.search(visible, QUERY, 10));
            reportAndAssert("filter-pushdown", pushdown);
        });

        // ③ 计量事件（写 outbox 行；Kafka 发送在 Relay 异步、不计入本环节）
        String payloadJson = objectMapper.writeValueAsString(
                new CallMeteringPayload("tax-probe", null, "tax-bench", 1, 1, 100, 0));
        long[] metering = measureWithIndex(i ->
                outboxWriter.append(TENANT, 970_000L + i, "llm.call.recorded", payloadJson));
        reportAndAssert("metering-event-append", metering);

        // ④ 配额检查（预算点查 + Redis Lua 预扣减；每次唯一 requestKey）
        long[] quota = measureWithIndex(i ->
                quotaGuard.reserveForToolInvocation(TENANT, "tax-probe-" + i, 100L));
        reportAndAssert("quota-reserve", quota);
    }

    // ---- helpers ----

    private long[] measureWithIndex(IntConsumer action) {
        for (int i = 0; i < WARMUP; i++) {
            action.accept((int) -i);  // warmup 也带 index（唯一键类环节需要）
        }
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            action.accept(i);
            samples[i] = System.nanoTime() - start;
        }
        return samples;
    }

    private void reportAndAssert(String stage, long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long p50 = sorted[(int) (sorted.length * 0.50)];
        long p95 = sorted[(int) (sorted.length * 0.95)];
        long max = sorted[sorted.length - 1];
        System.out.println("GOVERNANCE-TAX: " + stage
                + " p50=" + ms(p50) + "ms p95=" + ms(p95) + "ms max=" + ms(max)
                + "ms (n=" + samples.length + ", warmup=" + WARMUP + ")");
        assertThat(p95 / 1_000_000.0)
                .as("环节[%s] P95 不应超过宽松上界（防灾难性退化，非性能门禁）", stage)
                .isLessThan(P95_UPPER_BOUND_MS);
    }

    private static String ms(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
