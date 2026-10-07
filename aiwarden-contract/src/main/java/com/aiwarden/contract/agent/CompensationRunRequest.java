package com.aiwarden.contract.agent;

/**
 * 补偿执行请求（ADR-009 决策 4）：对指定会话的 PENDING 补偿计划按逆序执行。
 */
public record CompensationRunRequest(String sessionId) {
}
