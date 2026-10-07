package com.aiwarden.contract.agent;

import java.util.Map;

/**
 * 工具调用请求（FR-TOOL-01）。
 *
 * @param businessKey 业务键（如来源单号）——幂等键三要素之一；也是业务表唯一约束的最终防线
 * @param sessionId   会话标识——幂等键三要素之一
 * @param stepNo      会话内步骤号——幂等键三要素之一（与工具名、输入共同构成步骤指纹）
 * @param input       工具输入（JSON 对象；同输入同指纹，重放可复用首次结果）
 */
public record ToolInvokeRequest(String businessKey, String sessionId, Integer stepNo,
                                Map<String, Object> input) {
}
