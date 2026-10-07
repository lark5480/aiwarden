package com.aiwarden.start.agent;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolVisibilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * M2 切片② 工具类越权样本（FR-PERM-03 / FR-PERM-04，ADR-009 验证表第 6 行）：
 * 4 条样本入越权门禁（与切片①检索类 16 条合计 20 条），断言拦截率 100%。
 *
 * <p>样本：① 显式禁名单 {@code web_search}；② 显式禁名单 {@code web_fetch}；
 * ③ 未注册工具；④ 已注册但不在白名单（{@code export_data}）。全部 403 + 审计
 * {@code TOOL_DENIED}；对外消息不区分「未注册」与「未授权」（不泄露存在性）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class ToolWhitelistContainersTest {

    private static final String TENANT = "950";
    private static final String USER = "9501";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

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

    /** 越权样本 4 条：白名单外工具调用一律 403（含显式禁名单、未注册、注册未授权）。 */
    @ParameterizedTest(name = "越权工具样本：{0}")
    @ValueSource(strings = {"web_search", "web_fetch", "unknown_tool", "export_data"})
    void unauthorizedToolInvocation_isDenied(String tool) throws Exception {
        HttpResponse<String> response = post("/api/v1/agent/tools/" + tool + "/invoke",
                objectMapper.writeValueAsString(new ToolInvokeRequest(
                        "ORDER-9500", "sess-whitelist", 1, Map.of("query", "probe"))));

        assertThat(response.statusCode())
                .as("样本[%s]：工具不可见必须 403", tool)
                .isEqualTo(403);

        // 审计留痕（FR-PERM-05：越权尝试同样留痕）
        Integer audited = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_audit_log
                WHERE tenant_id = ? AND action = 'TOOL_DENIED' AND target = ? AND result = 'DENIED'
                """, Integer.class, Long.parseLong(TENANT), tool);
        assertThat(audited).as("样本[%s]：拒绝必须留痕", tool).isGreaterThanOrEqualTo(1);

        // 被拒调用不得产生任何调用账本记录
        Integer invocations = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_tool_invocation WHERE tenant_id = ? AND tool = ?
                """, Integer.class, Long.parseLong(TENANT), tool);
        assertThat(invocations).isZero();
    }

    /** 可见面基线：列表只含白名单内工具（「未在白名单内的工具不进模型请求体」的读侧）。 */
    @Test
    void visibleTools_containOnlyWhitelisted() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/agent/tools"))
                .header(TenantContext.TENANT_ID_HEADER, TENANT)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);

        ToolVisibilityResponse visibility = objectMapper.readValue(response.body(), ToolVisibilityResponse.class);
        assertThat(visibility.tools()).containsExactly("assign_ticket", "create_ticket");
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
}
