package com.aiwarden.agent.orchestration;

import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.governance.metering.CallMeteringPayload;
import com.aiwarden.governance.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 编排链路的模型调用计量写入（P4a 四维归因的模型维度；ADR-012 决策 1）。
 *
 * <p>与工具调用的计量（{@code ToolInvocationService.appendMetering}）同构：事件进事务性发件箱，
 * 由既有 Outbox → Kafka → {@code MeteringEventConsumer} 骨架落 {@code t_llm_call_log}——
 * 模型调用没有业务聚合，Kafka key 统一用 0（同分区保序）。
 */
@Component
public class ChatMeteringWriter {

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public ChatMeteringWriter(OutboxWriter outboxWriter, ObjectMapper objectMapper) {
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
    }

    /** 记录一次模型调用（tool=null——模型维度与工具维度在明细表可区分）。 */
    @Transactional
    public void recordModelCall(long tenantId, String sessionId, int stepNo, String model,
                                int promptTokens, int completionTokens, int latencyMs) {
        outboxWriter.append(tenantId, 0, MeteringEventTypes.CALL_RECORDED,
                objectMapper.writeValueAsString(new CallMeteringPayload(
                        null, model, sessionId, stepNo, latencyMs, promptTokens, completionTokens)));
    }
}
