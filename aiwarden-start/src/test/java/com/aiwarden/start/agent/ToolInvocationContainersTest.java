package com.aiwarden.start.agent;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.CompensationRunRequest;
import com.aiwarden.contract.agent.CompensationRunResponse;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
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
 * M2 切片② P3 验证（FR-TOOL-01/02/04，ADR-009 验证表）：工具副作用的幂等、重放仲裁与补偿链路。
 *
 * <p><b>主演示场景</b>（FR-TOOL-04）：模型已决定建单、工具未返回时断开通道 → 重放 →
 * 数据库终态工单数 == 1。本类的模拟方式是「提交后断点」——首次调用**真实执行成功**
 * （工单 + SUCCEEDED 落库），客户端丢弃响应（模拟响应不可达）；重放必须复用首见结果、
 * 不产生第二张工单。这是**能失败的断言**：若幂等仲裁失效，第二次执行会撞
 * {@code t_ticket} 唯一约束或产生第二张（两条防线任一暴露）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class ToolInvocationContainersTest {

    private static final String TENANT = "940";
    private static final String USER = "9401";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** 工具调用入口的限流/配额管道依赖 Redis（ADR-010）：与 docker-compose 锁同一镜像 tag。 */
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

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    /**
     * 主演示（FR-TOOL-04）：断网重放 → 工单数 == 1，且重放返回首次结果。
     */
    @Test
    void disconnectedChannelReplay_createsExactlyOneTicket() throws Exception {
        String businessKey = "ORDER-9001";
        String sessionId = "sess-main";
        Map<String, Object> input = Map.of("orderRef", "MAIN-1", "title", "售后工单（演示替身）");

        // ① 模型已决定建单：首次调用真实执行成功
        ToolInvokeResponse first = invoke("create_ticket", businessKey, sessionId, 1, input);
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.replayed()).isFalse();

        // ② 「通道断开」：客户端未收到响应——丢弃 first，直接重放同一调用
        ToolInvokeResponse replay = invoke("create_ticket", businessKey, sessionId, 1, input);

        // ③ 重放复用首见结果，不重执行
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());
        assertThat(replay.result()).isEqualTo(first.result());

        // ④ 数据库终态：工单 1 张、调用账本 1 行
        assertThat(ticketCount("MAIN-1")).isEqualTo(1);
        assertThat(invocationCount(sessionId)).isEqualTo(1);
    }

    /** 并发重放：16 线程同键并发，只有一个赢家执行（其余复用结果或 409「执行中」）。 */
    @Test
    void concurrentReplay_executesExactlyOnce() throws Exception {
        String businessKey = "ORDER-9002";
        String sessionId = "sess-concurrent";
        Map<String, Object> input = Map.of("orderRef", "CONC-1", "title", "并发重放工单");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return invokeRaw("create_ticket", businessKey, sessionId, 1, input).statusCode();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            // 赢家 200；其余或复用（200）或撞执行中（409）——两种都合法，不得出现其它码
            assertThat(codes).allMatch(code -> code == 200 || code == 409);
            assertThat(codes).contains(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(ticketCount("CONC-1")).isEqualTo(1);
        Integer succeeded = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_tool_invocation
                WHERE tenant_id = ? AND session_id = ? AND status = 'SUCCEEDED'
                """, Integer.class, Long.parseLong(TENANT), sessionId);
        assertThat(succeeded).isEqualTo(1);
    }

    /** 超租约重抢（ADR-009：不确定可重试）：模拟执行者崩溃停在 PROCESSING，重放可接管且不重复建单。 */
    @Test
    void processingLeaseTimeout_reclaimExecutes_andTicketStaysSingle() throws Exception {
        String businessKey = "ORDER-9003";
        String sessionId = "sess-lease";
        Map<String, Object> input = Map.of("orderRef", "LEASE-1", "title", "租约重抢工单");

        ToolInvokeResponse first = invoke("create_ticket", businessKey, sessionId, 1, input);
        assertThat(first.status()).isEqualTo("SUCCEEDED");

        // 模拟「执行者已崩溃」：状态回置 PROCESSING 且租约过期
        jdbcTemplate.update("""
                UPDATE t_tool_invocation SET status = 'PROCESSING', updated_at = now() - interval '10 minutes'
                WHERE id = ?
                """, first.invocationId());

        ToolInvokeResponse reclaimed = invoke("create_ticket", businessKey, sessionId, 1, input);

        assertThat(reclaimed.replayed()).isFalse();
        assertThat(reclaimed.status()).isEqualTo("SUCCEEDED");
        assertThat(reclaimed.invocationId()).isEqualTo(first.invocationId());
        // 重抢后重新执行，但工具自身幂等（唯一约束）→ 工单不重复
        assertThat(ticketCount("LEASE-1")).isEqualTo(1);
        assertThat(invocationCount(sessionId)).isEqualTo(1);
    }

    /** 重放仲裁矩阵：FAILED 重放返回首见失败记录（不重执行）——「补偿而非重试」。 */
    @Test
    void failedReplay_returnsFirstFailureRecord_withoutReExecution() throws Exception {
        String sessionId = "sess-failed";
        ToolInvokeResponse created = invoke("create_ticket", "ORDER-9004", sessionId, 1,
                Map.of("orderRef", "FAIL-1", "title", "失败场景工单"));
        assertThat(created.status()).isEqualTo("SUCCEEDED");

        Map<String, Object> assignInput = Map.of("orderRef", "FAIL-1", "assignee", "unavailable");
        ToolInvokeResponse failed = invoke("assign_ticket", "ORDER-9004", sessionId, 2, assignInput);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("受理组不可用");

        ToolInvokeResponse replay = invoke("assign_ticket", "ORDER-9004", sessionId, 2, assignInput);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("FAILED");
        assertThat(replay.error()).isEqualTo(failed.error());

        // 失败即生成补偿计划：更早的成功建单进入 PENDING（下一步测试验证执行）
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_compensation_log cl
                JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                WHERE cl.tenant_id = ? AND cl.status = 'PENDING' AND ti.session_id = ?
                """, Integer.class, Long.parseLong(TENANT), sessionId);
        assertThat(pending).isEqualTo(1);
    }

    /** 补偿链路（FR-TOOL-02）：两单成功 + 指派失败 → 逆序清算（先关联单、后主单），日志与审计留痕。 */
    @Test
    void compensation_unwindsInReverseOrder_andIsIdempotent() throws Exception {
        String sessionId = "sess-comp";
        ToolInvokeResponse mainTicket = invoke("create_ticket", "ORDER-9005", sessionId, 1,
                Map.of("orderRef", "COMP-MAIN", "title", "主单"));
        ToolInvokeResponse linkedTicket = invoke("create_ticket", "ORDER-9005", sessionId, 2,
                Map.of("orderRef", "COMP-LINK", "title", "关联单"));
        ToolInvokeResponse assigned = invoke("assign_ticket", "ORDER-9005", sessionId, 3,
                Map.of("orderRef", "COMP-MAIN", "assignee", "unavailable"));
        assertThat(assigned.status()).isEqualTo("FAILED");

        Integer pending = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_compensation_log cl
                JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                WHERE cl.tenant_id = ? AND cl.status = 'PENDING' AND ti.session_id = ?
                """, Integer.class, Long.parseLong(TENANT), sessionId);
        assertThat(pending).isEqualTo(2);

        CompensationRunResponse run = runCompensation(sessionId);
        assertThat(run.executed()).isEqualTo(2);
        assertThat(run.succeeded()).isEqualTo(2);
        assertThat(run.failed()).isZero();

        // 两张单都被取消；补偿日志各尝试一次
        assertThat(ticketStatus("COMP-MAIN")).isEqualTo("CANCELLED");
        assertThat(ticketStatus("COMP-LINK")).isEqualTo("CANCELLED");
        Integer attempts = jdbcTemplate.queryForObject("""
                SELECT sum(attempt) FROM t_compensation_log cl
                JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                WHERE cl.tenant_id = ? AND ti.session_id = ?
                """, Integer.class, Long.parseLong(TENANT), sessionId);
        assertThat(attempts).isEqualTo(2);

        // 逆序证据：审计按动作顺序插入——先关联单（更晚的调用）后主单
        List<String> compensatedTargets = jdbcTemplate.queryForList("""
                SELECT target FROM t_audit_log
                WHERE tenant_id = ? AND action = 'TOOL_COMPENSATED' AND result = 'SUCCEEDED'
                ORDER BY id
                """, String.class, Long.parseLong(TENANT));
        assertThat(compensatedTargets).containsExactly(
                String.valueOf(linkedTicket.invocationId()),
                String.valueOf(mainTicket.invocationId()));

        // 复跑幂等：无 PENDING → no-op
        CompensationRunResponse rerun = runCompensation(sessionId);
        assertThat(rerun.executed()).isZero();
    }

    /**
     * <b>补偿「假成功」与「失败死胡同」的回归门禁</b>（A9）。
     *
     * <p>三个断言各自钉住一条容易静默失效的语义：
     * <ol>
     *   <li><b>受影响 0 行 = NO_OP，不是成功</b>：工单已被取消后补偿再执行，{@code UPDATE}
     *       匹配 0 行——若执行器按「没抛异常即成功」记 SUCCEEDED，就会出现
     *       「审计 SUCCEEDED + 计数器 +1，而数据库什么都没变」的假成功；</li>
     *   <li><b>FAILED 可重跑</b>：补偿失败置 FAILED 后，若执行集合只查 PENDING，第二次 run
     *       永远返回 executed=0——会话永久卡在「半补偿」且无任何提示；</li>
     *   <li><b>attempt 上限可观测</b>：超过上限仍 FAILED 的计划计入 {@code exhausted}，
     *       单列给人工介入，而不是继续无声重试。</li>
     * </ol>
     */
    @Test
    void compensation_marksNoOpInsteadOfFakeSuccess_andRetriesFailedPlans() throws Exception {
        String sessionId = "sess-comp-noop";

        ToolInvokeResponse created = invoke("create_ticket", "ORDER-9009", sessionId, 1,
                Map.of("orderRef", "COMP-NOOP", "title", "待补偿单"));
        assertThat(created.status()).isEqualTo("SUCCEEDED");

        // 指派失败 → 建单进入补偿计划
        assertThat(invoke("assign_ticket", "ORDER-9009", sessionId, 2,
                Map.of("orderRef", "COMP-NOOP", "assignee", "unavailable")).status()).isEqualTo("FAILED");

        // 模拟「目标已被外部撤销」：工单先变成 CANCELLED，补偿就没有可撤销的效果
        jdbcTemplate.update("UPDATE t_ticket SET status = 'CANCELLED' WHERE tenant_id = ? AND idem_key = ?",
                Long.parseLong(TENANT), "COMP-NOOP");

        CompensationRunResponse noOpRun = runCompensation(sessionId);
        assertThat(noOpRun.executed()).isEqualTo(1);
        assertThat(noOpRun.succeeded())
                .as("受影响 0 行不得计为补偿成功（假成功门禁）")
                .isZero();
        assertThat(noOpRun.noOp())
                .as("应记为 NO_OP：补偿执行了，但没有产生效果")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT cl.status FROM t_compensation_log cl
                JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                WHERE cl.tenant_id = ? AND ti.session_id = ?
                """, String.class, Long.parseLong(TENANT), sessionId))
                .as("NO_OP 是终态，不得写成 SUCCEEDED")
                .isEqualTo("NO_OP");

        // ---- FAILED 可重跑 ----
        String retrySession = "sess-comp-retry";
        invoke("create_ticket", "ORDER-9010", retrySession, 1,
                Map.of("orderRef", "COMP-RETRY", "title", "重跑单"));
        assertThat(invoke("assign_ticket", "ORDER-9010", retrySession, 2,
                Map.of("orderRef", "COMP-RETRY", "assignee", "unavailable")).status()).isEqualTo("FAILED");

        // 模拟该计划上一次补偿失败（attempt 记 1，未达上限 3）
        jdbcTemplate.update("""
                UPDATE t_compensation_log
                SET status = 'FAILED', attempt = 1, detail = '注入的上一次失败', updated_at = now()
                WHERE id IN (SELECT cl.id FROM t_compensation_log cl
                             JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                             WHERE cl.tenant_id = ? AND ti.session_id = ?)
                """, Long.parseLong(TENANT), retrySession);

        CompensationRunResponse retry = runCompensation(retrySession);
        assertThat(retry.executed())
                .as("FAILED 且未超上限的计划必须可重跑（否则永久卡在「半补偿」）")
                .isEqualTo(1);
        assertThat(retry.succeeded()).isEqualTo(1);
        assertThat(ticketStatus("COMP-RETRY")).isEqualTo("CANCELLED");

        // ---- 超过重跑上限：单列 exhausted，不再无声重试 ----
        jdbcTemplate.update("""
                UPDATE t_compensation_log
                SET status = 'FAILED', attempt = 99, updated_at = now()
                WHERE id IN (SELECT cl.id FROM t_compensation_log cl
                             JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                             WHERE cl.tenant_id = ? AND ti.session_id = ?)
                """, Long.parseLong(TENANT), retrySession);
        CompensationRunResponse exhausted = runCompensation(retrySession);
        assertThat(exhausted.executed()).as("超上限的计划不再执行").isZero();
        assertThat(exhausted.exhausted())
                .as("超上限仍需可见（这些才是真正要人工介入的）")
                .isEqualTo(1);
    }

    /**
     * <b>幂等键越界必须在入口拒绝（400），而不是撞 DB 列宽后 500</b>（FR-TOOL-01，A8 回归门禁）。
     *
     * <p>键 = {@code businessKey + ':' + sessionId + ':' + 16 hex}。入口未校验长度时，
     * 超长业务键会一路走到 {@code INSERT}，由 PostgreSQL 报
     * {@code value too long for type character varying(160)} → 500（且可能外泄原始错误）。
     * 现入口显式校验（越界 400），列宽由 V7 迁移兜底。
     */
    @Test
    void oversizedIdempotencyKeyParts_areRejectedWith400_beforeHittingDb() throws Exception {
        String hugeBusinessKey = "B".repeat(200);
        String hugeSessionId = "S".repeat(200);

        HttpResponse<String> longBusinessKey = invokeRaw("create_ticket", hugeBusinessKey, "sess-len", 1,
                Map.of("orderRef", "LEN-1", "title", "越界业务键"));
        assertThat(longBusinessKey.statusCode())
                .as("超长业务键必须是 400（入口拒绝），不是 500（DB 列宽报错）")
                .isEqualTo(400);

        HttpResponse<String> longSessionId = invokeRaw("create_ticket", "ORDER-9200", hugeSessionId, 1,
                Map.of("orderRef", "LEN-2", "title", "越界会话"));
        assertThat(longSessionId.statusCode()).isEqualTo(400);

        // 越界请求不得留下任何账本行（拒绝发生在 claim 之前）
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_tool_invocation
                WHERE tenant_id = ? AND (idem_key LIKE '%LEN%' OR session_id = ?)
                """, Integer.class, Long.parseLong(TENANT), hugeSessionId);
        assertThat(rows).isZero();
    }

    /**
     * <b>幂等指纹必须保留输入的数字精度</b>（FR-TOOL-01 的正确性前提，A7 回归门禁）。
     *
     * <p>共享 ObjectMapper 若把 JSON 小数反序列化为 {@code Double}，则两个仅在 17 位有效数字
     * 之后不同的输入会得到<b>同一个指纹</b> → 第二次调用静默重放首次结果（返回首次 invocationId、
     * 不执行、无日志）。本用例用**会失败的断言**钉住它：
     * <ul>
     *   <li>同输入重放 → 返回同一 invocationId 且 {@code replayed=true}（幂等语义未被破坏）；</li>
     *   <li>仅末位不同的输入 → 必须产生<b>第二个</b> invocationId（指纹真的区分开了）。</li>
     * </ul>
     * 注：{@code 1.0000000000000000000001} 与 {@code 1.0000000000000000000002} 在 {@code Double}
     * 下都等于 {@code 1.0}（本机 Jackson 3.1.5 实测），因此末位差异只会体现在正确实现里。
     */
    @Test
    void idempotencyFingerprint_preservesNumericPrecision() throws Exception {
        String sessionId = "sess-precision";
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("orderRef", "PRECISION-1");
        input.put("title", "精度探针");
        input.put("amount", new java.math.BigDecimal("1.0000000000000000000001"));

        ToolInvokeResponse first = invoke("create_ticket", "ORDER-9101", sessionId, 1, input);
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.replayed()).isFalse();

        // ① 同输入重放：必须复用首见结果（幂等语义本身不能被精度修复破坏）
        ToolInvokeResponse replay = invoke("create_ticket", "ORDER-9101", sessionId, 1, input);
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());
        assertThat(replay.replayed()).isTrue();

        // ② 仅第 22 位小数不同：必须被识别为不同输入（Double 化时这里会误判为重复）
        Map<String, Object> distinct = new java.util.LinkedHashMap<>(input);
        distinct.put("amount", new java.math.BigDecimal("1.0000000000000000000002"));
        ToolInvokeResponse second = invoke("create_ticket", "ORDER-9101", sessionId, 1, distinct);
        assertThat(second.replayed())
                .as("末位不同的输入被误判为重复 → 静默重放首次结果（数字精度丢失）")
                .isFalse();
        assertThat(second.invocationId())
                .as("不同输入必须产生各自独立的调用记录")
                .isNotEqualTo(first.invocationId());
    }

    // ---- helpers ----

    private ToolInvokeResponse invoke(String tool, String businessKey, String sessionId, int stepNo,
                                      Map<String, Object> input) throws Exception {
        HttpResponse<String> response = invokeRaw(tool, businessKey, sessionId, stepNo, input);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ToolInvokeResponse.class);
    }

    private HttpResponse<String> invokeRaw(String tool, String businessKey, String sessionId, int stepNo,
                                           Map<String, Object> input) throws Exception {
        return post("/api/v1/agent/tools/" + tool + "/invoke",
                objectMapper.writeValueAsString(new ToolInvokeRequest(businessKey, sessionId, stepNo, input)));
    }

    private CompensationRunResponse runCompensation(String sessionId) throws Exception {
        HttpResponse<String> response = post("/api/v1/agent/compensations/run",
                objectMapper.writeValueAsString(new CompensationRunRequest(sessionId)));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), CompensationRunResponse.class);
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(TenantContext.TENANT_ID_HEADER, TENANT)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int ticketCount(String orderRef) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?
                """, Integer.class, Long.parseLong(TENANT), orderRef);
        return count == null ? 0 : count;
    }

    private String ticketStatus(String orderRef) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM t_ticket WHERE tenant_id = ? AND idem_key = ?
                """, String.class, Long.parseLong(TENANT), orderRef);
    }

    private int invocationCount(String sessionId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_tool_invocation WHERE tenant_id = ? AND session_id = ?
                """, Integer.class, Long.parseLong(TENANT), sessionId);
        return count == null ? 0 : count;
    }
}
