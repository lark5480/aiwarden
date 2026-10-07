package com.aiwarden.governance.quota;

import com.aiwarden.common.exception.QuotaExceededException;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.governance.audit.AuditLogWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 配额守卫（P4A / ADR-010 决策 3/4/8）：预算判定 → 预扣减 → 超限 429 语义（指标 + 审计 + 异常）。
 *
 * <p><b>演示当量</b>：无真实 LLM 时以「操作当量」计价（{@code aiwarden.quota.tool-token-cost}）——
 * 真实模型接入后替换为真实 token 数。
 *
 * <p><b>超限不留残留</b>：调用方在事务内收到 {@link QuotaExceededException} → 事务回滚
 * （幂等仲裁行同步消失）；审计用独立事务落库（REQUIRES_NEW，不被回滚）。
 */
@Component
public class QuotaGuard {

    private final BudgetService budgetService;
    private final QuotaService quotaService;
    private final MeterRegistry meterRegistry;
    private final AuditLogWriter auditLogWriter;
    private final long toolTokenCost;

    public QuotaGuard(BudgetService budgetService,
                      QuotaService quotaService,
                      MeterRegistry meterRegistry,
                      AuditLogWriter auditLogWriter,
                      @Value("${aiwarden.quota.tool-token-cost:100}") long toolTokenCost) {
        this.budgetService = budgetService;
        this.quotaService = quotaService;
        this.meterRegistry = meterRegistry;
        this.auditLogWriter = auditLogWriter;
        this.toolTokenCost = toolTokenCost;
    }

    /** 工具调用的演示当量（写入明细 prompt_tokens 的口径，与配额扣减一一对应）。 */
    public long toolTokenCost() {
        return toolTokenCost;
    }

    /**
     * 工具调用预扣减。
     *
     * @return true = 配额已激活（预扣入账，调用方成功后应 settle、失败/驳回应 release）；
     *         false = 未配置预算（不启用配额检查）
     * @throws QuotaExceededException 超限（指标 + 审计已写）
     */
    public boolean reserveForToolInvocation(long tenantId, String requestKey, long tokens) {
        String period = QuotaService.currentPeriod();
        Optional<Long> limit = budgetService.find(tenantId, period).map(BudgetService.BudgetRow::tokenLimit);
        if (limit.isEmpty()) {
            return false;
        }
        QuotaService.QuotaOutcome outcome = quotaService.reserve(tenantId, period, requestKey, tokens, limit.get());
        if (!outcome.allowed()) {
            meterRegistry.counter("aiwarden_budget_reject_total").increment();
            String detail = "账期 %s 配额超限：已用 %d + 预扣 %d > 上限 %d"
                    .formatted(period, outcome.usedAfter(), tokens, limit.get());
            auditLogWriter.append(tenantId, PrincipalContext.requireUserId(),
                    "QUOTA_EXCEEDED", requestKey, "DENIED", detail);
            throw new QuotaExceededException("配额超限：" + detail);
        }
        return true;
    }

    /** 结算（成功路径）：差额校正为实际当量。 */
    public void settleToolInvocation(long tenantId, String requestKey, long actualTokens) {
        quotaService.settle(tenantId, QuotaService.currentPeriod(), requestKey, actualTokens);
    }

    /** 释放（业务失败 / 驳回路径）：归还预扣；无预扣时安全 no-op。 */
    public void releaseToolInvocation(long tenantId, String requestKey) {
        quotaService.release(tenantId, QuotaService.currentPeriod(), requestKey);
    }
}
