package com.aiwarden.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条样本的门禁结论（FR-EVAL-03）。
 *
 * @param passed         是否全部硬断言通过
 * @param failures       失败明细（空 = 通过）
 * @param excessTickets  超出 max_tickets 的额外工单数（重复建单的口径；合计进报告）
 */
public record EvalCaseResult(String id, String category, boolean passed, String expectOutcome,
                             String actualOutcome, List<String> failures,
                             long latencyMs, BigDecimal cost, int excessTickets) {
}
