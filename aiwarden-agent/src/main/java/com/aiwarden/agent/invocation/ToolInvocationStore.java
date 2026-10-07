package com.aiwarden.agent.invocation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 工具调用账本存取（显式 SQL；所有查询显式带 tenant_id——行级隔离）。
 *
 * <p>状态机（ADR-009 决策 2）：{@code PROCESSING → SUCCEEDED / FAILED}；
 * 重放仲裁的原子性由唯一约束 {@code (tenant_id, idem_key)} 与条件更新（CAS）保证。
 */
@Component
public class ToolInvocationStore {

    private final JdbcTemplate jdbcTemplate;

    public ToolInvocationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 抢占幂等键：INSERT {@code PROCESSING}，冲突返回空——「本调用是首次」的唯一裁决点。
     */
    public Long tryClaim(long tenantId, String idemKey, String sessionId, int stepNo,
                         String tool, String inputJson) {
        List<Long> ids = jdbcTemplate.query("""
                INSERT INTO t_tool_invocation (tenant_id, idem_key, session_id, step_no, tool, input, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING')
                ON CONFLICT (tenant_id, idem_key) DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> rs.getLong("id"),
                tenantId, idemKey, sessionId, stepNo, tool, inputJson);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Optional<InvocationRow> findByKey(long tenantId, String idemKey) {
        return jdbcTemplate.query("""
                        SELECT id, idem_key, session_id, step_no, tool, input, result, error, status
                        FROM t_tool_invocation WHERE tenant_id = ? AND idem_key = ?
                        """,
                (rs, rowNum) -> mapRow(rs),
                tenantId, idemKey).stream().findFirst();
    }

    public InvocationRow requireById(long tenantId, long id) {
        return jdbcTemplate.query("""
                        SELECT id, idem_key, session_id, step_no, tool, input, result, error, status
                        FROM t_tool_invocation WHERE tenant_id = ? AND id = ?
                        """,
                (rs, rowNum) -> mapRow(rs),
                tenantId, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("工具调用记录不存在：id=" + id));
    }

    /**
     * 超租约重抢（CAS）：仅当仍是 {@code PROCESSING} 且超过租约时可抢——
     * 「不确定状态可重试」（原执行者可能已崩溃），确定失败（FAILED）不在此列。
     */
    public boolean tryReclaim(long tenantId, String idemKey, long leaseSeconds) {
        int updated = jdbcTemplate.update("""
                UPDATE t_tool_invocation
                SET status = 'PROCESSING', updated_at = now(), error = NULL
                WHERE tenant_id = ? AND idem_key = ? AND status = 'PROCESSING'
                  AND updated_at < now() - (?::bigint * interval '1 second')
                """, tenantId, idemKey, leaseSeconds);
        return updated == 1;
    }

    public void markSucceeded(long id, String resultJson) {
        jdbcTemplate.update("""
                UPDATE t_tool_invocation SET status = 'SUCCEEDED', result = ?, updated_at = now()
                WHERE id = ?
                """, resultJson, id);
    }

    public void markFailed(long id, String error) {
        jdbcTemplate.update("""
                UPDATE t_tool_invocation SET status = 'FAILED', error = ?, updated_at = now()
                WHERE id = ?
                """, error, id);
    }

    /** 二态审批（FR-TOOL-03）：需确认的工具在 claim 后挂起（输入快照已落库 = 挂起上下文）。 */
    public void markPendingApproval(long id) {
        jdbcTemplate.update("""
                UPDATE t_tool_invocation SET status = 'PENDING_APPROVAL', updated_at = now()
                WHERE id = ?
                """, id);
    }

    /** 批准（CAS：PENDING_APPROVAL → PROCESSING）；已终态 / 不存在返回 false。 */
    public boolean tryApprove(long id) {
        int updated = jdbcTemplate.update("""
                UPDATE t_tool_invocation SET status = 'PROCESSING', updated_at = now()
                WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, id);
        return updated == 1;
    }

    /** 驳回（CAS：PENDING_APPROVAL → REJECTED 终态）。 */
    public boolean tryReject(long id) {
        int updated = jdbcTemplate.update("""
                UPDATE t_tool_invocation SET status = 'REJECTED', updated_at = now()
                WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, id);
        return updated == 1;
    }

    /** 会话内更早的成功调用（id < beforeId），按 id 逆序——补偿计划的候选集（ADR-009 决策 4）。 */
    public List<InvocationRow> findSuccessesBefore(long tenantId, String sessionId, long beforeId) {
        return jdbcTemplate.query("""
                        SELECT id, idem_key, session_id, step_no, tool, input, result, error, status
                        FROM t_tool_invocation
                        WHERE tenant_id = ? AND session_id = ? AND status = 'SUCCEEDED' AND id < ?
                        ORDER BY id DESC
                        """,
                (rs, rowNum) -> mapRow(rs),
                tenantId, sessionId, beforeId);
    }

    /** 生成补偿计划（PENDING）：{@code UNIQUE(invocation_id)} 保证幂等（重复失败不重复计划）。 */
    public void insertCompensationPlan(long tenantId, long invocationId, String action) {
        jdbcTemplate.update("""
                INSERT INTO t_compensation_log (tenant_id, invocation_id, action, status)
                VALUES (?, ?, ?, 'PENDING')
                ON CONFLICT (invocation_id) DO NOTHING
                """, tenantId, invocationId, action);
    }

    private InvocationRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InvocationRow(
                rs.getLong("id"), rs.getString("idem_key"), rs.getString("session_id"),
                rs.getInt("step_no"), rs.getString("tool"), rs.getString("input"),
                rs.getString("result"), rs.getString("error"), rs.getString("status"));
    }

    /** 调用记录（result/error 仅在对应终态有值）。 */
    public record InvocationRow(long id, String idemKey, String sessionId, int stepNo, String tool,
                                String input, String result, String error, String status) {
    }
}
