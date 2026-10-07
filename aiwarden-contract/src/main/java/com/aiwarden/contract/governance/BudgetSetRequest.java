package com.aiwarden.contract.governance;

/**
 * 设置租户预算（P4a / ADR-010 决策 7）。
 *
 * @param period     账期（YYYY-MM；空 = 当前账期）
 * @param tokenLimit token 当量上限（演示期为「操作当量」口径，ADR-010 决策 8）
 */
public record BudgetSetRequest(String period, Long tokenLimit) {
}
