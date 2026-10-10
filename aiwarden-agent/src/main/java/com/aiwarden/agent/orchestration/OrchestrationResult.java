package com.aiwarden.agent.orchestration;

import com.aiwarden.contract.knowledge.RetrievalHit;

import java.math.BigDecimal;
import java.util.List;

/**
 * 编排的结构化结果（评测断言与 C 端「done 汇总」共用；与 SSE 事件流同源，不是两份数据）。
 *
 * @param sessionId        会话标识（请求缺省时由服务端生成）
 * @param outcome          answer / deny / human_handoff（+ aborted：客户端断连的取消路径）
 * @param reason           outcome 的原因（deny / human_handoff 时非空）
 * @param citations        引用（真实下推检索的命中，按相似度排序）
 * @param tools            工具调用结果（经 P3 管道）
 * @param promptTokens     模型调用输入 token（估算口径，ADR-012）
 * @param completionTokens 模型调用输出 token（估算口径）
 * @param cost             估算成本（演示单价，元）
 * @param timeline         步骤时间线（与 step 事件同源）
 */
public record OrchestrationResult(String sessionId, String outcome, String reason,
                                  List<RetrievalHit> citations, List<ToolOutcome> tools,
                                  int promptTokens, int completionTokens, BigDecimal cost,
                                  List<StepRecord> timeline) {

    /** 取消路径的 outcome（客户端已断开，无 SSE outcome 事件；评测不产生该路径）。 */
    public static final String ABORTED = "aborted";

    /** 工具调用结果记录。 */
    public record ToolOutcome(String tool, String status, boolean replayed, Long invocationId, String error) {
    }

    /** 步骤时间线记录（step / elapsedMs / detail，与 {@code ChatEvents.Step} 同源）。 */
    public record StepRecord(String step, long elapsedMs, String detail) {
    }
}
