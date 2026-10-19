package com.aiwarden.agent.checkpoint;

import com.aiwarden.agent.orchestration.ChatStreamSink;
import com.aiwarden.agent.orchestration.OrchestrationResult;
import com.aiwarden.common.exception.ConflictException;
import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.common.exception.QuotaExceededException;
import com.aiwarden.common.exception.RateLimitExceededException;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.contract.chat.ChatEvents;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 确定性 DAG 执行引擎（FR-CKPT-01/02/03/04）：每步执行 → 持久化检查点 → 事件投递。
 *
 * <p><b>恢复语义</b>：续跑从 {@code t_agent_session.last_step_no} 之后继续；已提交步骤的输出
 * 从 {@code t_agent_step.output} 反序列化重建（新实例，不复用旧对象）。未提交步骤重做——
 * 工具副作用由 P3 幂等键兜底；计量事件带 {@code dedupKey}，消费端唯一键仲裁。所以
 * 「崩溃在副作用之后、检查点之前」不会产生第二张工单，也不会重复计费。
 *
 * <p><b>崩溃注入</b>：{@code aiwarden.agent.checkpoint.crash-before-checkpoint=<node>} 让该节点动作
 * 完成后、检查点提交前 {@code Runtime.halt(137)}——与 `kill -9` 同样是「无关闭钩子、无 flush」的
 * 进程终止，测试用它在确定的位置复现「副作用已发生、检查点未落」的窗口。
 *
 * <p><b>事务边界</b>：节点执行不包在数据库事务里（模型调用可能很久）；检查点提交是独立短事务。
 * 这正是「崩溃窗口」存在的原因，也是必须靠幂等（而不是事务）保证不重复副作用的原因。
 */
