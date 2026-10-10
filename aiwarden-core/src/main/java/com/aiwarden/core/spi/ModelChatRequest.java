package com.aiwarden.core.spi;

import java.util.List;

/**
 * 对话请求（编排层的「Prompt 组装」步骤产物；ADR-012 决策 1）。
 *
 * @param systemPrompt 系统提示（含检索上下文片段与行为约束）
 * @param userMessage  用户问题
 * @param tools        可见面工具规格（FR-PERM-03：白名单过滤后；幂等键字段不出现在规格中——由治理层计算）
 */
public record ModelChatRequest(String systemPrompt, String userMessage, List<ModelToolSpec> tools) {
}
