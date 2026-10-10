package com.aiwarden.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次样本运行（一条消息）的结果快照（门禁驱动从编排结果适配；eval 模块不依赖编排类型）。
 *
 * @param outcome         实际结局（answer / deny / human_handoff / aborted）
 * @param reason          结局原因（deny / human_handoff 时非空）
 * @param citationDocIds  引用来源的 docId 列表（真实下推检索的命中）
 * @param anyToolReplayed 本次运行中是否有工具命中幂等重放（replayed=true）
 * @param executedTools   本次运行触达调用入口的工具名（含被拦截的决策）
 * @param latencyMs       端到端耗时（毫秒）
 * @param cost            估算成本（演示单价，元）
 */
public record RunOutcome(String outcome, String reason, List<Long> citationDocIds,
                         boolean anyToolReplayed, List<String> executedTools,
                         long latencyMs, BigDecimal cost) {
}
