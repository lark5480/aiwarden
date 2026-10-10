package com.aiwarden.agent.mcp;

import com.aiwarden.agent.invocation.ToolInvocationService;
import com.aiwarden.agent.tools.ToolCatalog;
import com.aiwarden.agent.tools.ToolNameNormalizer;
import com.aiwarden.agent.tools.ToolRegistry;
import com.aiwarden.agent.tools.ToolWhitelist;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 桥接（FR-TOOL-05）：把白名单内工具转成 MCP tool specifications——
 * **工具名装配期归一化**（非法字符→下划线 + 撞名哈希后缀）后暴露；
 * 执行经 {@link ToolInvocationService} **复用 P3 管道**（幂等键 / 审计 / 配额 / 限流）。
 *
 * <p><b>MCP 侧上下文约定</b>（tools/call 的 arguments）：除工具原生字段外，
 * 携带 {@code businessKey}（必填，调用幂等键的业务部分——重放必须一致）与
 * {@code sessionId}（可选，默认 "mcp"）；租户与主体来自 HTTP 请求头（同一身份纪律）。
 */
@Component
public class ToolMcpBridge {

    /** 身份键（transport context；由 McpExposureConfiguration 的 contextExtractor 在请求线程写入）。 */
    public static final String IDENTITY_TENANT = "tenantId";
    public static final String IDENTITY_USER = "userId";
    public static final String IDENTITY_ORG = "orgId";

    private final ToolRegistry toolRegistry;
    private final ToolWhitelist toolWhitelist;
    private final ToolInvocationService toolInvocationService;
    private final ObjectMapper objectMapper;

    public ToolMcpBridge(ToolRegistry toolRegistry,
                         ToolWhitelist toolWhitelist,
                         ToolInvocationService toolInvocationService,
                         ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolWhitelist = toolWhitelist;
        this.toolInvocationService = toolInvocationService;
        this.objectMapper = objectMapper;
    }

    /** 可见工具的 MCP 规格（归一化名暴露；执行以原始名路由）。 */
    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        List<String> visible = toolRegistry.registeredNames().stream()
                .filter(toolWhitelist::visible)
                .toList();
        Map<String, String> exposedNames = ToolNameNormalizer.normalizeAll(visible);

        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (String rawName : visible) {
            String exposed = exposedNames.get(rawName);
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(exposed)
                    .description(ToolCatalog.description(rawName))
                    .inputSchema(ToolCatalog.mcpSchema(rawName))
                    .annotations(McpSchema.ToolAnnotations.builder()
                            .title(rawName)
                            .idempotentHint(true)
                            .build())
                    .build();
            specs.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> handle(exchange, rawName, request))
                    .build());
        }
        return specs;
    }

    /**
     * tools/call → P3 管道：返回结果的 JSON（含 invocationId / status / replayed）作为文本内容。
     *
     * <p><b>线程边界</b>：handler 执行线程与 Servlet 请求线程不同（SDK 内部调度），ThreadLocal
     * 租户/主体不可用——从 {@code exchange.transportContext()} 恢复（ADR-003「显式 capture/apply」），
     * 缺失即拒绝（不回落默认租户）。
     */
    private McpSchema.CallToolResult handle(McpSyncServerExchange exchange, String rawToolName,
                                            McpSchema.CallToolRequest request) {
        McpTransportContext transportContext = exchange.transportContext();
        String tenantId = asText(transportContext.get(IDENTITY_TENANT));
        String userId = asText(transportContext.get(IDENTITY_USER));
        if (tenantId == null || userId == null) {
            return errorResult("MCP 请求缺少身份头（" + TenantContext.TENANT_ID_HEADER + " / "
                    + PrincipalContext.USER_ID_HEADER + "）：拒绝执行（FR-TEN-02，不回落默认租户）");
        }
        try {
            ToolInvokeResponse response = TenantContext.callWithTenant(tenantId, () -> {
                PrincipalContext.setPrincipal(userId, asText(transportContext.get(IDENTITY_ORG)));
                try {
                    Map<String, Object> arguments = request.arguments() == null
                            ? new HashMap<>() : new HashMap<>(request.arguments());
                    Object businessKey = arguments.remove("businessKey");
                    Object sessionId = arguments.remove("sessionId");
                    if (businessKey == null || String.valueOf(businessKey).isBlank()) {
                        throw new IllegalArgumentException("缺少 businessKey（调用幂等键，重放必须一致）");
                    }
                    return toolInvocationService.invoke(rawToolName,
                            new ToolInvokeRequest(String.valueOf(businessKey),
                                    sessionId == null ? "mcp" : String.valueOf(sessionId),
                                    1, arguments));
                } finally {
                    PrincipalContext.clear();
                }
            });
            boolean failed = "FAILED".equals(response.status());
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(response))))
                    .isError(failed)
                    .build();
        } catch (Exception e) {
            // 治理拒绝（不可见 / 配额 / 限流）与业务失败统一为 MCP 工具错误结果（不泄露堆栈）
            return errorResult("调用被拒绝或失败：" + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(message)))
                .isError(true)
                .build();
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
