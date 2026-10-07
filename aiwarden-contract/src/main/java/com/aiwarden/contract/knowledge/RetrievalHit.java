package com.aiwarden.contract.knowledge;

/**
 * 单条检索命中（distance 为 pgvector 余弦距离：0 = 完全同向）。
 */
public record RetrievalHit(long chunkId, long docId, String content, double distance) {
}
