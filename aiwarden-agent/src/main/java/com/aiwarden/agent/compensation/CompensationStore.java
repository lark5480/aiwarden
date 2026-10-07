package com.aiwarden.agent.compensation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补偿计划存取（显式 SQL；行级隔离）。
 *
 * <p>执行顺序由查询约定：{@code ORDER BY invocation_id DESC}——**逆序回滚**
 * （后续步骤先撤销，ADR-009 决策 4）；{@code attempt} 每次执行 +1（可观测重跑次数）。
 *
 * <p><b>FAILED 必须可重跑</b>：补偿失败把状态置 FAILED 后，若只查 PENDING，第二次
 * {@code run} 就永远返回 {@code executed=0}——会话卡在「半补偿」且无任何提示，
 * 而 SPI javadoc 承诺的是「等待人工/重跑」。因此执行集合 = PENDING ∪ FAILED(attempt &lt; 上限)。
 */
@Component
public class CompensationStore {

    private final JdbcTemplate jdbcTemplate;

    public CompensationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 指定会话下可执行的补偿计划：PENDING（首次）或 FAILED 且未超过重跑上限（重试），
     * 按被补偿调用 id 逆序。
     */
    public List<PlanRow> findExecutable(long tenantId, String sessionId, int maxAttempts) {
        return jdbcTemplate.query("""
                        SELECT cl.id, cl.invocation_id, cl.action, cl.status, cl.attempt
                        FROM t_compensation_log cl
                        JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                        WHERE cl.tenant_id = ? AND ti.session_id = ?
                          AND (cl.status = 'PENDING' OR (cl.status = 'FAILED' AND cl.attempt < ?))
                        ORDER BY cl.invocation_id DESC
                        """,
                (rs, rowNum) -> new PlanRow(
                        rs.getLong("id"), rs.getLong("invocation_id"),
                        rs.getString("action"), rs.getString("status"), rs.getInt("attempt")),
                tenantId, sessionId, maxAttempts);
    }

    /** 超过重跑上限、仍处 FAILED 的计划（可观测：这些是真正需要人工介入的）。 */
    public int countExhausted(long tenantId, String sessionId, int maxAttempts) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM t_compensation_log cl
                JOIN t_tool_invocation ti ON ti.id = cl.invocation_id
                WHERE cl.tenant_id = ? AND ti.session_id = ?
                  AND cl.status = 'FAILED' AND cl.attempt >= ?
                """, Integer.class, tenantId, sessionId, maxAttempts);
        return count == null ? 0 : count;
    }

    public void markSucceeded(long planId) {
        jdbcTemplate.update("""
                UPDATE t_compensation_log
                SET status = 'SUCCEEDED', attempt = attempt + 1, updated_at = now()
                WHERE id = ?
                """, planId);
    }

    /**
     * 记 NO_OP（补偿执行了但**没有产生效果**：受影响 0 行 / 目标已处于终态）。
     *
     * <p>单独一个状态而不是复用 SUCCEEDED：两者对运维的含义不同——SUCCEEDED 表示
     * 「确实撤销了一件事」，NO_OP 表示「本来就没有需要撤销的东西」。混在一起会让
     * 「补偿成功率」这个指标失去意义。NO_OP 是终态（不再重跑）。
     */
    public void markNoOp(long planId, String detail) {
        jdbcTemplate.update("""
                UPDATE t_compensation_log
                SET status = 'NO_OP', attempt = attempt + 1, detail = ?, updated_at = now()
                WHERE id = ?
                """, detail, planId);
    }

    public void markFailed(long planId, String detail) {
        jdbcTemplate.update("""
                UPDATE t_compensation_log
                SET status = 'FAILED', attempt = attempt + 1, detail = ?, updated_at = now()
                WHERE id = ?
                """, detail, planId);
    }

    /** 补偿计划行。 */
    public record PlanRow(long id, long invocationId, String action, String status, int attempt) {
    }
}
