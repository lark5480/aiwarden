package com.aiwarden.agent.api;

import com.aiwarden.agent.checkpoint.AgentCheckpointStore;
import com.aiwarden.agent.checkpoint.AgentDagEngine;
import com.aiwarden.agent.orchestration.ChatStreamSink;
import com.aiwarden.agent.orchestration.OrchestrationResult;
import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.AgentRunView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 运行续跑与检查点查询（M4 / P5；FR-CKPT-02/04）。
 *
 * <p><b>为什么不是 SSE</b>：崩溃 / 断连之后原连接已经不存在，续跑的观察者是「重新连上来的人」。
 * 这里返回一次性的 JSON 快照（结局 + 完整时间线 + 成本），把「续跑结果」与「实时流」分开——
 * 前者要的是确定性，后者要的是逐 token 体验。
 *
 * <p><b>身份</b>：租户 + 主体双必填（与 /admin/** 同一口径）；运行还要与发起时的 user_id 一致，
 * 否则 403（不泄露他人运行的状态）。
 */
@RestController
@RequestMapping("/api/v1/agent/runs")
public class AgentRunController {

    private final AgentDagEngine engine;
    private final AgentCheckpointStore store;

    public AgentRunController(AgentDagEngine engine, AgentCheckpointStore store) {
        this.engine = engine;
        this.store = store;
    }

    /** 从最近检查点续跑该会话最新的可续跑运行。 */
    @PostMapping("/{sessionKey}/resume")
    public ResumeResponse resume(@PathVariable String sessionKey) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        PrincipalContext.requireUserId();
        AgentCheckpointStore.RunRow before = store.findLatestResumable(tenantId, sessionKey)
                .orElseThrow(() -> new NotFoundException("没有可续跑的运行：" + sessionKey));
        OrchestrationResult result =
                engine.resume(sessionKey, ChatStreamSink.NOOP, new AtomicBoolean(false));
        AgentCheckpointStore.RunRow after = store.findLatest(tenantId, sessionKey).orElse(before);
        return new ResumeResponse(after.id(), result.sessionId(), before.lastStepNo(),
                after.status(), after.traceId(), result);
    }

    /** 查询该会话最新运行（任意状态）的检查点与 traceId。 */
    @GetMapping("/{sessionKey}")
    public AgentRunView view(@PathVariable String sessionKey) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        long userId = PrincipalContext.requireUserIdAsLong();
        AgentCheckpointStore.RunRow run = store.findLatest(tenantId, sessionKey)
                .orElseThrow(() -> new NotFoundException("运行不存在：" + sessionKey));
        if (run.userId() != userId) {
            throw new SecurityException("运行不属于当前主体：拒绝查询");
        }
        return viewOf(tenantId, run);
    }

    /** 按 traceId 查运行（FR-ADM-03 的 Trace 查询：DB 侧检查点时间线；span 明细在 Tempo）。 */
    @GetMapping("/by-trace/{traceId}")
    public AgentRunView byTrace(@PathVariable String traceId) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        long userId = PrincipalContext.requireUserIdAsLong();
        AgentCheckpointStore.RunRow run = store.findByTraceId(tenantId, traceId)
                .orElseThrow(() -> new NotFoundException("traceId 不存在：" + traceId));
        if (run.userId() != userId) {
            throw new SecurityException("运行不属于当前主体：拒绝查询");
        }
        return viewOf(tenantId, run);
    }

    private AgentRunView viewOf(long tenantId, AgentCheckpointStore.RunRow run) {
        List<AgentRunView.StepView> steps = store.findSteps(tenantId, run.id()).stream()
                .map(step -> new AgentRunView.StepView(step.stepNo(), step.name(), step.status(),
                        step.version(), step.elapsedMs(), step.detail(), step.traceId()))
                .toList();
        return new AgentRunView(run.id(), run.sessionKey(), run.status(), run.lastStepNo(),
                run.version(), run.traceId(), run.outcome(), run.reason(), steps);
    }

    /** 续跑结果（与 SSE 的 done 汇总同源，但不是流式）。 */
    public record ResumeResponse(long runId, String sessionId, int resumedFromStep,
                                 String status, String traceId, OrchestrationResult result) {
    }
}
