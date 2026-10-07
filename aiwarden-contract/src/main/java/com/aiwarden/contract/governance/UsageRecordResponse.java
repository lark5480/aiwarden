package com.aiwarden.contract.governance;

import java.time.OffsetDateTime;

/**
 * 用量明细条目（FR-COST-04）：一次模型 / 工具调用的四维归因明细
 * （租户经行级隔离，会话 / 步骤 / 工具在条目内）。
 */
public record UsageRecordResponse(long id, String sessionId, Integer stepNo, String tool, String model,
                                  int promptTokens, int completionTokens, Integer latencyMs,
                                  OffsetDateTime createdAt) {
}
