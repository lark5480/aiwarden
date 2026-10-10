package com.aiwarden.start.governance;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 端管理接口的身份边界（2026-10-10 联调后固化）。
 *
 * <p><b>为什么有这个测试</b>：界面联调实测发现 B 端管理接口口径不一致——
 * {@code /admin/audit} 与 {@code /usage} 只校验租户、**缺主体也返回 200**；
 * 而 {@code /admin/consistency/*} **连租户都没校验**（裸扫全表即返回报告）。
 * 同一套身份头「有的入口必填、有的可选」本身就是缺陷，且**管理面尤其需要主体**
 * （审计留痕的 {@code actor} 取自主体，缺主体会让 actor 落成 null）。
 *
 * <p>本测试对**每个 B 端管理端点**断言：缺租户头 → 400；有租户但缺主体 → 400；
 * 两者齐备 → 非 400。**端点清单是单一事实源**（{@link #ADMIN_READ_ENDPOINTS}）——
 * 新增管理接口时只补这一处即可被三个断言同时覆盖：把口径写进测试，而不是写进约定。
 *
 * <p>用真实 PostgreSQL 容器（这些 controller 都直接注入 {@code JdbcTemplate}），
 * 与其它 {@code *ContainersTest} 同一分层约定；**类内显式开启 Flyway**——根 pom 的 surefire 全局把
 * {@code spring.flyway.enabled} 关掉了（普通 {@code @SpringBootTest} 不依赖外部服务），
 * 不显式开启就只有空库：缺表会让「身份齐备」的断言拿到 500 而不是真实业务态，
 * 测试照样「绿」，但它什么都没证明（本项目已多次栽在「验证本身也要自证」上）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class AdminIdentityBoundaryContainersTest {

    /** B 端管理只读端点（单一事实源：三个断言共用；新增管理端点时只补这一处）。 */
    private static final String[] ADMIN_READ_ENDPOINTS = {
            "/api/v1/admin/audit?limit=1",
            "/api/v1/admin/usage?limit=1",
            "/api/v1/admin/consistency/report",
            "/api/v1/admin/eval/report",
            "/api/v1/admin/billing/reconcile",
            "/api/v1/admin/budgets/1",
    };

    static Stream<String> adminReadEndpoints() {
        return Stream.of(ADMIN_READ_ENDPOINTS);
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /**
     * 配额账本 / 对账的存储侧。
     *
     * <p>为什么这个测试要连 Redis：端点清单里包含 {@code /admin/billing/reconcile}，
     * 它要对 Redis 配额账与明细表做对账——**没有 Redis 会以 500 失败**，
     * 而「身份齐备」那条断言恰恰要求非 5xx。首版只起了 PostgreSQL，于是该断言把
     * 「环境缺件」误报成产品问题（断言本身没冤枉它：500 确实不该出现）。
     * 镜像 tag 与 docker-compose 一致；接法与其它容器测试同构。
     */
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String tenantHeader, String userHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getProperty("local.server.port") + path));
        if (tenantHeader != null) {
            builder.header(TenantContext.TENANT_ID_HEADER, tenantHeader);
        }
        if (userHeader != null) {
            builder.header(PrincipalContext.USER_ID_HEADER, userHeader);
        }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest(name = "缺租户头 → 400：{0}")
    @MethodSource("adminReadEndpoints")
    void adminEndpointWithoutTenantHeader_isRejectedWith400(String path) throws Exception {
        HttpResponse<String> response = get(path, null, "1");
        assertThat(response.statusCode())
                .as("B 端管理端点缺租户头必须 400（不回落默认租户，FR-TEN-02）：%s", path)
                .isEqualTo(400);
    }

    @ParameterizedTest(name = "缺主体头 → 400：{0}")
    @MethodSource("adminReadEndpoints")
    void adminEndpointWithoutPrincipalHeader_isRejectedWith400(String path) throws Exception {
        HttpResponse<String> response = get(path, "1", null);
        assertThat(response.statusCode())
                .as("B 端管理端点缺主体头必须 400（管理面必须知道操作者，审计 actor 取自主体）：%s", path)
                .isEqualTo(400);
        assertThat(response.body())
                .as("错误体应说明是主体缺失，而非其它 400 原因")
                .contains("主体上下文缺失");
    }

    @Test
    void adminEndpointsWithFullIdentity_areNotRejectedWith400() throws Exception {
        for (String path : ADMIN_READ_ENDPOINTS) {
            HttpResponse<String> response = get(path, "1", "1");
            assertThat(response.statusCode())
                    .as("身份齐备时不应因身份被拒（业务态 200/404 均可）：%s", path)
                    .isNotEqualTo(400);
            // ⚠️ 必须同时排除 5xx：只断言「不是 400」时，缺表 / SQL 错误会以 500 通过断言，
            // 测试照样绿却什么都没证明（本测试首版就踩了这个坑——未开 Flyway，库是空的）。
            assertThat(response.statusCode())
                    .as("身份齐备时不应出现 5xx（否则是 SQL / 装配问题，而非身份边界）：%s — body=%s",
                            path, response.body())
                    .isLessThan(500);
        }
    }
}
