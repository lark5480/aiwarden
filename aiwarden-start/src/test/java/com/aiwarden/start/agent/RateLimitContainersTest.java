package com.aiwarden.start.agent;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 切片③ 租户限流验证（FR-COST-07 / ADR-010 决策 6，验证表第 4 行）：
 * 类级把限额压到 3 次/窗口——前 3 次放行、第 4 次 429 且带 {@code Retry-After}；
 * 限流发生在幂等仲裁之前（超限不产生账本行）。
 *
 * <p>独立类的原因：类级配置（{@code aiwarden.rate-limit.max-requests=3}）会改变所有工具调用
 * 的窗口额度，放在共享类里会污染其他用例——类隔离是干净的边界。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "aiwarden.rate-limit.max-requests=3",
        "aiwarden.rate-limit.window-seconds=60"
})
class RateLimitContainersTest {

    private static final String TENANT = "993";
    private static final String USER = "99301";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** 限流滑动窗口在 Redis（与配额共用管道，ADR-010）。 */
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
    private MeterRegistry meterRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    @Test
    void rateLimit_exceedsAtFourthRequest_withRetryAfter_andNoLedgerResidue() throws Exception {
        // 前 3 次放行（同一「租户+工具」窗口）
        for (int step = 1; step <= 3; step++) {
            HttpResponse<String> allowed = post(invokeBody(step));
            assertThat(allowed.statusCode())
                    .as("第 %d 次请求应放行（限额 3）", step)
                    .isEqualTo(200);
        }

        // 第 4 次：429 + Retry-After
        HttpResponse<String> denied = post(invokeBody(4));
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After"))
                .as("429 必须携带 Retry-After（FR-COST-07）")
                .isPresent();
        assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow()))
                .isPositive();

        // 指标
        assertThat(meterRegistry.counter("aiwarden_rate_limit_reject_total").count())
                .isGreaterThanOrEqualTo(1.0);

        // 限流先于幂等仲裁：被拒请求不产生账本行（恰好 3 行）
        Integer invocations = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_tool_invocation WHERE tenant_id = ?
                """, Integer.class, Long.parseLong(TENANT));
        assertThat(invocations).isEqualTo(3);
    }

    // ---- helpers ----

    private String invokeBody(int step) throws Exception {
        return objectMapper.writeValueAsString(new ToolInvokeRequest(
                "ORDER-9930", "sess-ratelimit", step,
                Map.of("orderRef", "RATE-" + step, "title", "限流演示单" + step)));
    }

    private HttpResponse<String> post(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/agent/tools/create_ticket/invoke"))
                .header(TenantContext.TENANT_ID_HEADER, TENANT)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
