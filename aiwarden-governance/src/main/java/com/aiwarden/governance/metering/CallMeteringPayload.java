package com.aiwarden.governance.metering;

/**
 * 计量明细载荷（P4a 四维归因的最小粒度：一次模型 / 工具调用一条）。
 *
 * <p>四维 = tenantId（消息头跨进程传递） / sessionId / stepNo / tool；
 * 摄入链路的嵌入调用不属于会话，session/step 为 null（属正常而非缺失）。
 *
 * <p>{@code dedupKey} 是消费端幂等键：模型调用为 {@code model:<runId>:<stepNo>}，
 * 工具调用为 {@code tool:<invocationId>}；嵌入计量暂为 null（由摄入 ledger 幂等保护）。
 * 它同时覆盖 outbox 至少一次投递与 P5 检查点重做两种重复来源。
 */
public record CallMeteringPayload(String tool, String model, String sessionId, Integer stepNo,
                                  Integer latencyMs, int promptTokens, int completionTokens,
                                  String dedupKey) {
}
