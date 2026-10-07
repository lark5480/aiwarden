package com.aiwarden.contract.governance;

/**
 * 账单对账响应（FR-COST-05 / ADR-010 决策 5）：
 * Redis 配额账 vs 明细表 SUM(tokens) 的差异清单与差异率。
 *
 * @param period       账期（YYYY-MM）
 * @param budgetLimit  预算上限（未配置时为 null）
 * @param redisUsage   Redis 侧用量（配额账）
 * @param ledgerTokens 明细侧用量（t_llm_call_log 汇总）
 * @param mismatch     差异（redisUsage − ledgerTokens；正 = 预扣多于明细）
 * @param mismatchRate 差异率（|mismatch| / ledger 汇总；ledger 为 0 且差异非 0 时记 1.0）
 * @param reportId     落库的对账报告 id（t_reconcile_report type=BILLING）
 */
public record BillingReconcileResponse(String period, Long budgetLimit, long redisUsage,
                                       long ledgerTokens, long mismatch, double mismatchRate,
                                       long reportId) {
}
