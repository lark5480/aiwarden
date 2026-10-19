package com.aiwarden.contract.agent;

import java.util.List;

/**
 * Agent 运行视图（M4 / FR-CKPT-02；B 端与排障用）。
 *
 * <p>它把「一次调用走到哪一步、每步是否已提交检查点、traceId 是什么」暴露为可查询状态——
 * 进程崩溃后不必靠日志猜进度。步骤行只含快照元信息，不返回 input/output 全文（可能很大）。
 *
 * @param runId      运行主键（同一 sessionKey 可多次运行，以它区分）
 * @param sessionId  外部会话标识
 * @param status     RUNNING / SUCCEEDED / DENIED / FAILED / ABORTED
 * @param lastStepNo 最近完成的步骤号（0 = 仅有初始行）
 * @param version    版本 CAS 的当前值（每次完成步骤 +1）
 * @param traceId    本次运行的 traceId（可按它在 Tempo 还原调用链）
 * @param outcome    结局（终态时非空）
 * @param reason     结局原因
 * @param steps      已提交的步骤（按 stepNo 升序）
 */
public record AgentRunView(long runId, String sessionId, String status, int lastStepNo, int version,
                           String traceId, String outcome, String reason, List<StepView> steps) {

    /** 步骤快照元信息（input/output 不在此返回）。 */
    public record StepView(int stepNo, String name, String status, int version,
                           long elapsedMs, String detail, String traceId) {
    }
}
