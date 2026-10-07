package com.aiwarden.governance.quota;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;

/**
 * 账单对账（FR-COST-05 / ADR-010 决策 5）：**Redis 配额账 vs 明细表 SUM(tokens)**。
 *
 * <p>口径前提：消耗配额的操作其明细 token == 扣减当量（工具调用写 {@code prompt_tokens=当量}）——
 * 因此同一账期内预算全程启用时二者应严格对齐；**不是绝对一致**（reserve 后进程崩溃的泄漏窗口
 * 由本对账发现，属 B2 边界声明覆盖范围）。差异清单与差异率落 {@code t_reconcile_report(type=BILLING)}。
 */
@Component
public class BillingReconciler {

    private final QuotaService quotaService;
    private final BudgetService budgetService;
    private final JdbcTemplate jdbcTemplate;

    public BillingReconciler(QuotaService quotaService,
                             BudgetService budgetService,
                             JdbcTemplate jdbcTemplate) {
        this.quotaService = quotaService;
        this.budgetService = budgetService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 对账结论。 */
    public record BillingReport(String period, Long budgetLimit, long redisUsage, long ledgerTokens,
                                long mismatch, double mismatchRate, long reportId) {
    }

    /** 执行对账（账期为空取当前账期）：差异清单 + 差异率 + 报告落库。 */
    @Transactional
    public BillingReport reconcile(long tenantId, String period) {
        String effectivePeriod = (period == null || period.isBlank())
                ? QuotaService.currentPeriod() : period;
        if (!effectivePeriod.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("period 格式应为 YYYY-MM：" + effectivePeriod);
        }

        Long budgetLimit = budgetService.find(tenantId, effectivePeriod)
                .map(BudgetService.BudgetRow::tokenLimit).orElse(null);
        long redisUsage = quotaService.currentUsage(tenantId, effectivePeriod);
        Long ledgerSum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0)
                FROM t_llm_call_log
                WHERE tenant_id = ? AND to_char(created_at, 'YYYY-MM') = ?
                """, Long.class, tenantId, effectivePeriod);
        long ledgerTokens = ledgerSum == null ? 0 : ledgerSum;
        long mismatch = redisUsage - ledgerTokens;
        double mismatchRate = ledgerTokens == 0
                ? (redisUsage == 0 ? 0.0 : 1.0)
                : Math.abs((double) mismatch) / ledgerTokens;

        YearMonth month = YearMonth.parse(effectivePeriod);
        OffsetDateTime windowStart = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime windowEnd = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        String detailJson = """
                {"budgetLimit": %s, "redisUsage": %d, "ledgerTokens": %d, "mismatch": %d, "mismatchRate": %s}"""
                .formatted(budgetLimit == null ? "null" : budgetLimit, redisUsage, ledgerTokens,
                        mismatch, mismatchRate);
        Long reportId = jdbcTemplate.queryForObject("""
                INSERT INTO t_reconcile_report (type, window_start, window_end, mismatch_count, detail_ref)
                VALUES ('BILLING', ?, ?, ?, ?) RETURNING id
                """, Long.class, windowStart, windowEnd, (int) Math.min(Math.abs(mismatch), Integer.MAX_VALUE),
                detailJson);

        return new BillingReport(effectivePeriod, budgetLimit, redisUsage, ledgerTokens,
                mismatch, mismatchRate, reportId);
    }
}
