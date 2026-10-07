package com.aiwarden.governance.metering;

/**
 * 计量明细载荷（P4a 四维归因的最小粒度：一次模型 / 工具调用一条）。
 *
 * <p>tenantId 经消息头传递（TenantContextCarrier）；session / step 维度依赖 OTel 上下文
 * （M2/M4 补），当前摄入场景未产出；重复投递的去重随 M2 配额对账机制细化。
 */
public record CallMeteringPayload(String tool, String model, Integer latencyMs,
                                  int promptTokens, int completionTokens) {
}
