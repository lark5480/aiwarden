package com.aiwarden.knowledge.ingest;

import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.core.spi.VectorRecord;
import com.aiwarden.core.spi.VectorStore;
import com.aiwarden.governance.metering.CallMeteringPayload;
import com.aiwarden.governance.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄入链路存取（显式 SQL，与治理层同风格；所有查询显式带 tenant_id——行级隔离）。
 *
 * <p>职责：读待摄入文档、切片 + 向量落库（版本化替换，FR-ING-03）、删除产物、状态视图数据。
 */
@Component
public class DocumentIngestStore {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final OutboxWriter outboxWriter;

    public DocumentIngestStore(JdbcTemplate jdbcTemplate,
                               EmbeddingClient embeddingClient,
                               VectorStore vectorStore,
                               ObjectMapper objectMapper,
                               OutboxWriter outboxWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.outboxWriter = outboxWriter;
    }

    /** 读待摄入文档（含 kb.org_id，供 meta 可见集字段装配）；不存在或已删除 → {@link DocumentNotFoundException}（FR-ING-04 不可重试类）。 */
    public DocumentRow requireDocument(long tenantId, long docId) {
        List<DocumentRow> rows = jdbcTemplate.query("""
                        SELECT d.id, d.kb_id, d.content, d.version,
                               (d.deleted_at IS NOT NULL) AS deleted, kb.org_id
                        FROM t_document d
                        JOIN t_knowledge_base kb ON kb.id = d.kb_id
                        WHERE d.tenant_id = ? AND d.id = ?
                        """,
                (rs, rowNum) -> new DocumentRow(
                        rs.getLong("id"), rs.getLong("kb_id"), rs.getString("content"),
                        rs.getInt("version"), rs.getBoolean("deleted"),
                        rs.getObject("org_id", Long.class)),
                tenantId, docId);
        if (rows.isEmpty() || rows.get(0).deleted()) {
            throw new DocumentNotFoundException("文档不存在或已删除：docId=" + docId);
        }
        return rows.get(0);
    }

    /**
     * 摄入落库（单事务）：清同版本（重放安全）→ 写新切片与向量 → 退役旧版本（FR-ING-03：
     * 新版本就绪后旧版本才失效）→ 文档置 INDEXED。
     */
    @Transactional
    public void indexDocument(long tenantId, long docId, int version, long kbId, Long orgId,
                              List<String> chunkTexts) {
        vectorStore.deleteByVersion(docId, version);
        jdbcTemplate.update("DELETE FROM t_chunk WHERE doc_id = ? AND version = ?", docId, version);

        String metaJson = metaJson(docId, version, kbId, tenantId, orgId);
        List<VectorRecord> records = new ArrayList<>(chunkTexts.size());
        for (int seq = 0; seq < chunkTexts.size(); seq++) {
            String content = chunkTexts.get(seq);
            Long chunkId = jdbcTemplate.queryForObject("""
                    INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                    """, Long.class, tenantId, docId, version, seq, content, metaJson);
            long embedStartNanos = System.nanoTime();
            float[] embedding = embeddingClient.embed(content);
            records.add(new VectorRecord(chunkId, embedding, metaJson));
            appendCallMetering(tenantId, docId, embedStartNanos);
        }
        vectorStore.upsert(records);

        List<Integer> staleVersions = jdbcTemplate.queryForList(
                "SELECT DISTINCT version FROM t_chunk WHERE doc_id = ? AND version <> ?",
                Integer.class, docId, version);
        for (Integer stale : staleVersions) {
            vectorStore.deleteByVersion(docId, stale);
        }
        jdbcTemplate.update("DELETE FROM t_chunk WHERE doc_id = ? AND version <> ?", docId, version);

        jdbcTemplate.update("""
                UPDATE t_document SET status = 'INDEXED', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, tenantId, docId, version);
    }

    /** 删除文档产物（幂等）：清全部版本向量与切片，文档置 DELETED。 */
    @Transactional
    public void purgeDocumentArtifacts(long tenantId, long docId) {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT DISTINCT version FROM t_chunk WHERE doc_id = ?", Integer.class, docId);
        for (Integer version : versions) {
            vectorStore.deleteByVersion(docId, version);
        }
        jdbcTemplate.update("DELETE FROM t_chunk WHERE doc_id = ?", docId);
        jdbcTemplate.update("""
                UPDATE t_document SET status = 'DELETED', updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, docId);
    }

    /**
     * 可见集 meta（ADR-007 下推地基）：{@code tenantId} 必含；{@code orgId} 非空才带——
     * 字段全集与 t_chunk.meta 一致（filter 在 t_vector 侧可就地过滤）。
     */
    private String metaJson(long docId, int version, long kbId, long tenantId, Long orgId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenantId", tenantId);
        meta.put("kbId", kbId);
        meta.put("docId", docId);
        meta.put("version", version);
        if (orgId != null) {
            meta.put("orgId", orgId);
        }
        return objectMapper.writeValueAsString(meta);
    }

    /** P4a 计量：每次嵌入调用一条明细——与切片写入同事务，随索引原子的成 / 败。 */
    private void appendCallMetering(long tenantId, long docId, long embedStartNanos) {
        int latencyMs = (int) ((System.nanoTime() - embedStartNanos) / 1_000_000);
        outboxWriter.append(tenantId, docId, MeteringEventTypes.CALL_RECORDED,
                objectMapper.writeValueAsString(
                        new CallMeteringPayload("embedding", "deterministic-demo", null, null, latencyMs, 0, 0)));
    }

    /** 待摄入文档读取模型（含 kb.org_id，供 meta 可见集装配）。 */
    public record DocumentRow(long id, long kbId, String content, int version, boolean deleted,
                              Long orgId) {
    }
}
