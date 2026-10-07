package com.aiwarden.contract.agent;

/**
 * 补偿执行结果（ADR-009 决策 4）。
 *
 * @param sessionId 会话标识
 * @param executed  本次执行的动作数（逆序）
 * @param succeeded 补偿**确实产生效果**的数量（受影响行数 &gt; 0）
 * @param noOp      补偿执行了但无效果的数量（受影响 0 行 / 目标已在终态）——
 *                  与 succeeded 分开计数：混在一起会让「补偿成功率」失去意义
 * @param failed    补偿失败数（失败不阻塞其它动作，可在后续 run 中重跑，见 attempt 上限）
 * @param exhausted 超过重跑上限、仍处 FAILED 的计划数（真正需要人工介入的那部分）
 */
public record CompensationRunResponse(String sessionId, int executed, int succeeded, int noOp,
                                      int failed, int exhausted) {
}
