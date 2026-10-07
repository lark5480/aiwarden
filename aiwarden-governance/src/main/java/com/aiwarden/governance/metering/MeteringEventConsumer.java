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
 * 重复产生计量）；计量消息自身重复投递的去重随 M2 配额对账机制细化。
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
        TenantContext.runWithCarrier(new KafkaHeadersCarrier(record.headers()), () -> {
            CallMeteringPayload payload = objectMapper.readValue(record.value(), CallMeteringPayload.class);
            long tenantId = TenantContext.requireTenantIdAsLong();
            jdbcTemplate.update("""
                    INSERT INTO t_llm_call_log
                      (tenant_id, tool, model, prompt_tokens, completion_tokens, latency_ms)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, tenantId, payload.tool(), payload.model(),
                    payload.promptTokens(), payload.completionTokens(), payload.latencyMs());
        });
    }
}
