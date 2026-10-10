package com.aiwarden.core.spi;

import java.util.Map;

/**
 * 模型决策的工具调用（参数为业务字段 JSON 对象；工具名可指向可见面外的工具——
 * 治理管道在调用入口拦截并留痕，这是「治理对任意模型决策的约束执行」的验证点之一，ADR-012）。
 */
public record ModelToolCall(String toolName, Map<String, Object> arguments) {
}
