package com.aiwarden.governance.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计留痕写入（FR-PERM-05）：越权尝试等治理决策的不可变记录。
 *
 * <p><b>不可变由两层保证</b>：应用层只提供 INSERT（本类无更新 / 删除方法），
 * 数据库层 {@code trg_t_audit_log_immutable} 触发器再拒 UPDATE / DELETE——
 * 「不可变留痕」不是文档承诺，是可被测试断言的行为（V4 迁移）。
 *
 * <p><b>独立事务（REQUIRES_NEW）是必须的</b>：拒绝路径的典型形态是
 * 「写审计 → 抛拒绝异常」——若与业务同事务，异常回滚会把审计一起滚掉，
 * 「越权留痕」在数据库里将是零条（本类在 M2 实测踩过：测试断言审计计数为 0）。
 * 审计先于主事务落库，业务失败不影响留痕。写入失败不吞错：静默丢审计等于承诺失效。
 */
@Component
public class AuditLogWriter {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条审计记录（独立事务，主事务回滚不影响落库）。
     *
     * @param tenantId 租户（行级隔离）
     * @param actor    操作主体（M2：userId；接入认证后为认证主体标识）
     * @param action   动作（如 RETRIEVAL_DENIED / TOOL_DENIED / QUOTA_EXCEEDED）
     * @param target   目标对象（kbId / docId / 工具名；无具体目标传 "*"）
     * @param result   结果（DENIED / ALLOWED ...）
     * @param detail   明细（可空）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(long tenantId, String actor, String action, String target, String result, String detail) {
        jdbcTemplate.update("""
                INSERT INTO t_audit_log (tenant_id, actor, action, target, result, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, actor, action, target, result, detail);
    }
}
