package com.aiwarden.core.event;

/**
 * 计量事件类型：即 outbox type 与 Kafka topic 名（约定同 {@link DocumentEventTypes}）。
 */
public final class MeteringEventTypes {

    /**
     * 一次模型 / 工具调用完成后上报的计量明细（P4a 四维归因的最小粒度）。
     * 走与文档事件相同的 Outbox → Kafka → 消费骨架（PRD §8 M1：共用消费骨架，边际成本低）。
     */
    public static final String CALL_RECORDED = "llm.call.recorded";

    private MeteringEventTypes() {
    }
}
