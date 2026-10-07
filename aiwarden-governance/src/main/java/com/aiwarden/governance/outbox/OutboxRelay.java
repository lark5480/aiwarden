package com.aiwarden.governance.outbox;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.governance.messaging.KafkaHeadersCarrier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 事务性发件箱发布器（ADR-005）：
 * 轮询 {@code t_outbox_event} 的 PENDING 行 → 逐条发 Kafka（消息头带租户，M0 载体接口正式接入）
 * → 发送成功后 CAS 标记 SENT；失败累计 retry_count（达上限降为 FAILED 终态）。
 *
 * <p>语义要点：
 * <ul>
 *   <li><b>至少一次发送</b>：发送成功与标记 SENT 之间崩溃会重发——正确性由消费端幂等仲裁兜底
 *       （At-least-once + 唯一键幂等，而不是「恰好一次」的幻想）；</li>
 *   <li><b>定时任务边界</b>（FR-TEN-02）：按行恢复租户上下文（{@code runWithTenant}），
 *       让消息头带上租户，消费端经 {@code runWithCarrier} 还原；</li>
 *   <li><b>事件类型即 topic 名</b>（ADR-005 约定，见 DocumentEventTypes）。</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean pollEnabled;

    public OutboxRelay(JdbcTemplate jdbcTemplate,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${aiwarden.outbox.relay.enabled:true}") boolean pollEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.pollEnabled = pollEnabled;
    }

    /** 定时兜底入口；测试环境全局关闭（surefire 属性），由测试直接调 {@link #pollOnce()} 保证确定性。 */
    @Scheduled(fixedDelayString = "${aiwarden.outbox.relay.poll-interval-ms:2000}")
    public void scheduledPoll() {
        if (!pollEnabled) {
            return;
        }
        try {
            int sent = pollOnce();
            if (sent > 0) {
                log.debug("outbox relay 发布 {} 条事件", sent);
            }
        } catch (Exception e) {
            log.warn("outbox relay 轮询失败（下轮重试）", e);
        }
    }

    /** 单轮发布：返回成功发送条数（测试与对账可复用）。 */
    public int pollOnce() {
        List<OutboxEventRow> events = jdbcTemplate.query("""
                SELECT id, tenant_id, aggregate_id, type, payload
                FROM t_outbox_event
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT ?
                """, (rs, rowNum) -> new OutboxEventRow(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("aggregate_id"),
                rs.getString("type"),
                rs.getString("payload")), BATCH_SIZE);

        int sent = 0;
        for (OutboxEventRow event : events) {
            if (publish(event)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean publish(OutboxEventRow event) {
        try {
            TenantContext.runWithTenant(String.valueOf(event.tenantId()), () -> send(event));
            int updated = jdbcTemplate.update("""
                    UPDATE t_outbox_event SET status = 'SENT', sent_at = now()
                    WHERE id = ? AND status = 'PENDING'
                    """, event.id());
            return updated == 1;
        } catch (Exception e) {
            log.warn("outbox 事件 {} 发布失败：{}", event.id(), e.getMessage());
            jdbcTemplate.update("""
                    UPDATE t_outbox_event
                    SET retry_count = retry_count + 1,
                        status = CASE WHEN retry_count + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END
                    WHERE id = ?
                    """, MAX_RETRY, event.id());
            return false;
        }
    }

    private void send(OutboxEventRow event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.type(), String.valueOf(event.aggregateId()), event.payload());
        TenantContext.writeToCarrier(new KafkaHeadersCarrier(record.headers()));
        try {
            // 同步等待 broker 确认：发送成功才允许标记 SENT（吞吐优化留压测阶段，演示规模无需）
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发送被中断：" + event.id(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Kafka 发送失败：" + event.id(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Kafka 发送超时：" + event.id(), e);
        }
    }

    /** outbox 行（内部读取模型）。 */
    record OutboxEventRow(long id, long tenantId, long aggregateId, String type, String payload) {
    }
}
