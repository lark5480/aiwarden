package com.aiwarden.contract.agent;

import java.util.Map;

/**
 * 工具调用响应（ADR-009 决策 2 的重放仲裁结果在此可辨识）。
 *
 * @param invocationId 调用记录 id
 * @param tool         工具名
 * @param status       PROCESSING / SUCCEEDED / FAILED
 * @param result       结果（SUCCEEDED 时；重放返回首次结果）
 * @param error        首见失败原因（FAILED 时；重放返回首次失败记录）
 * @param idemKey      幂等键（业务键 + 会话 + 步骤指纹）
 * @param replayed     true = 本次为重放，未重复执行（复用首见终态）
 */
public record ToolInvokeResponse(long invocationId, String tool, String status,
                                 Map<String, Object> result, String error,
                                 String idemKey, boolean replayed) {
}
