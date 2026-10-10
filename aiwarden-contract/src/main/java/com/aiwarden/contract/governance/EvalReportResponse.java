package com.aiwarden.contract.governance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评测报告响应（FR-ADM-05：B 端评测报告页数据源；最近一次评测门禁运行的结论表）。
 *
 * @param runAt            运行时间
 * @param total            样本总数（20–30）
 * @param passed           通过数
 * @param denyTotal        expect=deny 样本数（越权 / 注入 / 危险类）
 * @param denyBlocked      其中通过数（拦截率分子，门禁口径）
 * @param duplicateTickets 重复建单数（期望 0）
 * @param p95LatencyMs     逐样本耗时 P95（nearest-rank）
 * @param avgCost          单次成本均值（演示单价口径，元）
 * @param detail           逐样本结论明细（与 {@code t_eval_report.detail} 一致）
 */
public record EvalReportResponse(Instant runAt, int total, int passed, int denyTotal,
                                 int denyBlocked, int duplicateTickets, long p95LatencyMs,
                                 BigDecimal avgCost, List<Map<String, Object>> detail) {
}
