package com.aiwarden.core.event;

/**
 * 文档事件类型：即 outbox 事件 type，也是 Kafka topic 名（本项目约定，见 DECISIONS ADR-005）。
 *
 * <p>生产端由 OutboxWriter 写入 t_outbox_event；消费端见 aiwarden-knowledge 的 DocumentEventConsumer。
 */
public final class DocumentEventTypes {

    /** 索引请求（上传/重传后触发，FR-ING-01） */
    public static final String INDEX_REQUESTED = "document.index.requested";

    /** 删除请求（删除后触发，FR-ING-05） */
    public static final String DELETE_REQUESTED = "document.delete.requested";

    private DocumentEventTypes() {
    }
}
