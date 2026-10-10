package com.aiwarden.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具规格单一来源（描述 + 业务字段 schema）：MCP 暴露（外部调用者视角）与
 * 模型请求体（模型视角）共用，避免两处描述漂移。
 *
 * <p><b>两个视角的差异</b>：
 * <ul>
 *   <li>MCP schema（{@link #mcpSchema}）含 {@code businessKey}（必填）/ {@code sessionId}（可选）——
 *       MCP 调用者是幂等键的提供方；</li>
 *   <li>模型 schema（{@link #businessSchema}）只含业务字段——幂等键由编排层计算，
 *       模型不该看到、也不该填（ADR-012 决策 2）。</li>
 * </ul>
 */
public final class ToolCatalog {

    private ToolCatalog() {
    }

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "create_ticket", "创建工单（演示替身：零资金风险）。相同业务键的重放不会创建第二张工单。",
            "assign_ticket", "指派工单给受理组；assignee=unavailable 时模拟外部协作系统故障（用于补偿链路演示）。");

    private static final Map<String, Map<String, Object>> BUSINESS_SCHEMAS = Map.of(
            "create_ticket", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "orderRef", Map.of("type", "string", "description", "来源单号（业务唯一）"),
                            "title", Map.of("type", "string", "description", "工单标题")),
                    "required", List.of("orderRef", "title")),
            "assign_ticket", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "orderRef", Map.of("type", "string", "description", "来源单号"),
                            "assignee", Map.of("type", "string", "description", "受理组（unavailable = 模拟外部故障）")),
                    "required", List.of("orderRef", "assignee")));

    /** 工具描述（未登记的工具回退为工具名）。 */
    public static String description(String toolName) {
        return DESCRIPTIONS.getOrDefault(toolName, toolName);
    }

    /** 模型视角的业务字段 schema（不含幂等键字段）。 */
    public static Map<String, Object> businessSchema(String toolName) {
        Map<String, Object> schema = BUSINESS_SCHEMAS.get(toolName);
        if (schema == null) {
            return Map.of("type", "object");
        }
        return deepCopy(schema);
    }

    /** MCP 视角的 schema = 业务字段 + businessKey（必填）+ sessionId（可选）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mcpSchema(String toolName) {
        Map<String, Object> base = businessSchema(toolName);
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) base.get("properties"));
        properties.put("businessKey", Map.of("type", "string", "description", "调用幂等键（重放必须一致）"));
        properties.put("sessionId", Map.of("type", "string", "description", "会话标识（可选，默认 mcp）"));
        List<String> required = new java.util.ArrayList<>((List<String>) base.get("required"));
        required.add("businessKey");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", base.getOrDefault("type", "object"));
        schema.put("properties", properties);
        schema.put("required", List.copyOf(required));
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>();
        schema.forEach((key, value) -> copy.put(key,
                value instanceof Map<?, ?> map ? deepCopy((Map<String, Object>) map) : value));
        return copy;
    }
}
