package com.aiwarden.start.observability;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路 Trace 验收（FR-OBS-01/04）：一次真实 HTTP 问答 → 按 traceId 还原完整 span 树。
 *
 * <p>断言的是「可以还原」而不是「有日志」：
 * <ul>
 *   <li>HTTP 响应头 {@code X-Trace-Id} 与落库的 {@code t_agent_session.trace_id} 一致；</li>
 *   <li>同一 traceId 下包含请求、运行、10 个 DAG 节点、模型子 span、工具子 span；</li>
 *   <li>父子关系成立（HTTP → agent.run → node → model/tool），关键业务 ID 进 span 属性。</li>
 * </ul>
 *
 * <p><b>不依赖外部 collector</b>：用 OTel SDK 的内存 exporter 收集 span（生产链路仍走 OTLP → Tempo，
 * 配置在 application.yml）；测试只验证 instrumentation 与上下文传播，不验证 Tempo 的存储。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "management.tracing.export.otlp.enabled=false"
        })
class AgentTraceContainersTest {

    private static final String TENANT = "720";
    private static final String USER = "72001";
    private static final String SESSION = "trace-chain";
    private static final String REFUND_DOC =
            "退款政策的说明：退款将在七个工作日内原路退回，如遇争议请提交人工审核，由工单系统跟进。";

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

    @TestConfiguration
    static class SpanExportConfiguration {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private InMemorySpanExporter spanExporter;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private Environment environment;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();
    private String baseUrl;

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        if (SEEDED.compareAndSet(false, true)) {
            long kbId = jdbcTemplate.queryForObject("""
                    INSERT INTO t_knowledge_base (tenant_id, org_id, name)
                    VALUES (?, NULL, 'trace-kb') RETURNING id
                    """, Long.class, Long.parseLong(TENANT));
            seedIndexedDocument(Long.parseLong(TENANT), kbId, "trace-doc", REFUND_DOC);
        }
    }

    @Test
    void oneChat_isRestorableByTraceId_withFullSpanTree() throws Exception {
        HttpResponse<String> response = postChat("帮我建工单 ORDER-7701，客户投诉退款慢");
        assertThat(response.statusCode()).isEqualTo(200);
        String traceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        assertThat(traceId).as("响应头必须带 traceId（FR-OBS-04）").isNotBlank();

        // BatchSpanProcessor 异步导出：先 flush 再按 traceId 取
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        List<SpanData> spans = spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();

        assertThat(spans).as("同一 traceId 下应能还原完整调用链").isNotEmpty();
        assertThat(spans).extracting(SpanData::getName)
                .as("请求 / 运行 / 10 个 DAG 节点 / 模型 / 工具 span 齐全")
                .contains("http.chat", "agent.run",
                        "agent.node.init", "agent.node.visibility", "agent.node.retrieval",
                        "agent.node.prompt", "agent.node.model", "agent.node.metering",
                        "agent.node.tool", "agent.node.cost", "agent.node.outcome",
                        "agent.node.finalize",
                        "model.chat", "tool.invoke.create_ticket");

        SpanData http = span("http.chat", spans);
        SpanData run = span("agent.run", spans);
        SpanData visibility = span("agent.node.visibility", spans);
        SpanData modelNode = span("agent.node.model", spans);
        SpanData model = span("model.chat", spans);
        SpanData tool = span("tool.invoke.create_ticket", spans);

        assertThat(run.getParentSpanId()).as("运行 span 是请求 span 的子节点").isEqualTo(http.getSpanId());
        assertThat(visibility.getParentSpanId()).isEqualTo(run.getSpanId());
        assertThat(model.getParentSpanId()).as("模型调用是模型节点的子 span").isEqualTo(modelNode.getSpanId());
        assertThat(tool.getParentSpanId()).isEqualTo(span("agent.node.tool", spans).getSpanId());

        assertThat(attribute(visibility, "aiwarden.step_no")).isEqualTo("2");
        assertThat(attribute(visibility, "aiwarden.visibility.kb_count")).isEqualTo("1");
        assertThat(attribute(span("agent.node.retrieval", spans), "aiwarden.retrieval.hits"))
                .as("检索 span 带命中数（按 traceId 定位答错时的第一手证据）")
                .isEqualTo("1");
        assertThat(attribute(modelNode, "aiwarden.session_id")).isEqualTo(SESSION);
        assertThat(attribute(tool, "aiwarden.tool")).isEqualTo("create_ticket");
        assertThat(attribute(tool, "aiwarden.tenant_id")).isEqualTo(TENANT);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT trace_id FROM t_agent_session
                WHERE tenant_id = ? AND session_key = ? ORDER BY id DESC LIMIT 1
                """, String.class, Long.parseLong(TENANT), SESSION))
                .as("落库的 traceId 必须与响应头一致（Metric / Trace / Log 可互跳）")
                .isEqualTo(traceId);
    }

    private SpanData span(String name, List<SpanData> spans) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少 span：" + name
                        + "，实际=" + spans.stream().map(SpanData::getName).toList()));
    }

    private String attribute(SpanData span, String key) {
        return span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(key));
    }

    private HttpResponse<String> postChat(String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/chat"))
                .header(TenantContext.TENANT_ID_HEADER, TENANT)
                .header(PrincipalContext.USER_ID_HEADER, USER)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(
                                new ChatAskRequest(message, SESSION, null, null)),
                        StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void seedIndexedDocument(long tenant, long kb, String name, String content) {
        Long doc = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, ?, ?, 1, 'INDEXED') RETURNING id
                """, Long.class, tenant, kb, name, content);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenantId", tenant);
        meta.put("kbId", kb);
        meta.put("docId", doc);
        meta.put("version", 1);
        String metaJson = objectMapper.writeValueAsString(meta);
        Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                VALUES (?, ?, 1, 0, ?, ?::jsonb) RETURNING id
                """, Long.class, tenant, doc, content, metaJson);
        jdbcTemplate.update("""
                INSERT INTO t_vector (chunk_id, embedding, meta) VALUES (?, ?::vector, ?::jsonb)
                """, chunkId, PgVectorLiteral.of(embeddingClient.embed(content)), metaJson);
    }
}
