package com.aiwarden.knowledge.ingest;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.event.DocumentEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摄入处理器（M1 切片③，替换占位实现）：消费骨架的「真实处理」侧。
 *
 * <p>INDEX：读文档 → 切块 → 嵌入 → 切片 / 向量落库（版本化替换，FR-ING-03）→ 文档 INDEXED；
 * DELETE：清向量与切片（全版本，幂等）→ 文档 DELETED。
 *
 * <p>错误分级（FR-ING-04）：{@link DocumentNotFoundException} 为不可重试类，消费者侧直接终态；
 * 其余异常抛出让 Kafka 错误处理器退避重试 → 死信。
 *
 * <p>注：当前为直白顺序流水线；FR-ING-02 的 DAG 引擎（条件分支 / 环路检测）为后续独立切片，
 * 届时本节点的处理逻辑按类迁入 DAG 节点。
 */
@Component
public class DefaultDocumentIngestHandler implements DocumentIngestHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentIngestHandler.class);

    private final DocumentIngestStore store;
    private final SimpleTextChunker chunker;

    public DefaultDocumentIngestHandler(DocumentIngestStore store, SimpleTextChunker chunker) {
        this.store = store;
        this.chunker = chunker;
    }

    @Override
    public void handle(String eventType, DocumentEventPayload payload) {
        long tenantId = TenantContext.requireTenantIdAsLong();

        if (DocumentEventTypes.DELETE_REQUESTED.equals(eventType)) {
            store.purgeDocumentArtifacts(tenantId, payload.docId());
            log.info("删除产物清理完成：docId={} version={}", payload.docId(), payload.version());
            return;
        }

        DocumentIngestStore.DocumentRow document = store.requireDocument(tenantId, payload.docId());
        List<String> chunks = chunker.chunk(document.content());
        store.indexDocument(tenantId, payload.docId(), payload.version(), document.kbId(),
                document.orgId(), chunks);
        log.info("摄入完成：docId={} version={} 切片数={}", payload.docId(), payload.version(), chunks.size());
    }
}
