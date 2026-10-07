package com.aiwarden.core.spi;

/**
 * 向量记录：chunkId + 向量 + meta（JSON 串，与 t_chunk.meta 一致，保证可见集 filter 可下推）。
 */
public record VectorRecord(long chunkId, float[] embedding, String metaJson) {
}
