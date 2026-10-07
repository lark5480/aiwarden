package com.aiwarden.start.agent;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
import com.aiwarden.contract.governance.AuditLogResponse;
import com.aiwarden.contract.governance.BillingReconcileResponse;
import com.aiwarden.contract.governance.BudgetResponse;
import com.aiwarden.contract.governance.BudgetSetRequest;
import com.aiwarden.contract.governance.UsageRecordResponse;
import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.governance.metering.MeteringEventConsumer;
import com.aiwarden.governance.quota.QuotaService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
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
 * M2 切片③ P4a 配额强一致验证（ADR-010 验证表）：预扣减 / 幂等 / 差额结算 / 并发不超卖 /
 * 超限 429（含指标、审计、零残留）/ 对账零差异与缺口发现 / 四维归因落库 / 审计与用量接口。
 *
 * <p>租户隔离约定：本类不同测试方法用不同租户号（990 正常链路 / 991 超限链路），
 * 组件级断言用独立账期（2099-xx）——避免共享容器内的相互污染。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class QuotaEnforcementContainersTest {

    private static final String TENANT_OK = "990";
    private static final String TENANT_OVER = "991";
    private static final String USER = "99001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** 配额账本与对账的存储侧（ADR-010）：与 docker-compose 锁同一镜像 tag。 */
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
    private Environment environment;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private MeteringEventConsumer meteringEventConsumer;

    @Autowired
    private MeterRegistry meterRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    /**
     * 正常链路（TENANT_OK）：预算启用 → 3 次工具调用扣 300 → 明细四维落库 →
     * 对账零差异 → 审计与用量接口可查。
     */
    @Test
    void budgetEnabled_deductsLedgersAndReconciles() throws Exception {
        setBudget(Long.parseLong(TENANT_OK), 1000L);

        String sessionId = "sess-quota-ok";
        for (int step = 1; step <= 3; step++) {
            ToolInvokeResponse response = invoke(TENANT_OK, "create_ticket",
                    new ToolInvokeRequest("ORDER-9900", sessionId, step,
                            Map.of("orderRef", "QUOTA-OK-" + step, "title", "配额演示单" + step)));
            assertThat(response.status()).isEqualTo("SUCCEEDED");
        }

        // ① 配额账：3 × 当量（100）= 300
        String period = QuotaService.currentPeriod();
        assertThat(quotaService.currentUsage(Long.parseLong(TENANT_OK), period)).isEqualTo(300L);

        // ② 四维归因落库：消费 outbox 载荷（真实消费逻辑）→ t_llm_call_log 带 session/step/tool/tokens
        consumeMeteringOutbox(Long.parseLong(TENANT_OK));
        Integer ledgerRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_llm_call_log
                WHERE tenant_id = ? AND session_id = ? AND tool = 'create_ticket'
                  AND prompt_tokens = 100
                """, Integer.class, Long.parseLong(TENANT_OK), sessionId);
        assertThat(ledgerRows).isEqualTo(3);

        // ③ 对账零差异：Redis 账 vs 明细汇总；报告落库
        BillingReconcileResponse reconcile = getForResponse(
                "/api/v1/admin/billing/reconcile", BillingReconcileResponse.class, TENANT_OK);
        assertThat(reconcile.redisUsage()).isEqualTo(300L);
        assertThat(reconcile.ledgerTokens()).isEqualTo(300L);
        assertThat(reconcile.mismatch()).isZero();
        assertThat(reconcile.mismatchRate()).isZero();
        assertThat(reconcile.reportId()).isPositive();

        // ④ 对账缺口发现：以「未被结算的预扣」模拟泄漏 → 差异可见
        quotaService.reserve(Long.parseLong(TENANT_OK), period, "leak-probe", 50, 1_000_000);
        BillingReconcileResponse withGap = getForResponse(
                "/api/v1/admin/billing/reconcile", BillingReconcileResponse.class, TENANT_OK);
        assertThat(withGap.mismatch()).isEqualTo(50L);
        assertThat(withGap.mismatchRate()).isEqualTo(50.0 / 300.0);

        // ⑤ 用量与审计接口（FR-COST-04 / FR-PERM-05）
        List<UsageRecordResponse> usage = getList("/api/v1/admin/usage?tool=create_ticket&sessionId=" + sessionId,
                UsageRecordResponse.class, TENANT_OK);
        assertThat(usage).hasSize(3);
        assertThat(usage).allMatch(record -> record.promptTokens() == 100 && record.stepNo() != null);

        List<AuditLogResponse> audit = getList("/api/v1/admin/audit?action=TOOL_APPROVED",
                AuditLogResponse.class, TENANT_OK);
        assertThat(audit).isEmpty();  // 本租户无批准动作——接口过滤生效（非空断言在超限用例）
    }

    /**
     * 超限链路（TENANT_OVER）：预算 250 → 第 3 次 429 + 指标 + 审计 + 账本零残留；
     * 提额后同键可成功（拒绝不留下不可重试的终态）。
     */
    @Test
    void quotaExceeded_returns429_withAuditAndNoResidue_andRetriesAfterTopUp() throws Exception {
        setBudget(Long.parseLong(TENANT_OVER), 250L);

        String sessionId = "sess-quota-over";
        ToolInvokeResponse first = invoke(TENANT_OVER, "create_ticket",
                new ToolInvokeRequest("ORDER-9910", sessionId, 1,
                        Map.of("orderRef", "QUOTA-OVER-1", "title", "超限演示单1")));
        ToolInvokeResponse second = invoke(TENANT_OVER, "create_ticket",
                new ToolInvokeRequest("ORDER-9910", sessionId, 2,
                        Map.of("orderRef", "QUOTA-OVER-2", "title", "超限演示单2")));
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(second.status()).isEqualTo("SUCCEEDED");

        // 第 3 次：剩余 50 < 当量 100 → 429
        ToolInvokeRequest rejected = new ToolInvokeRequest("ORDER-9910", sessionId, 3,
                Map.of("orderRef", "QUOTA-OVER-3", "title", "超限演示单3"));
        HttpResponse<String> denied = post("/api/v1/agent/tools/create_ticket/invoke", rejected, TENANT_OVER);
        assertThat(denied.statusCode()).isEqualTo(429);

        // 指标 + 审计
        assertThat(meterRegistry.counter("aiwarden_budget_reject_total").count()).isGreaterThanOrEqualTo(1.0);
        List<AuditLogResponse> audit = getList("/api/v1/admin/audit?action=QUOTA_EXCEEDED",
                AuditLogResponse.class, TENANT_OVER);
        assertThat(audit).isNotEmpty();
        assertThat(audit.get(0).result()).isEqualTo("DENIED");

        // 零残留：被拒调用没有账本行（事务回滚）——本租户恰好只有 2 行
        Integer invocationRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_tool_invocation WHERE tenant_id = ?
                """, Integer.class, Long.parseLong(TENANT_OVER));
        assertThat(invocationRows).isEqualTo(2);
        // 工单也只有 2 张（第 3 张未建）
        Integer ticketRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_ticket WHERE tenant_id = ?
                """, Integer.class, Long.parseLong(TENANT_OVER));
        assertThat(ticketRows).isEqualTo(2);

        // 重放同键仍 429（配额未恢复）
        assertThat(post("/api/v1/agent/tools/create_ticket/invoke", rejected, TENANT_OVER).statusCode())
                .isEqualTo(429);

        // 提额 → 同键重试成功（拒绝不留终态，配额恢复即可重试）
        setBudget(Long.parseLong(TENANT_OVER), 1000L);
        ToolInvokeResponse retried = objectMapper.readValue(
                post("/api/v1/agent/tools/create_ticket/invoke", rejected, TENANT_OVER).body(),
                ToolInvokeResponse.class);
        assertThat(retried.status()).isEqualTo("SUCCEEDED");
        assertThat(quotaService.currentUsage(Long.parseLong(TENANT_OVER), QuotaService.currentPeriod()))
                .isEqualTo(300L);
    }

    /** 组件级语义（独立账期 2099-03）：预扣幂等 / 差额结算 / 释放安全 / 并发不超卖。 */
    @Test
    void quotaComponent_idempotencySettleRelease_andConcurrentNoOversell() throws Exception {
        long tenantId = 990_209;  // 专用租户，避免与 HTTP 用例的账期用量混淆
        String period = "2099-03";

        // 预扣：100；同 requestKey 再预扣 → 幂等命中不重复扣
        QuotaService.QuotaOutcome first = quotaService.reserve(tenantId, period, "key-a", 100, 1000);
        assertThat(first.allowed()).isTrue();
        QuotaService.QuotaOutcome again = quotaService.reserve(tenantId, period, "key-a", 100, 1000);
        assertThat(again.idempotentHit()).isTrue();
        assertThat(quotaService.currentUsage(tenantId, period)).isEqualTo(100L);

        // 差额结算：实际 60 → 校正 -40；重复结算为 no-op
        long delta = quotaService.settle(tenantId, period, "key-a", 60);
        assertThat(delta).isEqualTo(-40L);
        assertThat(quotaService.settle(tenantId, period, "key-a", 60)).isZero();
        assertThat(quotaService.currentUsage(tenantId, period)).isEqualTo(60L);

        // 释放：归还未结算的预扣（key-b 预扣 100 → 释放 → 回到 60）；重复释放安全
        quotaService.reserve(tenantId, period, "key-b", 100, 1000);
        quotaService.release(tenantId, period, "key-b");
        quotaService.release(tenantId, period, "key-b");
        assertThat(quotaService.currentUsage(tenantId, period)).isEqualTo(60L);

        // 并发不超卖：limit=1000、每次 100 → 恰好 10 个 allowed（16 线程）
        String windowPeriod = "2099-04";
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String key = "concurrent-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return quotaService.reserve(tenantId, windowPeriod, key, 100, 1000).allowed();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(10L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(quotaService.currentUsage(tenantId, windowPeriod)).isEqualTo(1000L);
    }

    // ---- helpers ----

    /**
     * 跨租户幂等键隔离（ADR-010 决策 1 修订）：DB 唯一约束是 {@code (tenant_id, idem_key)}，
     * 因此两个租户<b>可以</b>持有完全相同的 idemKey。若 Redis 预扣 / 结算键不带租户，
     * 租户 B 会命中租户 A 留下的键 → 幂等分支「允许且不扣减」→ 免费调用 + 对账负差异。
     *
     * <p>本用例是该缺陷的回归门禁：两租户用<b>同一个 requestKey</b> 预扣，必须各自扣减。
     */
    @Test
    void sameRequestKeyAcrossTenants_deductsIndependently() {
        long tenantA = 990_301;
        long tenantB = 990_302;
        String period = "2099-05";
        String sharedKey = "SAME-KEY-ACROSS-TENANTS:session-x:0123456789abcdef";

        QuotaService.QuotaOutcome a = quotaService.reserve(tenantA, period, sharedKey, 100, 1000);
        QuotaService.QuotaOutcome b = quotaService.reserve(tenantB, period, sharedKey, 100, 1000);

        assertThat(a.allowed()).isTrue();
        assertThat(b.allowed())
                .as("租户 B 不得因租户 A 的同名幂等键而被判为「幂等命中」")
                .isTrue();
        assertThat(b.idempotentHit())
                .as("同一 requestKey 在不同租户下必须是两次独立预扣，而不是幂等复用")
                .isFalse();
        assertThat(quotaService.currentUsage(tenantA, period)).isEqualTo(100L);
        assertThat(quotaService.currentUsage(tenantB, period))
                .as("租户 B 必须真的被扣减（修复前这里是 0 = 免费调用）")
                .isEqualTo(100L);

        // 各租户独立结算：互不影响
        assertThat(quotaService.settle(tenantA, period, sharedKey, 100)).isZero();
        assertThat(quotaService.settle(tenantB, period, sharedKey, 100)).isZero();
        assertThat(quotaService.currentUsage(tenantA, period)).isEqualTo(100L);
        assertThat(quotaService.currentUsage(tenantB, period)).isEqualTo(100L);
    }

    /**
     * 释放后重试必须重新计费（ADR-010 决策 2 修订）：{@code release} 归还预扣时必须
     * <b>删除预扣键</b>，否则同一 requestKey 的再次执行会命中残留键走「幂等免扣」，
     * 而随后的 settle 差额为 0（actual == reserved）→ 这次真实执行<b>完全不计费</b>
     * （usage 恒为 0，明细却有行 —— 对账正向差异，且全程无报错）。
     */
    @Test
    void releaseThenReserveThenSettle_chargesExactlyOnce() {
        long tenantId = 990_303;
        String period = "2099-06";
        String key = "RELEASE-THEN-RETRY:session-y:fedcba9876543210";

        quotaService.reserve(tenantId, period, key, 100, 1000);
        assertThat(quotaService.currentUsage(tenantId, period)).isEqualTo(100L);

        // 业务失败 → 释放预扣（归还，且键必须被清掉）
        quotaService.release(tenantId, period, key);
        assertThat(quotaService.currentUsage(tenantId, period)).isZero();

        // 重抢 / 审批恢复：这是**一次真实的再次执行** → 必须重新扣减
        QuotaService.QuotaOutcome reReserve = quotaService.reserve(tenantId, period, key, 100, 1000);
        assertThat(reReserve.allowed()).isTrue();
        assertThat(quotaService.currentUsage(tenantId, period))
                .as("释放之后重试必须重新占用配额（修复前残留键导致免扣，这里恒为 0）")
                .isEqualTo(100L);

        // 结算：actual == 当量 → 差额 0，用量保持 100（一次执行 = 一次计费）
        assertThat(quotaService.settle(tenantId, period, key, 100)).isZero();
        assertThat(quotaService.currentUsage(tenantId, period))
                .as("一次真实执行必须恰好计费一次")
                .isEqualTo(100L);
    }

    /**
     * 预算接口的租户边界（P4a / ADR-010）：路径 {@code tenantId} 与租户上下文是两个独立来源，
     * 不校验就等于把「读任意租户预算」与「改写任意租户预算」暴露出去（改大即解除限流、改小即 DoS）。
     */
    @Test
    void budgetEndpoints_rejectCrossTenantAccess() throws Exception {
        setBudget(Long.parseLong(TENANT_OK), 1000L);

        // 读：租户 990 请求租户 991 的预算 → 403
        assertThat(get("/api/v1/admin/budgets/" + TENANT_OVER, TENANT_OK).statusCode())
                .as("跨租户读预算必须被拒")
                .isEqualTo(403);

        // 写：租户 990 改写租户 991 的预算 → 403
        HttpResponse<String> denied = put("/api/v1/admin/budgets/" + TENANT_OVER,
                objectMapper.writeValueAsString(new BudgetSetRequest(null, 999_999L)), TENANT_OK);
        assertThat(denied.statusCode())
                .as("跨租户改写预算必须被拒（否则可解除他人限流 / 制造 DoS）")
                .isEqualTo(403);
        // 写入确实没生效：991 的预算峰值仍是 990 自己设的值（若是 999999 则说明越权写入成功）
        Long overTenantLimit = jdbcTemplate.queryForObject("""
                SELECT token_limit FROM t_budget WHERE tenant_id = ? AND period = ?
                """, Long.class, Long.parseLong(TENANT_OVER), QuotaService.currentPeriod());
        assertThat(overTenantLimit)
                .as("跨租户写入不得落库")
                .isNotEqualTo(999_999L);

        // 自身租户正常可读可写（排除「一律 403」的假绿）
        assertThat(get("/api/v1/admin/budgets/" + TENANT_OK, TENANT_OK).statusCode()).isEqualTo(200);
    }

    private void setBudget(long tenantId, long tokenLimit) throws Exception {
        HttpResponse<String> response = put("/api/v1/admin/budgets/" + tenantId,
                objectMapper.writeValueAsString(new BudgetSetRequest(null, tokenLimit)),
                String.valueOf(tenantId));
        assertThat(response.statusCode()).isEqualTo(200);
        BudgetResponse body = objectMapper.readValue(response.body(), BudgetResponse.class);
        assertThat(body.configured()).isTrue();
    }

    private ToolInvokeResponse invoke(String tenant, String tool, ToolInvokeRequest request) throws Exception {
        HttpResponse<String> response = post("/api/v1/agent/tools/" + tool + "/invoke", request, tenant);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ToolInvokeResponse.class);
    }

    private <T> T getForResponse(String path, Class<T> type, String tenant) throws Exception {
        HttpResponse<String> response = get(path, tenant);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), type);
    }

    private <T> List<T> getList(String path, Class<T> elementType, String tenant) throws Exception {
        HttpResponse<String> response = get(path, tenant);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    /** 从 outbox 取本租户的计量载荷并经由真实消费逻辑落库（无需 broker）。 */
    private void consumeMeteringOutbox(long tenantId) {
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload::text FROM t_outbox_event
                WHERE tenant_id = ? AND type = ? ORDER BY id
                """, String.class, tenantId, MeteringEventTypes.CALL_RECORDED);
        assertThat(payloads).isNotEmpty();
        TenantContext.runWithTenant(String.valueOf(tenantId),
                () -> payloads.forEach(meteringEventConsumer::consume));
    }

    private HttpResponse<String> get(String path, String tenant) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(TenantContext.TENANT_ID_HEADER, tenant)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, Object body, String tenant) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(TenantContext.TENANT_ID_HEADER, tenant)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> put(String path, String jsonBody, String tenant) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(TenantContext.TENANT_ID_HEADER, tenant)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
