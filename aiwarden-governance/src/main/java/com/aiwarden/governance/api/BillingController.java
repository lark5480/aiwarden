package com.aiwarden.governance.api;

import com.aiwarden.contract.governance.BillingReconcileResponse;
import com.aiwarden.contract.governance.BudgetResponse;
import com.aiwarden.contract.governance.BudgetSetRequest;
import com.aiwarden.governance.quota.BillingReconciler;
import com.aiwarden.governance.quota.BudgetService;
import com.aiwarden.governance.quota.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 账单对账与预算管理（FR-COST-05 / P4a，ADR-010）：
 * GET   /api/v1/admin/billing/reconcile  对账：Redis 配额账 vs 明细汇总 + 报告落库
 * GET   /api/v1/admin/budgets/{tenantId} 查询预算（未配置 = 不启用配额检查）
 * PUT   /api/v1/admin/budgets/{tenantId} 设置预算（upsert）
 *
 * <p><b>路径租户必须等于调用方租户</b>：{@code {tenantId}} 是路径参数，与租户上下文是
 * <b>两个独立来源</b>——不校验就等于把「读任意租户预算」与「改写任意租户预算」暴露出去
 * （改大即解除限流、改小即 DoS）。校验失败返回 403（与越权拒绝同语义），不是 404：
 * 本接口是「管理面操作」而非资源读取，调用方本就知道自己在访问哪个租户。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class BillingController {

    private final BillingReconciler billingReconciler;
    private final BudgetService budgetService;

    public BillingController(BillingReconciler billingReconciler, BudgetService budgetService) {
        this.billingReconciler = billingReconciler;
        this.budgetService = budgetService;
    }

    @GetMapping("/billing/reconcile")
    public BillingReconcileResponse reconcile(@RequestParam(required = false) String period) {
        long tenantId = AdminAccess.requireIdentity();
        BillingReconciler.BillingReport report = billingReconciler.reconcile(tenantId, period);
        return new BillingReconcileResponse(report.period(), report.budgetLimit(), report.redisUsage(),
                report.ledgerTokens(), report.mismatch(), report.mismatchRate(), report.reportId());
    }

    @GetMapping("/budgets/{tenantId}")
    public BudgetResponse getBudget(@PathVariable long tenantId,
                                    @RequestParam(required = false) String period) {
        requireOwnTenant(tenantId);
        String effectivePeriod = (period == null || period.isBlank())
                ? QuotaService.currentPeriod() : period;
        Optional<BudgetService.BudgetRow> row = budgetService.find(tenantId, effectivePeriod);
        return new BudgetResponse(tenantId, effectivePeriod,
                row.map(BudgetService.BudgetRow::tokenLimit).orElse(null), row.isPresent());
    }

    @PutMapping("/budgets/{tenantId}")
    public BudgetResponse setBudget(@PathVariable long tenantId, @RequestBody BudgetSetRequest request) {
        requireOwnTenant(tenantId);
        if (request.tokenLimit() == null) {
            throw new IllegalArgumentException("tokenLimit 不能为空");
        }
        String effectivePeriod = (request.period() == null || request.period().isBlank())
                ? QuotaService.currentPeriod() : request.period();
        BudgetService.BudgetRow row = budgetService.set(tenantId, effectivePeriod, request.tokenLimit());
        return new BudgetResponse(row.tenantId(), row.period(), row.tokenLimit(), true);
    }

    /**
     * 路径租户 == 调用方租户，否则拒绝（403）。
     *
     * <p>这是「先查全量再过滤」在写侧的等价反面：<b>租户边界必须在进入业务逻辑之前判定</b>，
     * 不能依赖后续 SQL 恰好带上 tenant_id 条件——{@code BudgetService}/{@code BillingReconciler}
     * 接的就是入参 tenantId，一旦入参可伪造，下游的条件就是错的。
     */
    private static void requireOwnTenant(long pathTenantId) {
        long callerTenantId = AdminAccess.requireIdentity();
        if (pathTenantId != callerTenantId) {
            throw new SecurityException(
                    "路径租户与调用方租户不一致：拒绝访问（租户边界不可由路径参数改写）");
        }
    }
}
