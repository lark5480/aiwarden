package com.aiwarden.governance.metering;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.governance.messaging.KafkaHeadersCarrier;
import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * 计量事件消费者（P4a）：与文档事件共用 Outbox → Kafka → 消费骨架（PRD §8 M1），
 * 落库 {@code t_llm_call_log}（单表明细 + 租户 / 时间索引）。
 *
 * <p>M4 补齐两项（STATUS §3「未做」）：
 * <ul>
 *   <li>{@code cost} 回填：按与编排层同一套演示单价从 tokens 计算（口径一致，不是第二套计价）；</li>
 *   <li>{@code trace_id}：取消费 span 的 traceId，用量明细可按一次调用的 trace 聚合（FR-OBS-04）。</li>
 * </ul>
 *
 * <p>幂等说明：计量载荷带 {@code dedupKey}（模型 {@code model:<runId>:<stepNo>}、工具
 * {@code tool:<invocationId>}），落库时以 {@code t_llm_call_log.dedup_key} 唯一索引 +
 * {@code ON CONFLICT DO NOTHING} 仲裁——同时覆盖 outbox 至少一次投递与 P5 检查点重做。
 * 嵌入计量暂不带键（摄入侧已由 ledger 幂等保护）。
 */
@Component
public class MeteringEventConsumer {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TraceSupport traceSupport;
    private final BigDecimal promptCostPer1k;
    private final BigDecimal completionCostPer1k;

    public MeteringEventConsumer(ObjectMapper objectMapper,
                                 JdbcTemplate jdbcTemplate,
                                 TraceSupport traceSupport,
                                 @Value("${aiwarden.chat.cost.prompt-per-1k:0.002}") double promptCostPer1k,
                                 @Value("${aiwarden.chat.cost.completion-per-1k:0.006}") double completionCostPer1k) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.traceSupport = traceSupport;
        this.promptCostPer1k = BigDecimal.valueOf(promptCostPer1k);
        this.completionCostPer1k = BigDecimal.valueOf(completionCostPer1k);
    }

    @KafkaListener(topics = MeteringEventTypes.CALL_RECORDED, groupId = "aiwarden-metering")
    public void onCallRecorded(ConsumerRecord<String, String> record) {
        // Kafka 消费边界（FR-OBS-02）：消费 span 以生产端 traceparent 为父，调用链跨 broker 连续。
        Span span = traceSupport.startSpan("kafka.consume." + record.topic(), traceParentOf(record));
        span.tag("messaging.system", "kafka");
        span.tag("messaging.destination.name", record.topic());
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(span)) {
            TenantContext.runWithCarrier(new KafkaHeadersCarrier(record.headers()),
                    () -> consume(record.value()));
        } finally {
            traceSupport.end(span);
        }
    }

    /** 消费体（租户取当前上下文）：从载荷落库明细——测试可直接调用以验证消费逻辑（无需 broker）。 */
    public void consume(String payloadJson) {
        CallMeteringPayload payload = objectMapper.readValue(payloadJson, CallMeteringPayload.class);
        long tenantId = TenantContext.requireTenantIdAsLong();
        jdbcTemplate.update("""
                INSERT INTO t_llm_call_log
                  (tenant_id, session_id, step_no, tool, model, prompt_tokens, completion_tokens,
                   latency_ms, cost, trace_id, dedup_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (dedup_key) DO NOTHING
                """, tenantId, payload.sessionId(), payload.stepNo(), payload.tool(), payload.model(),
                payload.promptTokens(), payload.completionTokens(), payload.latencyMs(),
                cost(payload.promptTokens(), payload.completionTokens()), traceSupport.currentTraceId(),
                payload.dedupKey());
    }

    /** 与编排层同一演示单价（元；真实价目表接入后两处一起换）。 */
    private BigDecimal cost(int promptTokens, int completionTokens) {
        return BigDecimal.valueOf(promptTokens).multiply(promptCostPer1k)
                .add(BigDecimal.valueOf(completionTokens).multiply(completionCostPer1k))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private static String traceParentOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(TraceSupport.TRACEPARENT);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
