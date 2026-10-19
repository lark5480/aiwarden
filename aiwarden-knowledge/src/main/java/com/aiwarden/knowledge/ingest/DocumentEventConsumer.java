package com.aiwarden.knowledge.ingest;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.event.DocumentEventTypes;
import com.aiwarden.governance.ingest.IngestLedgerGuard;
import com.aiwarden.governance.messaging.KafkaHeadersCarrier;
import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * 文档事件消费者（幂等消费骨架，FR-ING-01）：
 * 消息头恢复租户（M0 载体接口正式接入）→ ledger 唯一键仲裁 → 抢占成功才交给 handler。
 *
 * <p>失败语义：handler 异常 → ledger 置 FAILED → 抛出交给 Kafka 错误处理器
 * （指数退避重试 ≤3 次 → DLT，FR-ING-04）；重试消息凭 FAILED 状态重新抢占（不会被仲裁挡死）。
 */
@Component
public class DocumentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final IngestLedgerGuard ledgerGuard;
    private final DocumentIngestHandler ingestHandler;
    private final TraceSupport traceSupport;

    public DocumentEventConsumer(ObjectMapper objectMapper,
                                 IngestLedgerGuard ledgerGuard,
                                 DocumentIngestHandler ingestHandler,
                                 TraceSupport traceSupport) {
        this.objectMapper = objectMapper;
        this.ledgerGuard = ledgerGuard;
        this.ingestHandler = ingestHandler;
        this.traceSupport = traceSupport;
    }

    @KafkaListener(topics = {DocumentEventTypes.INDEX_REQUESTED, DocumentEventTypes.DELETE_REQUESTED},
            groupId = "aiwarden-ingest")
    public void onDocumentEvent(ConsumerRecord<String, String> record) {
        // Kafka 消费边界（FR-OBS-02）：生产端在 outbox 写入时捕获 traceparent，这里恢复父上下文。
        Span span = traceSupport.startSpan("kafka.consume." + record.topic(), traceParentOf(record));
        span.tag("messaging.system", "kafka");
        span.tag("messaging.destination.name", record.topic());
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(span)) {
            TenantContext.runWithCarrier(new KafkaHeadersCarrier(record.headers()), () -> process(record));
        } finally {
            traceSupport.end(span);
        }
    }

    private void process(ConsumerRecord<String, String> record) {
        DocumentEventPayload payload = objectMapper.readValue(record.value(), DocumentEventPayload.class);
        long tenantId = TenantContext.requireTenantIdAsLong();

        boolean deleteEvent = DocumentEventTypes.DELETE_REQUESTED.equals(record.topic());
        boolean claimed = deleteEvent
                ? ledgerGuard.tryClaimForDelete(tenantId, payload.docId(), payload.version())
                : ledgerGuard.tryClaimForIndex(tenantId, payload.docId(), payload.version());
        if (!claimed) {
            log.debug("幂等跳过（已处理 / 处理中）：docId={} version={}", payload.docId(), payload.version());
            return;
        }
        try {
            ingestHandler.handle(record.topic(), payload);
            if (deleteEvent) {
                ledgerGuard.markDeleted(payload.docId());
            } else {
                ledgerGuard.markIndexed(payload.docId(), payload.version());
            }
        } catch (DocumentNotFoundException e) {
            // FR-ING-04：不可重试类错误——直接终态，不重试不进死信
            log.warn("不可重试的摄入失败（直接终态）：docId={} version={} 原因={}",
                    payload.docId(), payload.version(), e.getMessage());
            ledgerGuard.markFailed(payload.docId(), payload.version(), e.getMessage());
        } catch (RuntimeException e) {
            ledgerGuard.markFailed(payload.docId(), payload.version(), e.getMessage());
            throw e;
        }
    }

    private static String traceParentOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(TraceSupport.TRACEPARENT);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
