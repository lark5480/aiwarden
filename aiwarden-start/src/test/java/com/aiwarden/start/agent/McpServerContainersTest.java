package com.aiwarden.start.agent;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 切片④ MCP 最小版验证（FR-TOOL-05）：官方 SDK 客户端 ↔ Streamable HTTP Servlet transport 全链路——
 * ① tools/list 暴露白名单内工具（归一化名）；② tools/call 复用 P3 管道（幂等键）；
 * ③ 相同 businessKey 重放不产生第二张工单（replayed=true）。
 *
 * <p>身份纪律：MCP 请求经同一 Servlet 容器，租户/主体过滤器照常生效——客户端在
 * {@code httpRequestCustomizer} 中携带租户与主体头（缺头即拒绝，不回落默认租户）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class McpServerContainersTest {

    private static final String TENANT = "980";
    private static final String USER = "98001";

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
    private Environment environment;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        String baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .httpRequestCustomizer((requestBuilder, method, uri, body, context) -> requestBuilder
                        .header(TenantContext.TENANT_ID_HEADER, TENANT)
                        .header(PrincipalContext.USER_ID_HEADER, USER))
                .build();
        client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("aiwarden-m2-mcp-test", "0.1.0"))
                .build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void mcpRoundTrip_exposesWhitelistedToolsAndReusesIdempotencyPipeline() {
        // ① tools/list：白名单内工具（归一化名；未授权工具不暴露）
        McpSchema.ListToolsResult tools = client.listTools();
        assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("create_ticket", "assign_ticket");
        assertThat(tools.tools()).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
        });

        // ② tools/call：复用 P3 管道创建工单
        McpSchema.CallToolResult created = client.callTool(new McpSchema.CallToolRequest(
                "create_ticket", Map.of(
                        "orderRef", "MCP-1",
                        "title", "MCP 通道工单",
                        "businessKey", "MCP-ORDER-1",
                        "sessionId", "mcp-sess")));
        String createdText = ((McpSchema.TextContent) created.content().get(0)).text();
        assertThat(created.isError()).as("MCP tools/call 结果：%s", createdText).isNotEqualTo(Boolean.TRUE);
        assertThat(createdText).contains("SUCCEEDED").contains("\"replayed\":false");
        assertThat(ticketCount("MCP-1")).isEqualTo(1);

        // ③ 「重放」（相同 businessKey + arguments）→ 复用首见结果，不建第二张单
        McpSchema.CallToolResult replayed = client.callTool(new McpSchema.CallToolRequest(
                "create_ticket", Map.of(
                        "orderRef", "MCP-1",
                        "title", "MCP 通道工单",
                        "businessKey", "MCP-ORDER-1",
                        "sessionId", "mcp-sess")));
        assertThat(replayed.isError()).isNotEqualTo(Boolean.TRUE);
        String replayedText = ((McpSchema.TextContent) replayed.content().get(0)).text();
        assertThat(replayedText).contains("\"replayed\":true");
        assertThat(ticketCount("MCP-1")).isEqualTo(1);
    }

    private int ticketCount(String orderRef) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?
                """, Integer.class, Long.parseLong(TENANT), orderRef);
        return count == null ? 0 : count;
    }
}
