package com.aiwarden.start.governance;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.event.DocumentEventTypes;
import com.aiwarden.governance.outbox.OutboxRelay;
import com.aiwarden.governance.outbox.OutboxWriter;
import com.aiwarden.knowledge.ingest.DocumentEventPayload;
import com.aiwarden.knowledge.ingest.DocumentIngestHandler;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 切片②端到端验证：<b>Outbox → Kafka → 幂等消费 → ledger 终态</b> 闭环。
 *
 * <p>覆盖：① 事件经 outbox 同表写入 → 发布器发 Kafka（消息头带租户）→ 消费者恢复租户 →
 * 幂等抢占 → 处理一次 → ledger INDEXED；② 重复投递被唯一键仲裁跳过（M1 验收②的先行验证，
 * 向量落库与「向量数 == 切片数」详见切片③）。
 *
 * <p>确定性编排：发布器 @Scheduled 全局关闭（surefire 属性），测试直接调 {@code pollOnce()}；
 * 消费完成的信号用消费者组 committed offset（单分区 topic，offset 累计）。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class OutboxIngestLoopContainersTest {

    private static final String GROUP_ID = "aiwarden-ingest";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CountingIngestHandler countingHandler;

    @Test
    void documentEvent_flowsFromOutboxThroughKafkaToIdempotentLedger() throws Exception {
        long tenantId = 42L;
        long docId = 1001L;
        long baseline = committedOffset(DocumentEventTypes.INDEX_REQUESTED);

        outboxWriter.append(tenantId, docId, DocumentEventTypes.INDEX_REQUESTED,
                "{\"docId\":1001,\"version\":1}");
        assertThat(outboxRelay.pollOnce()).isEqualTo(1);

        awaitCommittedOffset(DocumentEventTypes.INDEX_REQUESTED, baseline + 1);

        assertThat(countingHandler.calls(docId)).isEqualTo(1);
        assertThat(countingHandler.tenantSeen(docId)).isEqualTo("42");

        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT status, tenant_id FROM t_ingest_ledger WHERE doc_id = ? AND version = 1", docId);
        assertThat(ledger.get("status")).isEqualTo("INDEXED");
        assertThat(((Number) ledger.get("tenant_id")).longValue()).isEqualTo(42L);

        Integer sent = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_outbox_event WHERE aggregate_id = ? AND status = 'SENT'",
                Integer.class, docId);
        assertThat(sent).isEqualTo(1);
    }

    @Test
    void duplicateDelivery_isSkippedByIdempotencyGuard() throws Exception {
        long tenantId = 42L;
        long docId = 1002L;
        String payload = "{\"docId\":1002,\"version\":1}";
        long baseline = committedOffset(DocumentEventTypes.INDEX_REQUESTED);

        outboxWriter.append(tenantId, docId, DocumentEventTypes.INDEX_REQUESTED, payload);
        outboxRelay.pollOnce();
        awaitCommittedOffset(DocumentEventTypes.INDEX_REQUESTED, baseline + 1);
        assertThat(countingHandler.calls(docId)).isEqualTo(1);

        // 同一 (docId, version) 的重复事件再次投递：消费端凭唯一键仲裁跳过，处理次数不变
        outboxWriter.append(tenantId, docId, DocumentEventTypes.INDEX_REQUESTED, payload);
        outboxRelay.pollOnce();
        awaitCommittedOffset(DocumentEventTypes.INDEX_REQUESTED, baseline + 2);

        assertThat(countingHandler.calls(docId)).isEqualTo(1);
    }

    private long committedOffset(String topic) throws Exception {
        try (AdminClient admin = adminClient()) {
            return offsetFor(admin, topic);
        }
    }

    private void awaitCommittedOffset(String topic, long atLeast) throws Exception {
        try (AdminClient admin = adminClient()) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (offsetFor(admin, topic) >= atLeast) {
                    return;
                }
                Thread.sleep(200);
            }
            throw new AssertionError("消费组 " + GROUP_ID + " 未在 30s 内在 " + topic
                    + " 上提交到 offset " + atLeast);
        }
    }

    private long offsetFor(AdminClient admin, String topic) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        return offsets.entrySet().stream()
                .filter(entry -> entry.getKey().topic().equals(topic))
                .mapToLong(entry -> entry.getValue().offset())
                .sum();
    }

    private AdminClient adminClient() {
        return AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    @TestConfiguration
    static class CountingIngestConfig {

        @Bean
        @Primary
        CountingIngestHandler countingIngestHandler() {
            return new CountingIngestHandler();
        }

        @Bean
        NewTopic indexTopic() {
            return new NewTopic(DocumentEventTypes.INDEX_REQUESTED, 1, (short) 1);
        }

        @Bean
        NewTopic deleteTopic() {
            return new NewTopic(DocumentEventTypes.DELETE_REQUESTED, 1, (short) 1);
        }
    }

    /** 计数处理器：记录每文档的处理次数与处理时看到的租户（替代占位实现，验证「只处理一次 + 租户正确」）。 */
    static class CountingIngestHandler implements DocumentIngestHandler {

        private final Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<Long, String> tenantIds = new ConcurrentHashMap<>();

        @Override
        public void handle(String eventType, DocumentEventPayload payload) {
            calls.computeIfAbsent(payload.docId(), key -> new AtomicInteger()).incrementAndGet();
            tenantIds.put(payload.docId(), TenantContext.requireTenantId());
        }

        int calls(long docId) {
            AtomicInteger counter = calls.get(docId);
            return counter == null ? 0 : counter.get();
        }

        String tenantSeen(long docId) {
            return tenantIds.get(docId);
        }
    }
}