@Service
public class AgentDagEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentDagEngine.class);

    /** 实例标识（租约持有者）；进程重启即变化——崩溃实例的租约不会被新实例误认。 */
    private static final String OWNER = "node-" + UUID.randomUUID().toString().substring(0, 8);

    private final List<AgentNode> nodes;
    private final Map<Integer, AgentNode> nodesByStep = new HashMap<>();
    private final AgentCheckpointStore store;
    private final ObjectMapper objectMapper;
    private final TraceSupport traceSupport;
    private final MeterRegistry meterRegistry;
    private final String crashBeforeCheckpoint;
    private final BigDecimal promptCostPer1k;
    private final BigDecimal completionCostPer1k;

    public AgentDagEngine(List<AgentNode> nodes,
                          AgentCheckpointStore store,
                          ObjectMapper objectMapper,
                          TraceSupport traceSupport,
                          MeterRegistry meterRegistry,
                          @Value("${aiwarden.agent.checkpoint.crash-before-checkpoint:}") String crashBeforeCheckpoint,
                          @Value("${aiwarden.chat.cost.prompt-per-1k:0.002}") double promptCostPer1k,
                          @Value("${aiwarden.chat.cost.completion-per-1k:0.006}") double completionCostPer1k) {
        this.nodes = nodes.stream().sorted(Comparator.comparingInt(AgentNode::stepNo)).toList();
        for (AgentNode node : this.nodes) {
            nodesByStep.put(node.stepNo(), node);
        }
        this.store = store;
        this.objectMapper = objectMapper;
        this.traceSupport = traceSupport;
        this.meterRegistry = meterRegistry;
        this.crashBeforeCheckpoint = crashBeforeCheckpoint == null ? "" : crashBeforeCheckpoint.trim();
        this.promptCostPer1k = BigDecimal.valueOf(promptCostPer1k);
        this.completionCostPer1k = BigDecimal.valueOf(completionCostPer1k);
    }

    /**
     * 执行一次问答（新建 run）。
     *
     * @param request 问答请求（身份从 {@link TenantContext} / {@link PrincipalContext} 取——缺失即拒绝）
     * @param sink    事件出口（SSE 实现 / 测试收集实现）
     * @param aborted 取消标志（传输层置位）
     */
    public OrchestrationResult run(ChatAskRequest request, ChatStreamSink sink,
                                   java.util.concurrent.atomic.AtomicBoolean aborted) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        long tenantId = TenantContext.requireTenantIdAsLong();
        long userId = PrincipalContext.requireUserIdAsLong();
        Long orgId = PrincipalContext.orgIdAsLong();
        String sessionKey = StringUtils.hasText(request.sessionId())
                ? request.sessionId() : UUID.randomUUID().toString();
        String businessKey = BusinessKeys.resolve(request, sessionKey);

        // 取消检查点①（入口）：请求到达时客户端已断开——不建 run、不做任何事
        if (aborted.get()) {
            meterRegistry.counter("aiwarden_chat_aborted_total").increment();
            meterRegistry.counter("aiwarden_chat_total", "outcome", OrchestrationResult.ABORTED).increment();
            return new OrchestrationResult(sessionKey, OrchestrationResult.ABORTED,
                    "客户端断连：请求取消（未执行任何步骤）", List.of(), List.of(),
                    0, 0, BigDecimal.ZERO, List.of());
        }

        Span root = traceSupport.startSpan("agent.run");
        root.tag("aiwarden.tenant_id", String.valueOf(tenantId));
        root.tag("aiwarden.user_id", String.valueOf(userId));
        root.tag("aiwarden.session_id", sessionKey);
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root)) {
            String traceId = traceSupport.traceIdOf(root);
            String traceParent = traceSupport.currentTraceParent();
            long runId = store.createRun(tenantId, userId, orgId, sessionKey,
                    objectMapper.writeValueAsString(request), OWNER, traceId, traceParent);
            AgentRunContext context = new AgentRunContext(request, runId, sessionKey,
                    tenantId, userId, orgId, businessKey, sink, aborted, new AgentState());
            return execute(context, 0, 0);
        } finally {
            traceSupport.end(root);
        }
    }

    /**
     * 从最近检查点续跑该会话最新的 RUNNING / ABORTED 运行（FR-CKPT-02）。
     *
     * @throws NotFoundException  没有可续跑的运行
     * @throws SecurityException  运行不属于当前主体（不泄露存在性以外的东西）
     * @throws ConflictException  租约仍被其他实例持有 / 版本冲突（过期实例必须丢弃）
     */
    public OrchestrationResult resume(String sessionKey, ChatStreamSink sink,
                                      java.util.concurrent.atomic.AtomicBoolean aborted) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        long userId = PrincipalContext.requireUserIdAsLong();
        AgentCheckpointStore.RunRow run = store.findLatestResumable(tenantId, sessionKey)
                .orElseThrow(() -> new NotFoundException("没有可续跑的运行：" + sessionKey));
        if (run.userId() != userId) {
            throw new SecurityException("运行不属于当前主体：拒绝续跑");
        }
        if (!store.tryClaimLease(tenantId, run.id(), OWNER)) {
            meterRegistry.counter("aiwarden_agent_checkpoint_lease_conflict_total").increment();
            throw new ConflictException(
                    "运行正在被其他实例执行（租约未过期）：请稍后重试（runId=" + run.id() + "）");
        }

        ChatAskRequest request = objectMapper.readValue(run.request(), ChatAskRequest.class);
        String businessKey = BusinessKeys.resolve(request, sessionKey);
        AgentState state = new AgentState();
        List<AgentCheckpointStore.StepRow> steps = store.findSteps(tenantId, run.id());

        Span root = traceSupport.startSpan("agent.resume", run.traceParent());
        root.tag("aiwarden.tenant_id", String.valueOf(tenantId));
        root.tag("aiwarden.run_id", String.valueOf(run.id()));
        root.tag("aiwarden.session_id", sessionKey);
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root)) {
            AgentRunContext context = new AgentRunContext(request, run.id(), sessionKey,
                    tenantId, userId, run.orgId(), businessKey, sink, aborted, state);
            for (AgentCheckpointStore.StepRow step : steps) {
                AgentNode node = requireNode(step.stepNo());
                if (step.output() != null) {
                    state.put(node.name(), objectMapper.readValue(step.output(), node.outputType()));
                }
                context.restoreStep(step.name(), step.elapsedMs(), step.detail());
            }
            meterRegistry.counter("aiwarden_agent_resume_total", "from_step",
                    String.valueOf(run.lastStepNo())).increment();
            return execute(context, run.lastStepNo(), run.version());
        } finally {
            traceSupport.end(root);
        }
    }

    /** 从 {@code lastStepNo} 之后执行（新 run 传 0 / 版本 0）。 */
    private OrchestrationResult execute(AgentRunContext context, int lastStepNo, int expectedVersion) {
        int version = expectedVersion;
        for (AgentNode node : nodes) {
            if (node.stepNo() <= lastStepNo) {
                continue;
            }
            long startNanos = System.nanoTime();
            Span span = traceSupport.startSpan("agent.node." + node.name());
            span.tag("aiwarden.tenant_id", String.valueOf(context.tenantId()));
            span.tag("aiwarden.session_id", context.sessionKey());
            span.tag("aiwarden.run_id", String.valueOf(context.runId()));
            span.tag("aiwarden.step_no", String.valueOf(node.stepNo()));
            Object output;
            long elapsedMs;
            String inputJson;
            String outputJson;
            String detail;
            AgentCheckpointStore.StepRow step;
            try {
                try (Tracer.SpanInScope ignored = traceSupport.withSpan(span)) {
                    output = node.execute(context);
                }
            } catch (RuntimeException e) {
                span.error(e);
                traceSupport.end(span);
                return handleFailure(context, version, e);
            }
            elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            try {
                for (Map.Entry<String, Object> attribute : node.attributes(context, output).entrySet()) {
                    if (attribute.getValue() != null) {
                        span.tag(attribute.getKey(), String.valueOf(attribute.getValue()));
                    }
                }
                inputJson = json(node.input(context));
                outputJson = json(output);
                detail = node.detail(context, output);
                if (detail != null && detail.length() > 500) {
                    detail = detail.substring(0, 500);
                }
                // 崩溃注入点：动作已完成（可能已产生副作用），检查点尚未提交
                if (!crashBeforeCheckpoint.isEmpty() && crashBeforeCheckpoint.equals(node.name())) {
                    log.warn("测试崩溃注入：节点 {} 动作完成、检查点提交前 halt(137)", node.name());
                    traceSupport.end(span);
                    Runtime.getRuntime().halt(137);
                }
                step = store.completeStep(context.tenantId(), context.runId(), version,
                        node.stepNo(), node.name(), inputJson, outputJson, detail, elapsedMs,
                        traceSupport.traceIdOf(span));
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                traceSupport.end(span);
            }
            version = step.version();
            context.state().put(node.name(), output);
            if (node.emitsStepEvent(context, output)) {
                context.recordStep(node.name(), elapsedMs, detail);
            }
            node.afterCheckpoint(context, output);

            // 取消检查点②（M3 语义）：模型与计量都已完成，工具绝不因断连执行
            if (node.stepNo() == 6 && context.aborted()) {
                store.abortRun(context.tenantId(), context.runId(), version,
                        "客户端断连：后续步骤取消（工具未执行）");
                meterRegistry.counter("aiwarden_chat_aborted_total").increment();
                meterRegistry.counter("aiwarden_chat_total", "outcome", OrchestrationResult.ABORTED).increment();
                return abortedResult(context, "客户端断连：后续步骤取消（工具未执行）");
            }
        }

        AgentNodeOutputs.Outcome outcome = context.state()
                .require("outcome", AgentNodeOutputs.Outcome.class);
        store.finishRun(context.tenantId(), context.runId(), version,
                outcome.outcome(), outcome.reason());
        meterRegistry.counter("aiwarden_chat_total", "outcome", outcome.outcome()).increment();
        return successResult(context, outcome);
    }

    /** 节点异常 → 治理拒绝 / 内部错误：已发生 tokens 的成本照报，终态落库（不可续跑）。 */
    private OrchestrationResult handleFailure(AgentRunContext context, int version, RuntimeException e) {
        String reason;
        String status;
        if (e instanceof SecurityException || e instanceof QuotaExceededException
                || e instanceof RateLimitExceededException) {
            status = AgentRunStatus.DENIED;
            reason = "治理拒绝：" + e.getMessage();
        } else {
            status = AgentRunStatus.FAILED;
            reason = "编排内部错误：" + e.getMessage();
            log.warn("编排内部错误（会话 {}）：{}", context.sessionKey(), e.getMessage(), e);
        }

        int currentVersion = version;
        BigDecimal cost = BigDecimal.ZERO;
        if (context.state().has("model") && !context.state().has("cost")) {
            AgentNodeOutputs.Model model = context.state()
                    .require("model", AgentNodeOutputs.Model.class);
            cost = ChatCost.compute(model.promptTokens(), model.completionTokens(),
                    promptCostPer1k, completionCostPer1k);
            AgentNodeOutputs.Cost costOutput = new AgentNodeOutputs.Cost(model.promptTokens(),
                    model.completionTokens(), cost, model.latencyMs(),
                    ChatEvents.Outcome.DENY, reason);
            currentVersion = persistStep(context, 8, "cost", costOutput, currentVersion);
            context.state().put("cost", costOutput);
            context.cost(costOutput);
        } else if (context.state().has("cost")) {
            cost = context.state().require("cost", AgentNodeOutputs.Cost.class).cost();
        }

        if (!context.state().has("outcome")) {
            AgentNodeOutputs.Outcome outcome = new AgentNodeOutputs.Outcome(ChatEvents.Outcome.DENY, reason);
            currentVersion = persistStep(context, 9, "outcome", outcome, currentVersion);
            context.state().put("outcome", outcome);
        }
        context.outcome(context.state().require("outcome", AgentNodeOutputs.Outcome.class));
        store.failRun(context.tenantId(), context.runId(), currentVersion, status,
                ChatEvents.Outcome.DENY, reason);
        meterRegistry.counter("aiwarden_chat_total", "outcome", ChatEvents.Outcome.DENY).increment();
        return new OrchestrationResult(context.sessionKey(), ChatEvents.Outcome.DENY, reason,
                citations(context), tools(context), tokens(context).promptTokens(),
                tokens(context).completionTokens(), cost, context.timeline());
    }

    private int persistStep(AgentRunContext context, int stepNo, String name, Object output, int version) {
        return store.completeStep(context.tenantId(), context.runId(), version, stepNo, name,
                null, json(output), "", 0, traceSupport.currentTraceId()).version();
    }

    private OrchestrationResult successResult(AgentRunContext context, AgentNodeOutputs.Outcome outcome) {
        AgentNodeOutputs.Model model = tokens(context);
        BigDecimal cost = context.state().has("cost")
                ? context.state().require("cost", AgentNodeOutputs.Cost.class).cost()
                : BigDecimal.ZERO;
        return new OrchestrationResult(context.sessionKey(), outcome.outcome(), outcome.reason(),
                citations(context), tools(context), model.promptTokens(), model.completionTokens(),
                cost, context.timeline());
    }

    private OrchestrationResult abortedResult(AgentRunContext context, String reason) {
        AgentNodeOutputs.Model model = tokens(context);
        return new OrchestrationResult(context.sessionKey(), OrchestrationResult.ABORTED, reason,
                citations(context), tools(context), model.promptTokens(), model.completionTokens(),
                BigDecimal.ZERO, context.timeline());
    }

    private AgentNodeOutputs.Model tokens(AgentRunContext context) {
        if (!context.state().has("model")) {
            return new AgentNodeOutputs.Model(null, null, List.of(), 0, 0, 0);
        }
        return context.state().require("model", AgentNodeOutputs.Model.class);
    }

    private List<RetrievalHit> citations(AgentRunContext context) {
        if (!context.state().has("retrieval")) {
            return List.of();
        }
        List<RetrievalHit> citations = new ArrayList<>();
        for (AgentNodeOutputs.Hit hit : context.state()
                .require("retrieval", AgentNodeOutputs.Retrieval.class).hits()) {
            citations.add(new RetrievalHit(hit.chunkId(), hit.docId(), hit.content(), hit.distance()));
        }
        return citations;
    }

    private List<OrchestrationResult.ToolOutcome> tools(AgentRunContext context) {
        if (!context.state().has("tool")) {
            return List.of();
        }
        List<OrchestrationResult.ToolOutcome> outcomes = new ArrayList<>();
        for (AgentNodeOutputs.ToolOutcome outcome : context.state()
                .require("tool", AgentNodeOutputs.ToolPhase.class).outcomes()) {
            outcomes.add(new OrchestrationResult.ToolOutcome(outcome.tool(), outcome.status(),
                    outcome.replayed(), outcome.invocationId(), outcome.error()));
        }
        return outcomes;
    }

    private AgentNode requireNode(int stepNo) {
        AgentNode node = nodesByStep.get(stepNo);
        if (node == null) {
            throw new IllegalStateException("检查点含未知步骤：" + stepNo);
        }
        return node;
    }

    private String json(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }
}
