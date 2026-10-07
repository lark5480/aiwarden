package com.aiwarden.governance.metering;

/**
 * 计量明细载荷（P4a 四维归因的最小粒度：一次模型 / 工具调用一条）。
 *
 * <p>四维 = tenantId（消息头跨进程传递） / sessionId / stepNo / tool；
 * 摄入链路的嵌入调用不属于会话，session/step 为 null（属正常而非缺失）。
 */
public record CallMeteringPayload(String tool, String model, String sessionId, Integer stepNo,
                                  Integer latencyMs, int promptTokens, int completionTokens) {
}
