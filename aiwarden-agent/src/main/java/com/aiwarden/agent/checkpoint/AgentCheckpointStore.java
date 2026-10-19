package com.aiwarden.agent.checkpoint;

import com.aiwarden.common.exception.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Checkpoint 存取（FR-CKPT-02/03）：显式 SQL + 短事务，状态推进一律走版本 CAS。
 *
 * <p><b>两个不变量</b>：
 * <ol>
 *   <li>{@code t_agent_session.version} 每完成一步 +1；写入步骤时
 *       {@code WHERE version = expectedVersion}，冲突即拒绝（不覆盖别人的推进）；</li>
 *   <li>步骤行与版本推进在<b>同一事务</b>内：要么「步骤可见 + 版本前进」，要么都不发生——
 *       不会出现「步骤写了但版本没动」的半截检查点。</li>
 * </ol>
 *
 * <p><b>租约</b>（{@code owner / lease_until}）：同一 run 只允许一个实例推进。崩溃实例的租约过期后
 * 由续跑实例 CAS 接管；租约未过期时续跑返回 409，而不是两个实例同时执行副作用。
 */
@Component
public class AgentCheckpointStore {

    private final JdbcTemplate jdbcTemplate;
    private final long leaseSeconds;

    public AgentCheckpointStore(JdbcTemplate jdbcTemplate,
                                @Value("${aiwarden.agent.checkpoint.lease-seconds:60}") long leaseSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseSeconds = leaseSeconds;
    }

