package com.aiwarden.agent.compensation;

import com.aiwarden.agent.invocation.ToolInvocationStore;
import com.aiwarden.agent.tools.ToolRegistry;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.CompensationRunResponse;
import com.aiwarden.core.spi.ToolExecutor;
import com.aiwarden.governance.audit.AuditLogWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 补偿执行器（FR-TOOL-02 / ADR-009 决策 4）：**失败走补偿，而不是重试**。
 *
 * <p>两段式：失败调用落库时生成 {@code PENDING} 计划（只算要回滚哪些更早的成功步骤）；
 * 执行由显式触发（{@code POST /api/v1/agent/compensations/run}）——按被补偿调用 id
 * **逆序**执行，每行动作独立落日志（成功 / NO_OP / 失败 + attempt），并写审计（动作顺序可断言）。
 *
 * <p><b>三条防「静默假成功 / 死胡同」的纪律</b>（M2 复核后补）：
 * <ol>
 *   <li><b>受影响行数才是成功</b>：{@link ToolExecutor#compensate} 返回 0 行时记
 *       {@code NO_OP} 并单列计数——不能因为「没抛异常」就记 SUCCEEDED（那会让审计与计数器
 *       都声称撤销成功，而数据库毫无变化）；</li>
 *   <li><b>{@code compensable()} 守卫</b>：计划生成时会过滤不可补偿工具，但已存在的计划
 *       仍可能在装配变更后指向不可补偿工具——执行期再判一次，避免走到 SPI 默认实现
 *       （默认实现抛异常，但提前判能给出更有指向性的失败原因）；</li>
 *   <li><b>FAILED 可重跑</b>：失败的计划在 {@code attempt} 未达上限前可被后续 run 重试，
 *       超过上限的单列 {@code exhausted}——否则一次失败即永久卡在「半补偿」且无人知晓。</li>
 * </ol>
 */
@Service
public class CompensationService {

    private final ToolRegistry toolRegistry;
    private final ToolInvocationStore invocationStore;
    private final CompensationStore compensationStore;
    private final AuditLogWriter auditLogWriter;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;

    public CompensationService(ToolRegistry toolRegistry,
                               ToolInvocationStore invocationStore,
                               CompensationStore compensationStore,
                               AuditLogWriter auditLogWriter,
                               MeterRegistry meterRegistry,
                               @Value("${aiwarden.agent.compensation.max-attempts:3}") int maxAttempts) {
        this.toolRegistry = toolRegistry;
        this.invocationStore = invocationStore;
        this.compensationStore = compensationStore;
        this.auditLogWriter = auditLogWriter;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 生成补偿计划（失败调用触发）：对同会话更早（id &lt; failedInvocationId）的 SUCCEEDED
     * 且工具声明可补偿的调用，各落一条 PENDING（{@code UNIQUE(invocation_id)} 幂等）。
     */
    @Transactional
    public void planFor(long tenantId, String sessionId, long failedInvocationId) {
        for (ToolInvocationStore.InvocationRow row
                : invocationStore.findSuccessesBefore(tenantId, sessionId, failedInvocationId)) {
            toolRegistry.find(row.tool())
                    .filter(ToolExecutor::compensable)
                    .ifPresent(tool -> invocationStore.insertCompensationPlan(
                            tenantId, row.id(), "UNDO:" + row.tool()));
        }
    }

    /** 执行指定会话的可执行补偿计划（逆序；单个失败不阻塞其它动作）。 */
    @Transactional
    public CompensationRunResponse run(String sessionId) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        String actor = PrincipalContext.requireUserId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        List<CompensationStore.PlanRow> plans =
                compensationStore.findExecutable(tenantId, sessionId, maxAttempts);
        int succeeded = 0;
        int noOp = 0;
        int failed = 0;
        for (CompensationStore.PlanRow plan : plans) {
            ToolInvocationStore.InvocationRow invocation =
                    invocationStore.requireById(tenantId, plan.invocationId());
            try {
                ToolExecutor tool = toolRegistry.find(invocation.tool())
                        .orElseThrow(() -> new IllegalStateException(
                                "补偿目标工具未注册：" + invocation.tool()));
                if (!tool.compensable()) {
                    throw new IllegalStateException(
                            "补偿目标工具不可补偿（装配变更 / 脏计划）：" + tool.name());
                }
                int affected = tool.compensate(invocation.input(), invocation.result());
                if (affected > 0) {
                    compensationStore.markSucceeded(plan.id());
                    auditLogWriter.append(tenantId, actor, "TOOL_COMPENSATED",
                            String.valueOf(invocation.id()), "SUCCEEDED",
                            tool.name() + " affected=" + affected);
                    meterRegistry.counter("aiwarden_compensation_total", "result", "succeeded").increment();
                    succeeded++;
                } else {
                    // 补偿执行了但没有效果：单列 NO_OP，绝不计入成功
                    compensationStore.markNoOp(plan.id(), "受影响 0 行：无可撤销的效果");
                    auditLogWriter.append(tenantId, actor, "TOOL_COMPENSATED",
                            String.valueOf(invocation.id()), "NO_OP",
                            tool.name() + " affected=0");
                    meterRegistry.counter("aiwarden_compensation_total", "result", "noop").increment();
                    noOp++;
                }
            } catch (RuntimeException e) {
                compensationStore.markFailed(plan.id(), e.getMessage());
                auditLogWriter.append(tenantId, actor, "TOOL_COMPENSATED",
                        String.valueOf(invocation.id()), "FAILED",
                        invocation.tool() + ": " + e.getMessage());
                meterRegistry.counter("aiwarden_compensation_total", "result", "failed").increment();
                failed++;
            }
        }
        int exhausted = compensationStore.countExhausted(tenantId, sessionId, maxAttempts);
        if (exhausted > 0) {
            meterRegistry.counter("aiwarden_compensation_exhausted_total").increment(exhausted);
        }
        return new CompensationRunResponse(sessionId, plans.size(), succeeded, noOp, failed, exhausted);
    }
}
