package com.aiwarden.knowledge.document;

import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.contract.knowledge.DocumentDeleteResponse;
import com.aiwarden.contract.knowledge.DocumentStatusResponse;
import com.aiwarden.contract.knowledge.DocumentUploadResponse;
import com.aiwarden.core.event.DocumentEventTypes;
import com.aiwarden.governance.outbox.OutboxWriter;
import com.aiwarden.knowledge.ingest.DocumentEventPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 文档服务（FR-KB-02/03/04）：业务写入与 outbox 事件<b>同事务</b>——一致性管道的起点（ADR-005）。
 *
 * <p>口径：跨租户 / 不存在 / 已删除统一 NotFoundException → 404（不区分 403，避免探测存在性）。
 */
@Service
public class DocumentService {

    private final JdbcTemplate jdbcTemplate;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public DocumentService(JdbcTemplate jdbcTemplate, OutboxWriter outboxWriter, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传文档（FR-KB-02 / FR-ING-03）：同名重传产生新 version；
     * 写入文档记录 + outbox 索引事件（同事务），接口不等摄入完成。
     */
    @Transactional
    public DocumentUploadResponse upload(long tenantId, long kbId, String name, String content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文档名称不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        Integer kbCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_knowledge_base WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, kbId);
        if (kbCount == 0) {
            throw new NotFoundException("知识库不存在：kbId=" + kbId);
        }

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO t_document (tenant_id, kb_id, name, content)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (kb_id, name) DO UPDATE
                  SET content = EXCLUDED.content,
                      version = t_document.version + 1,
                      status = 'PENDING',
                      deleted_at = NULL,
                      updated_at = now()
                RETURNING id, version
                """, tenantId, kbId, name, content);
        long docId = ((Number) row.get("id")).longValue();
        int version = ((Number) row.get("version")).intValue();

        outboxWriter.append(tenantId, docId, DocumentEventTypes.INDEX_REQUESTED, payloadJson(docId, version));
        return new DocumentUploadResponse(docId, version, "PENDING");
    }

    /**
     * 删除文档（FR-KB-03）：同事务写删除标记 + outbox 删除事件；
     * 向量清理为异步（ledger 进度可查），文档级状态立即置 DELETED。
     */
    @Transactional
    public DocumentDeleteResponse delete(long tenantId, long docId) {
        Map<String, Object> row = requireActiveDocument(tenantId, docId);
        int version = ((Number) row.get("version")).intValue();

        jdbcTemplate.update("""
                UPDATE t_document SET deleted_at = now(), status = 'DELETED', updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, docId);
        outboxWriter.append(tenantId, docId, DocumentEventTypes.DELETE_REQUESTED, payloadJson(docId, version));

        return new DocumentDeleteResponse(docId + ":" + version, docId);
    }

    /** 状态查询（FR-KB-04）：文档级状态 + 账本明细（索引进度 / 清理进度 / 失败原因）。 */
    public DocumentStatusResponse status(long tenantId, long docId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, version, status FROM t_document WHERE tenant_id = ? AND id = ?
                """, tenantId, docId);
        if (rows.isEmpty()) {
            throw new NotFoundException("文档不存在：docId=" + docId);
        }
        Map<String, Object> document = rows.get(0);
        int version = ((Number) document.get("version")).intValue();

        List<Map<String, Object>> ledger = jdbcTemplate.queryForList("""
                SELECT status, error FROM t_ingest_ledger WHERE doc_id = ? AND version = ?
                """, docId, version);
        String ledgerStatus = ledger.isEmpty() ? null : (String) ledger.get(0).get("status");
        String error = ledger.isEmpty() ? null : (String) ledger.get(0).get("error");

        return new DocumentStatusResponse(docId, version, (String) document.get("status"), ledgerStatus, error);
    }

    private Map<String, Object> requireActiveDocument(long tenantId, long docId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, version FROM t_document
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """, tenantId, docId);
        if (rows.isEmpty()) {
            throw new NotFoundException("文档不存在或已删除：docId=" + docId);
        }
        return rows.get(0);
    }

    private String payloadJson(long docId, int version) {
        return objectMapper.writeValueAsString(new DocumentEventPayload(docId, version));
    }
}
