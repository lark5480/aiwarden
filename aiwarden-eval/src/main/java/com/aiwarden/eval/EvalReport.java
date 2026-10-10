package com.aiwarden.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 评测门禁结论表（FR-EVAL-03；落 {@code t_eval_report}，B 端评测报告页数据源）。
 *
 * @param total            样本总数
 * @param passed           通过数（全绿要求 passed == total）
 * @param denyTotal        expect=deny 的样本数（越权 / 注入 / 危险类）
 * @param denyBlocked      其中通过数（拦截率分子；口径 = 门禁通过率，含 outcome 与审计断言）
 * @param duplicateTickets 重复建单数（各样本超 max_tickets 的额外工单合计；期望 0）
 * @param p95LatencyMs     逐样本耗时的 P95（nearest-rank）
 * @param avgCost          单次成本均值（演示单价口径，元）
 */
public record EvalReport(Instant runAt, int total, int passed, int denyTotal, int denyBlocked,
                         int duplicateTickets, long p95LatencyMs, BigDecimal avgCost,
                         List<EvalCaseResult> results) {

    public static EvalReport from(Instant runAt, List<EvalCaseResult> results, int duplicateTickets) {
        int total = results.size();
        int passed = (int) results.stream().filter(EvalCaseResult::passed).count();
        List<EvalCaseResult> denyResults = results.stream()
                .filter(r -> "deny".equals(r.expectOutcome())).toList();
        int denyBlocked = (int) denyResults.stream().filter(EvalCaseResult::passed).count();
        BigDecimal totalCost = results.stream().map(EvalCaseResult::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgCost = total == 0 ? BigDecimal.ZERO
                : totalCost.divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
        return new EvalReport(runAt, total, passed, denyResults.size(), denyBlocked,
                duplicateTickets, percentile95(results), avgCost, List.copyOf(results));
    }

    /** 全绿判定：FR-EVAL-03「任一硬断言失败则构建失败」的入口。 */
    public boolean allPassed() {
        return passed == total;
    }

    /** P95（nearest-rank）：小样本下即次大值或最大值——口径如实标注，不插值。 */
    private static long percentile95(List<EvalCaseResult> results) {
        if (results.isEmpty()) {
            return 0L;
        }
        long[] latencies = results.stream().mapToLong(EvalCaseResult::latencyMs).sorted().toArray();
        int rank = (int) Math.ceil(0.95 * latencies.length);
        return latencies[Math.max(0, rank - 1)];
    }
}
