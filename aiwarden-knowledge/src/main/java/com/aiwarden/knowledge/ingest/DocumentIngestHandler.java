package com.aiwarden.knowledge.ingest;

/**
 * 文档事件处理器：由 {@link DocumentEventConsumer} 在「租户上下文已恢复 + 幂等抢占成功」后调用。
 *
 * <p>实现抛出的异常由 Kafka 错误处理器接管（指数退避重试 ≤3 次 → DLT，FR-ING-04），
 * 消费者侧同时把 ledger 置为 FAILED（可被重试重新抢占，见 IngestLedgerGuard）。
 *
 * <p>M1 切片③将提供真实实现（解析 → 切块 → 向量化 → 落库）；当前为日志占位实现。
 */
public interface DocumentIngestHandler {

    /**
     * @param eventType 事件类型（即 topic 名，见 DocumentEventTypes）
     * @param payload   事件载荷
     */
    void handle(String eventType, DocumentEventPayload payload);
}
