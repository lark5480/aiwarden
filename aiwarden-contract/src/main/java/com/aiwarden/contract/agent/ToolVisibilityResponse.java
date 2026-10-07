package com.aiwarden.contract.agent;

import java.util.List;

/**
 * 工具可见面（FR-PERM-03 的读侧）：只返回装配期白名单内的工具——
 * 「未在白名单内的工具不进模型请求体」在 API 层面即不可见。
 */
public record ToolVisibilityResponse(List<String> tools) {
}