    /** 新建运行（session_key 可重复：同一会话的多次问答是不同 run）。 */
    public long createRun(long tenantId, long userId, Long orgId, String sessionKey,
                          String requestJson, String owner, String traceId, String traceParent) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO t_agent_session
                    (tenant_id, user_id, org_id, session_key, request, status, last_step_no, version,
                     owner, lease_until, trace_id, trace_parent)
                VALUES (?, ?, ?, ?, ?::jsonb, 'RUNNING', 0, 0, ?,
                        now() + (?::bigint * interval '1 second'), ?, ?)
                RETURNING id
                """, Long.class,
                tenantId, userId, orgId, sessionKey, requestJson, owner, leaseSeconds, traceId, traceParent);
        if (id == null) {
            throw new IllegalStateException("创建 Agent 运行失败：未返回 id");
        }
        return id;
    }

    /**
     * 该会话下最新的可续跑运行（RUNNING / ABORTED）。
     *
     * <p>FAILED 不自动续跑：同一输入重放只会得到同一错误，留给人看检查点（ADR-013）。
     */
    public Optional<RunRow> findLatestResumable(long tenantId, String sessionKey) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, org_id, session_key, request::text AS request,
                               status, last_step_no, version, trace_id, trace_parent, outcome, reason
                        FROM t_agent_session
                        WHERE tenant_id = ? AND session_key = ?
                          AND status IN ('RUNNING', 'ABORTED')
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> mapRun(rs),
                tenantId, sessionKey).stream().findFirst();
    }

    public Optional<RunRow> findById(long tenantId, long runId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, org_id, session_key, request::text AS request,
                               status, last_step_no, version, trace_id, trace_parent, outcome, reason
                        FROM t_agent_session
                        WHERE tenant_id = ? AND id = ?
                        """,
                (rs, rowNum) -> mapRun(rs),
                tenantId, runId).stream().findFirst();
    }

    /** 该会话下最新的一次运行（任意状态）——运行视图 / trace 查询用。 */
    public Optional<RunRow> findLatest(long tenantId, String sessionKey) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, org_id, session_key, request::text AS request,
                               status, last_step_no, version, trace_id, trace_parent, outcome, reason
                        FROM t_agent_session
                        WHERE tenant_id = ? AND session_key = ?
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> mapRun(rs),
                tenantId, sessionKey).stream().findFirst();
    }

    /** 按 traceId 查运行（FR-ADM-03：从一次调用的 trace 还原完整步骤时间线）。 */
    public Optional<RunRow> findByTraceId(long tenantId, String traceId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, org_id, session_key, request::text AS request,
                               status, last_step_no, version, trace_id, trace_parent, outcome, reason
                        FROM t_agent_session
                        WHERE tenant_id = ? AND trace_id = ?
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> mapRun(rs),
                tenantId, traceId).stream().findFirst();
    }

    public List<StepRow> findSteps(long tenantId, long runId) {
        return jdbcTemplate.query("""
                        SELECT id, step_no, name, input::text AS input, output::text AS output,
                               detail, elapsed_ms, status, version, trace_id
                        FROM t_agent_step
                        WHERE tenant_id = ? AND session_id = ?
                        ORDER BY step_no
                        """,
                (rs, rowNum) -> new StepRow(
                        rs.getLong("id"), rs.getInt("step_no"), rs.getString("name"),
                        rs.getString("input"), rs.getString("output"), rs.getString("detail"),
                        rs.getLong("elapsed_ms"), rs.getString("status"),
                        rs.getInt("version"), rs.getString("trace_id")),
                tenantId, runId);
    }

    /** 租约接管：可续跑状态下，租约过期 / 无租约 / 同一 owner 才允许（CAS）；ABORTED 接管后回到 RUNNING。 */
    public boolean tryClaimLease(long tenantId, long runId, String owner) {
        int updated = jdbcTemplate.update("""
                UPDATE t_agent_session
                SET owner = ?, lease_until = now() + (?::bigint * interval '1 second'),
                    status = 'RUNNING', updated_at = now()
                WHERE tenant_id = ? AND id = ?
                  AND status IN ('RUNNING', 'ABORTED')
                  AND (lease_until IS NULL OR lease_until < now() OR owner = ?)
                """, owner, leaseSeconds, tenantId, runId, owner);
        return updated == 1;
    }

    /**
     * 完成一步：版本 CAS + 步骤行同事务。返回提交后的步骤行（version = expected + 1）。
     *
     * @throws ConflictException 版本冲突 / 步骤重复（并发推进或过期实例）——调用方不得继续执行后续步骤
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StepRow completeStep(long tenantId, long runId, int expectedVersion, int stepNo,
                                String name, String inputJson, String outputJson,
                                String detail, long elapsedMs, String traceId) {
        int updated = jdbcTemplate.update("""
                UPDATE t_agent_session
                SET last_step_no = ?, version = version + 1, updated_at = now(),
                    lease_until = now() + (?::bigint * interval '1 second')
                WHERE tenant_id = ? AND id = ? AND status = 'RUNNING' AND version = ?
                """, stepNo, leaseSeconds, tenantId, runId, expectedVersion);
        if (updated != 1) {
            throw new ConflictException(
                    "检查点版本冲突（过期实例必须丢弃）：runId=%d expectedVersion=%d"
                            .formatted(runId, expectedVersion));
        }
        int newVersion = expectedVersion + 1;
        try {
            Long stepId = jdbcTemplate.queryForObject("""
                    INSERT INTO t_agent_step
                        (tenant_id, session_id, step_no, name, input, output, detail,
                         elapsed_ms, status, version, trace_id)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'SUCCEEDED', ?, ?)
                    RETURNING id
                    """, Long.class,
                    tenantId, runId, stepNo, name, inputJson, outputJson, detail,
                    elapsedMs, newVersion, traceId);
            return new StepRow(stepId == null ? -1 : stepId, stepNo, name, inputJson, outputJson,
                    detail, elapsedMs, "SUCCEEDED", newVersion, traceId);
        } catch (DuplicateKeyException e) {
            // 同一 run 的 step_no 唯一：另一个实例已经推进到同一步——事务回滚，版本也不会前进
            throw new ConflictException(
                    "检查点步骤重复（并发推进）：runId=%d stepNo=%d".formatted(runId, stepNo));
        }
    }

    /** 结束运行（SUCCEEDED）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(long tenantId, long runId, int expectedVersion,
                          String outcome, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE t_agent_session
                SET status = 'SUCCEEDED', outcome = ?, reason = ?, version = version + 1,
                    owner = NULL, lease_until = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND version = ?
                  AND status IN ('RUNNING', 'ABORTED', 'FAILED')
                """, outcome, reason, tenantId, runId, expectedVersion);
        if (updated != 1) {
            throw new ConflictException(
                    "结束运行失败：版本冲突或状态已终态（runId=%d expectedVersion=%d）"
                            .formatted(runId, expectedVersion));
        }
    }

    /** 中断运行（不结束：允许后续 resume）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abortRun(long tenantId, long runId, int expectedVersion, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE t_agent_session
                SET status = 'ABORTED', outcome = 'aborted', reason = ?, version = version + 1,
                    owner = NULL, lease_until = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND version = ?
                  AND status IN ('RUNNING', 'ABORTED', 'FAILED')
                """, reason, tenantId, runId, expectedVersion);
        if (updated != 1) {
            throw new ConflictException(
                    "中断运行失败：版本冲突（runId=%d expectedVersion=%d）"
                            .formatted(runId, expectedVersion));
        }
    }

    /** 失败 / 拒绝终态（保留步骤；不允许自动续跑）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(long tenantId, long runId, int expectedVersion,
                        String status, String outcome, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE t_agent_session
                SET status = ?, outcome = ?, reason = ?, version = version + 1,
                    owner = NULL, lease_until = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND version = ?
                  AND status IN ('RUNNING', 'ABORTED', 'FAILED')
                """, status, outcome, reason, tenantId, runId, expectedVersion);
        if (updated != 1) {
            throw new ConflictException(
                    "落终态失败：版本冲突（runId=%d expectedVersion=%d）"
                            .formatted(runId, expectedVersion));
        }
    }

    private RunRow mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        long orgId = rs.getLong("org_id");
        Long org = rs.wasNull() ? null : orgId;
        return new RunRow(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("user_id"), org,
                rs.getString("session_key"), rs.getString("request"), rs.getString("status"),
                rs.getInt("last_step_no"), rs.getInt("version"), rs.getString("trace_id"),
                rs.getString("trace_parent"), rs.getString("outcome"), rs.getString("reason"));
    }

    /** 运行行（request 为 JSON 原文；续跑时反序列化，不信任客户端重放）。 */
    public record RunRow(long id, long tenantId, long userId, Long orgId, String sessionKey,
                         String request, String status, int lastStepNo, int version,
                         String traceId, String traceParent, String outcome, String reason) {
    }

    /** 步骤快照行。 */
    public record StepRow(long id, int stepNo, String name, String input, String output,
                          String detail, long elapsedMs, String status, int version, String traceId) {
    }
}
