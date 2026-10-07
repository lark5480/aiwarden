package com.aiwarden.governance.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 摄入账本仲裁器（FR-ING-01）：以 {@code t_ingest_ledger(doc_id, version)} 唯一键做幂等抢占。
 *
 * <p>状态机与并发语义：
 * <ul>
 *   <li>无记录 → INSERT(PROCESSING) 抢占成功；</li>
 *   <li><b>索引抢占</b>：PROCESSING（并发重复）或 INDEXED / DELETED（终态）→ 拒绝；FAILED → 允许重抢
 *       （配合 Kafka 失败重试，避免重试消息被仲裁挡死）；</li>
 *   <li><b>删除抢占</b>：删除是「索引之后」的正常状态流转——INDEXED / FAILED 允许重抢；
 *       DELETED 为文档级终态（拒绝）；PROCESSING（索引处理中）拒绝（消息按聚合 key 分区、同分区顺序
 *       消费，删除消息不会先于索引消息到达）。</li>
 * </ul>
 *
 * <p>注意：PROCESSING 卡死（实例崩溃）的超时回收由对账任务兜底（M1 切片④）。
 */
@Component
public class IngestLedgerGuard {

    private final JdbcTemplate jdbcTemplate;

    public IngestLedgerGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 索引抢占：新记录或 FAILED（Kafka 重试）可抢；PROCESSING / INDEXED / DELETED 拒绝 = 幂等跳过。 */
    public boolean tryClaimForIndex(long tenantId, long docId, int version) {
        if (insertProcessing(tenantId, docId, version)) {
            return true;
        }
        return jdbcTemplate.update("""
                UPDATE t_ingest_ledger SET status = 'PROCESSING', error = NULL, updated_at = now()
                WHERE doc_id = ? AND version = ? AND status = 'FAILED'
                """, docId, version) == 1;
    }

    /** 删除抢占：新记录 / INDEXED / FAILED 可抢；DELETED（终态）与 PROCESSING 拒绝。 */
    public boolean tryClaimForDelete(long tenantId, long docId, int version) {
        if (insertProcessing(tenantId, docId, version)) {
            return true;
        }
        return jdbcTemplate.update("""
                UPDATE t_ingest_ledger SET status = 'PROCESSING', error = NULL, updated_at = now()
                WHERE doc_id = ? AND version = ? AND status IN ('INDEXED', 'FAILED')
                """, docId, version) == 1;
    }

    private boolean insertProcessing(long tenantId, long docId, int version) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO t_ingest_ledger (doc_id, version, tenant_id, status, updated_at)
                VALUES (?, ?, ?, 'PROCESSING', now())
                ON CONFLICT (doc_id, version) DO NOTHING
                """, docId, version, tenantId);
        return inserted == 1;
    }

    public void markIndexed(long docId, int version) {
        mark(docId, version, "INDEXED", null);
    }

    /** 删除完成：文档级终态——删除是文档级操作，账本内该文档全部版本行均置 DELETED。 */
    public void markDeleted(long docId) {
        jdbcTemplate.update("""
                UPDATE t_ingest_ledger SET status = 'DELETED', error = NULL, updated_at = now()
                WHERE doc_id = ?
                """, docId);
    }

    public void markFailed(long docId, int version, String error) {
        mark(docId, version, "FAILED", error);
    }

    private void mark(long docId, int version, String status, String error) {
        jdbcTemplate.update("""
                UPDATE t_ingest_ledger SET status = ?, error = ?, updated_at = now()
                WHERE doc_id = ? AND version = ?
                """, status, error, docId, version);
    }
}
