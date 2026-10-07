package com.aiwarden.contract.governance;

/**
 * 租户预算响应（P4a）。
 *
 * @param tenantId    租户
 * @param period      账期（YYYY-MM）
 * @param tokenLimit  token 当量上限（未配置时为 null）
 * @param configured  是否已配置（未配置 = 不启用配额检查，ADR-010 决策 7）
 */
public record BudgetResponse(long tenantId, String period, Long tokenLimit, boolean configured) {
}
