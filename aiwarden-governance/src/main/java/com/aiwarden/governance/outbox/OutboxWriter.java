package com.aiwarden.governance.outbox;

import com.aiwarden.governance.tracing.TraceSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 事务性发件箱写入（FR-KB-02/03）：把事件追加进 {@code t_outbox_event}。
 *
 * <p><b>必须在业务写入的同一事务内调用</b>——「业务表 + outbox 同事务」是本项目一致性管道的
 * 起点；发布由 {@link OutboxRelay} 异步完成（至少一次），消费端以幂等键仲裁（At-least-once + 幂等）。
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private final TraceSupport traceSupport;

    public OutboxWriter(JdbcTemplate jdbcTemplate, TraceSupport traceSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.traceSupport = traceSupport;
    }

    /**
     * 追加一条待发布事件。
     *
     * @param tenantId    租户（t_tenant.id；异步路径经消息头传递）
     * @param aggregateId 聚合 id（如 docId），同时作为 Kafka 消息 key（同聚合有序）
     * @param type        事件类型（即 Kafka topic 名，见 DocumentEventTypes）
     * @param payloadJson 事件载荷（JSON 字符串，落 JSONB）
     */
    public void append(long tenantId, long aggregateId, String type, String payloadJson) {
        // traceparent 在**写入时**捕获：发送发生在定时任务里，那时当前线程已没有请求上下文（FR-OBS-02）。
        String traceParent = traceSupport.currentTraceParent();
        jdbcTemplate.update("""
                INSERT INTO t_outbox_event
                    (tenant_id, aggregate_id, type, payload, status, retry_count, trace_parent)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', 0, ?)
                """, tenantId, aggregateId, type, payloadJson, traceParent);
    }
}
