package com.aiwarden.start.agent;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 切片②（应做档）二态审批开关验证（FR-TOOL-03，ADR-009 决策的「随后落地」项）。
 *
 * <p>类级配置把 {@code assign_ticket} 设为「需确认」：调用时挂起（PENDING_APPROVAL，
 * 输入快照已落库 = Checkpoint）；重放返回挂起态；**批准**后从快照恢复执行（不丢上下文）、
 * **驳回**落 REJECTED 终态且不触发补偿；对已终态调用的 approve/reject → 409。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "aiwarden.agent.tools.require-approval=assign_ticket"
})
class ToolApprovalContainersTest {

    private static final String TENANT = "960";
    private static final String USER = "9601";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** 工具调用入口的限流/配额管道依赖 Redis（ADR-010）。 */
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

    /** 批准链路：挂起（不执行）→ 重放返回挂起态 → 批准后从输入快照恢复执行。 */
    @Test
    void approvalFlow_suspendsThenExecutesFromSnapshot() throws Exception {
        String sessionId = "sess-approve";
        ToolInvokeResponse created = invoke("create_ticket", "ORDER-9601", sessionId, 1,
                Map.of("orderRef", "APPROVE-1", "title", "待审批工单"));
        assertThat(created.status()).isEqualTo("SUCCEEDED");

        Map<String, Object> assignInput = Map.of("orderRef", "APPROVE-1", "assignee", "group-a");
        ToolInvokeResponse suspended = invoke("assign_ticket", "ORDER-9601", sessionId, 2, assignInput);

        // ① 挂起：未执行（工单仍无受理人）
        assertThat(suspended.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(suspended.replayed()).isFalse();
        assertThat(ticketAssignee("APPROVE-1")).isNull();

        // ② 重放：返回挂起态首见记录，不重复挂起
        ToolInvokeResponse replay = invoke("assign_ticket", "ORDER-9601", sessionId, 2, assignInput);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(replay.invocationId()).isEqualTo(suspended.invocationId());

        // ③ 批准：从已落库的输入快照恢复执行（不丢上下文）
        ToolInvokeResponse approved = postForResponse(
                "/api/v1/agent/tool-invocations/" + suspended.invocationId() + "/approve", null);
        assertThat(approved.status()).isEqualTo("SUCCEEDED");
        assertThat(approved.result()).containsEntry("assignee", "group-a");
        assertThat(ticketAssignee("APPROVE-1")).isEqualTo("group-a");

        // ④ 批准留痕
        Integer audit = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_audit_log
                WHERE tenant_id = ? AND action = 'TOOL_APPROVED' AND target = ?
                """, Integer.class, Long.parseLong(TENANT), String.valueOf(suspended.invocationId()));
        assertThat(audit).isGreaterThanOrEqualTo(1);
    }

    /** 驳回链路：REJECTED 终态、不执行、可重放；对已驳回的调用再 approve → 409。 */
    @Test
    void rejection_isTerminal_andReplayable() throws Exception {
        String sessionId = "sess-reject";
        ToolInvokeResponse created = invoke("create_ticket", "ORDER-9602", sessionId, 1,
                Map.of("orderRef", "REJECT-1", "title", "驳回场景工单"));
        assertThat(created.status()).isEqualTo("SUCCEEDED");

        Map<String, Object> assignInput = Map.of("orderRef", "REJECT-1", "assignee", "group-a");
        ToolInvokeResponse suspended = invoke("assign_ticket", "ORDER-9602", sessionId, 2, assignInput);
        assertThat(suspended.status()).isEqualTo("PENDING_APPROVAL");

        ToolInvokeResponse rejected = postForResponse(
                "/api/v1/agent/tool-invocations/" + suspended.invocationId() + "/reject", null);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(ticketAssignee("REJECT-1")).isNull();

        // 重放：REJECTED 首见终态
        ToolInvokeResponse replay = invoke("assign_ticket", "ORDER-9602", sessionId, 2, assignInput);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("REJECTED");

        // 对已驳回的调用再批准 → 409（CAS 拒绝）
        HttpResponse<String> approveAfterReject = post(
                "/api/v1/agent/tool-invocations/" + suspended.invocationId() + "/approve", null);
        assertThat(approveAfterReject.statusCode()).isEqualTo(409);

        // 驳回留痕
        Integer audit = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_audit_log
                WHERE tenant_id = ? AND action = 'TOOL_REJECTED' AND target = ?
                """, Integer.class, Long.parseLong(TENANT), String.valueOf(suspended.invocationId()));
        assertThat(audit).isGreaterThanOrEqualTo(1);
    }

    // ---- helpers ----

    private ToolInvokeResponse invoke(String tool, String businessKey, String sessionId, int stepNo,
                                      Map<String, Object> input) throws Exception {
        HttpResponse<String> response = post("/api/v1/agent/tools/" + tool + "/invoke",
                objectMapper.writeValueAsString(new ToolInvokeRequest(businessKey, sessionId, stepNo, input)));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ToolInvokeResponse.class);
    }

    private ToolInvokeResponse postForResponse(String path, String jsonBody) throws Exception {
        HttpResponse<String> response = post(path, jsonBody);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ToolInvokeResponse.class);
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(TenantContext.TENANT_ID_HEADER, TENANT)
                .header(PrincipalContext.USER_ID_HEADER, USER);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method("POST", HttpRequest.BodyPublishers.noBody());
        }
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String ticketAssignee(String orderRef) {
        return jdbcTemplate.queryForObject("""
                SELECT assignee FROM t_ticket WHERE tenant_id = ? AND idem_key = ?
                """, String.class, Long.parseLong(TENANT), orderRef);
    }
}
