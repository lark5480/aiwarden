package com.aiwarden.knowledge.retrieval;

import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索服务（M1 最小版）：单路向量检索 + 租户过滤。
 *
 * <p>M2 加可见集四级下推（metadata filter）与混合检索单路降级；本类的存在首先是
 * P1 验收载体——文档删除收敛后，该文档不得再出现在任何命中里。
 */
@Service
public class RetrievalService {

    static final int DEFAULT_TOP_K = 10;
    static final int MAX_TOP_K = 50;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;

    public RetrievalService(JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
    }

    public List<RetrievalHit> search(long tenantId, String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int limit = topK == null ? DEFAULT_TOP_K : Math.clamp(topK, 1, MAX_TOP_K);
        String queryLiteral = PgVectorLiteral.of(embeddingClient.embed(query));

        return jdbcTemplate.query("""
                SELECT c.id AS chunk_id, c.doc_id, c.content, v.embedding <=> ?::vector AS distance
                FROM t_vector v
                JOIN t_chunk c ON v.chunk_id = c.id
                WHERE c.tenant_id = ?
                ORDER BY distance
                LIMIT ?
                """,
                (rs, rowNum) -> new RetrievalHit(rs.getLong("chunk_id"), rs.getLong("doc_id"),
                        rs.getString("content"), rs.getDouble("distance")),
                queryLiteral, tenantId, limit);
    }
}
