package com.aiwarden.governance.metering;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.governance.messaging.KafkaHeadersCarrier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 计量事件消费者（P4a）：与文档事件共用 Outbox → Kafka → 消费骨架（PRD §8 M1），
 * 落库 {@code t_llm_call_log}（单表明细 + 租户 / 时间索引）。
 *
 * <p>幂等说明：M1 不做计量去重——上游索引处理已被 ledger 幂等保护（重复的索引消息不会
 * 重复产生计量）；工具调用的账本侧已由 t_tool_invocation 唯一键仲裁，重放不会重复上报。
 */
@Component
public class MeteringEventConsumer {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public MeteringEventConsumer(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @KafkaListener(topics = MeteringEventTypes.CALL_RECORDED, groupId = "aiwarden-metering")
    public void onCallRecorded(ConsumerRecord<String, String> record) {
        TenantContext.runWithCarrier(new KafkaHeadersCarrier(record.headers()),
                () -> consume(record.value()));
    }

    /** 消费体（租户取当前上下文）：从载荷落库明细——测试可直接调用以验证消费逻辑（无需 broker）。 */
    public void consume(String payloadJson) {
        CallMeteringPayload payload = objectMapper.readValue(payloadJson, CallMeteringPayload.class);
        long tenantId = TenantContext.requireTenantIdAsLong();
        jdbcTemplate.update("""
                INSERT INTO t_llm_call_log
                  (tenant_id, session_id, step_no, tool, model, prompt_tokens, completion_tokens, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, payload.sessionId(), payload.stepNo(), payload.tool(), payload.model(),
                payload.promptTokens(), payload.completionTokens(), payload.latencyMs());
    }
}
