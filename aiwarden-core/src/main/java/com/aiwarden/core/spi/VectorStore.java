package com.aiwarden.core.spi;

import java.util.List;

/**
 * 向量存储 SPI（PRD §7.3：「只做 pgvector + 一个 SPI 扩展点，证明可替换」）。
 *
 * <p>实现须保证幂等语义（同 chunkId 覆盖写入、删除可重复执行）；
 * 实现方不感知租户——隔离由 chunkId 的归属（t_chunk）承载。
 */
public interface VectorStore {

    /** 写入 / 覆盖向量记录。 */
    void upsert(List<VectorRecord> records);

    /** 删除指定文档指定版本的向量（重放清理 / 版本退役）。 */
    void deleteByVersion(long docId, int version);

    /** 删除指定文档全部版本的向量（文档级删除）。 */
    void deleteByDoc(long docId);
}
