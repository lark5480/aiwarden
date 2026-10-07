package com.aiwarden.governance.quota;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 租户预算（P4a / ADR-010 决策 7）：{@code t_budget} 的唯一读写入口。
 *
 * <p><b>未配置 = 不启用配额检查</b>（演示默认零门槛）；配置即对当前账期生效。
 */
@Component
public class BudgetService {

    private final JdbcTemplate jdbcTemplate;

    public BudgetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某租户某账期的预算（不存在返回空 = 未启用配额检查）。 */
    public Optional<BudgetRow> find(long tenantId, String period) {
        return jdbcTemplate.query("""
                        SELECT tenant_id, period, token_limit FROM t_budget
                        WHERE tenant_id = ? AND period = ?
                        """,
                (rs, rowNum) -> new BudgetRow(
                        rs.getLong("tenant_id"), rs.getString("period"), rs.getLong("token_limit")),
                tenantId, period).stream().findFirst();
    }

    /** 设置（upsert）预算。 */
    @Transactional
    public BudgetRow set(long tenantId, String period, long tokenLimit) {
        if (tokenLimit < 0) {
            throw new IllegalArgumentException("token_limit 不能为负");
        }
        jdbcTemplate.update("""
                INSERT INTO t_budget (tenant_id, period, token_limit) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, period)
                DO UPDATE SET token_limit = EXCLUDED.token_limit, updated_at = now()
                """, tenantId, period, tokenLimit);
        return new BudgetRow(tenantId, period, tokenLimit);
    }

    /** 预算行。 */
    public record BudgetRow(long tenantId, String period, long tokenLimit) {
    }
}
