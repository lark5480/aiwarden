package com.aiwarden.agent.checkpoint;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 演示单价口径的成本计算（元；真实价目表接入后替换，ADR-012「代价与放弃」）。
 *
 * <p>编排层与 deny 路径共用同一实现——成本是「一次调用的属性」，不因结局不同换算法。
 */
public final class ChatCost {

    private ChatCost() {
    }

    public static BigDecimal compute(int promptTokens, int completionTokens,
                                     BigDecimal promptCostPer1k, BigDecimal completionCostPer1k) {
        return BigDecimal.valueOf(promptTokens).multiply(promptCostPer1k)
                .add(BigDecimal.valueOf(completionTokens).multiply(completionCostPer1k))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }
}
