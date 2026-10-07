package com.aiwarden.agent.mcp;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 最小版（FR-TOOL-05 / ADR-011 决策 5）：把内置工具以 **Streamable HTTP** 暴露为 MCP Server。
 *
 * <p><b>形态</b>：官方 Java SDK（`io.modelcontextprotocol.sdk:mcp` 聚合件，2.x 内置
 * {@link HttpServletStreamableServerTransportProvider}——Servlet 形态，挂在本应用的
 * Spring WebMVC 容器上，端点 `/mcp`）。<b>审计不绕开</b>：MCP 经同一 Servlet 容器，
 * 租户/主体过滤器照常生效——MCP 客户端必须携带 `X-Aiwarden-Tenant-Id` / `X-Aiwarden-User-Id`
 * 请求头（与 REST 入口同一身份纪律；缺失即拒绝，不回落默认租户）。
 *
 * <p>工具清单来自 {@link ToolMcpBridge}（白名单内 + 工具名装配期归一化）；
 * 执行复用 P3 管道（幂等键 / 审计 / 配额），MCP 只是暴露 adapter。
 */
@Configuration
public class McpExposureConfiguration {

    /**
     * Streamable HTTP 传输（Servlet；协议端点 /mcp，Servlet 映射见下方 RegistrationBean）。
     *
     * <p><b>身份经 transport context 携带</b>：MCP handler 执行线程与 Servlet 请求线程不同
     * （SDK 内部调度），ThreadLocal 不跨线程——因此用 {@code contextExtractor} 在容器请求线程
     * 把身份头抄进 {@link McpTransportContext}，由 {@link ToolMcpBridge} 在 handler 里恢复
     * （与 ADR-003「显式 capture/apply」同一纪律）。
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .contextExtractor(McpExposureConfiguration::extractIdentity)
                .build();
    }

    private static McpTransportContext extractIdentity(HttpServletRequest request) {
        Map<String, Object> identity = new HashMap<>();
        putIfPresent(identity, ToolMcpBridge.IDENTITY_TENANT, request.getHeader(TenantContext.TENANT_ID_HEADER));
        putIfPresent(identity, ToolMcpBridge.IDENTITY_USER, request.getHeader(PrincipalContext.USER_ID_HEADER));
        putIfPresent(identity, ToolMcpBridge.IDENTITY_ORG, request.getHeader(PrincipalContext.ORG_ID_HEADER));
        return McpTransportContext.create(identity);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/mcp");
    }

    /** MCP Server：暴露白名单内工具（经桥接复用 P3 治理管道）。 */
    @Bean
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transport,
                                       ToolMcpBridge toolMcpBridge) {
        return McpServer.sync(transport)
                .serverInfo("aiwarden", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(toolMcpBridge.toolSpecifications())
                .build();
    }
}
