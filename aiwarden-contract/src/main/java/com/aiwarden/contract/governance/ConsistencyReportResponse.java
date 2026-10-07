package com.aiwarden.contract.governance;

/**
 * 一致性对账报告（FR-ING-06）：最近一次扫描的结论与不一致明细。
 *
 * @param reportId      报告 id（t_reconcile_report）
 * @param mismatchCount 不一致数 = 有残留的已删除文档数 + 超时未收敛的账本数
 * @param detailsJson   明细 JSON（残留文档列表 / 超时未收敛明细）
 * @param createdAt     扫描时间
 */
public record ConsistencyReportResponse(long reportId, int mismatchCount,
                                        String detailsJson, String createdAt) {
}
