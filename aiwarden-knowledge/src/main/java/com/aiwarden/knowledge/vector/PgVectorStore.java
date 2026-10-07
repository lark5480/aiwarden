package com.aiwarden.knowledge.vector;

import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.core.spi.VectorRecord;
import com.aiwarden.core.spi.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pgvector 实现（PRD §7.3 的 SPI 扩展点实现）。
 *
 * <p>向量以 pgvector 字面量（{@code '[..]'}）经 {@code ::vector} 转换写入——演示规模免引
 * pgvector-java 依赖；维度与 {@link EmbeddingClient#DIMENSION} 强校验，防止与迁移的
 * {@code vector(1536)} 漂移。
 */
@Component
public class PgVectorStore implements VectorStore {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        for (VectorRecord record : records) {
            if (record.embedding().length != EmbeddingClient.DIMENSION) {
                throw new IllegalArgumentException("向量维度与 schema 不一致：期望 "
                        + EmbeddingClient.DIMENSION + "，实际 " + record.embedding().length);
            }
            jdbcTemplate.update("""
                    INSERT INTO t_vector (chunk_id, embedding, meta)
                    VALUES (?, ?::vector, ?::jsonb)
                    ON CONFLICT (chunk_id) DO UPDATE
                      SET embedding = EXCLUDED.embedding, meta = EXCLUDED.meta
                    """, record.chunkId(), PgVectorLiteral.of(record.embedding()), record.metaJson());
        }
    }

    @Override
    public void deleteByVersion(long docId, int version) {
        jdbcTemplate.update("""
                DELETE FROM t_vector
                WHERE chunk_id IN (SELECT id FROM t_chunk WHERE doc_id = ? AND version = ?)
                """, docId, version);
    }

    @Override
    public void deleteByDoc(long docId) {
        jdbcTemplate.update("""
                DELETE FROM t_vector
                WHERE chunk_id IN (SELECT id FROM t_chunk WHERE doc_id = ?)
                """, docId);
    }
}
