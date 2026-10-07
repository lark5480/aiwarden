package com.aiwarden.knowledge.ingest;

/**
 * 摄入时文档不存在或已删除——FR-ING-04 的「不可重试」错误类：
 * 消费者捕获后直接置 ledger FAILED 并 ack，不进重试 / 死信链路。
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
