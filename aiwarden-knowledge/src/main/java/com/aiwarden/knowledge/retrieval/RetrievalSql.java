package com.aiwarden.knowledge.retrieval;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 检索 SQL 的唯一出口（生产路径与测试 EXPLAIN 自证共用同一常量）。
 *
 * <p><b>为什么抽成类常量</b>：ADR-007 落实清单 #5 要求断言「执行计划确实走了 HNSW 索引」——
 * 若 EXPLAIN 测试另写一份 SQL，断言的就是测试自己的 SQL 而不是生产 SQL，"全对但什么都没证明"。
 * 生产与自证必须消费同一字符串。
 *
 * <p><b>可见性过滤只允许在本 SQL 的 WHERE 中表达</b>（ADR-008）：应用层没有「先查全量再过滤」
 * 的路径——ArchUnit 规则与签名守护见 {@code ArchitectureTest}。
 */
public final class RetrievalSql {

    /**
     * 下推检索（主路径）：可见集过滤与向量排序在同一条针对 t_vector 的 HNSW 扫描中表达
     * （ADR-007 决策），{@code JOIN t_chunk} 仅承担「按 chunkId 回表取内容」的职责。
     *
     * <p>参数顺序：queryLiteral（vector 字面量）→ tenantId（text）→ kbIds → allowDocIds → denyDocIds
     * （三个 text[] 字面量）→ limit。
     */
    public static final String PUSHDOWN = """
            SELECT v.chunk_id, c.doc_id, c.content, v.embedding <=> ?::vector AS distance
            FROM t_vector v
            JOIN t_chunk c ON c.id = v.chunk_id
            WHERE v.meta->>'tenantId' = ?
              AND (v.meta->>'kbId' = ANY(?::text[]) OR v.meta->>'docId' = ANY(?::text[]))
              AND NOT (v.meta->>'docId' = ANY(?::text[]))
            ORDER BY distance
            LIMIT ?
            """;

    /**
     * 可见总数（精确计数，同一套下推谓词）：回退判定用——只有「HNSW 返回数 < 可见总数」才是
     * 近似索引塌陷（ADR-007 语义），而「可见总数本来就 < K」不回退（避免小可见集的无谓全扫）。
     *
     * <p>参数顺序：tenantId（text）→ kbIds → allowDocIds → denyDocIds。
     */
    public static final String COUNT_VISIBLE = """
            SELECT count(*) FROM t_vector v
            WHERE v.meta->>'tenantId' = ?
              AND (v.meta->>'kbId' = ANY(?::text[]) OR v.meta->>'docId' = ANY(?::text[]))
              AND NOT (v.meta->>'docId' = ANY(?::text[]))
            """;

    private RetrievalSql() {
    }

    /**
     * PG text[] 字面量序列化（值均为 long，无注入面）；空集合 → {@code {}}。
     *
     * <p>不依赖驱动对 Java 数组参数的隐式包装——显式字面量让「任何集合（含空集）都直接可比较」，
     * 空集时 {@code ANY('{}')} 为假，语义清晰。
     */
    public static String toTextArrayLiteral(Collection<Long> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
