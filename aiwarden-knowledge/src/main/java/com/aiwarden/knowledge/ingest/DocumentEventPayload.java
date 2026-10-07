package com.aiwarden.knowledge.ingest;

/**
 * 文档事件载荷（outbox payload / Kafka 消息 value 的 JSON 映射，Jackson 3 反序列化）。
 *
 * @param docId   文档 id（= outbox aggregate_id = Kafka 消息 key）
 * @param version 文档版本（与 docId 一起构成摄入幂等键，FR-ING-01）
 */
public record DocumentEventPayload(long docId, int version) {
}
