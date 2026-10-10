package com.aiwarden.agent.orchestration;

import com.aiwarden.agent.invocation.ToolInvocationService;
import com.aiwarden.agent.tools.ToolCatalog;
import com.aiwarden.common.exception.QuotaExceededException;
import com.aiwarden.common.exception.RateLimitExceededException;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.contract.chat.ChatEvents;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelChatResult;
import com.aiwarden.core.spi.ModelClient;
import com.aiwarden.core.spi.ModelToolCall;
import com.aiwarden.core.spi.ModelToolSpec;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.governance.visibility.VisibilitySetCalculator;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 问答编排内核（M3 / ADR-012）：确定性管线，治理管道全真走。
 *
 * <p><b>链路</b>：会话初始化 → 可见集计算（真）→ 检索下推（真）→ Prompt 组装 →
 * 模型生成（SPI，替身 / 真实适配器）→ 工具调用（真 P3 管道：白名单/限流/幂等/配额/审批）→
 * 计量事件（真 Outbox）→ 成本汇总。除「模型生成」外每一步都是 M1/M2 已交付的真实组件。
 *
 * <p><b>outcome 产生规则</b>（ADR-012 决策 7）：`deny` = SecurityException（可见集空 /
 * 工具不可见）/ QuotaExceededException / RateLimitExceededException（治理拒绝，审计由下层组件写）；
 * `human_handoff` = 工具 PENDING_APPROVAL；其余 = `answer`。**deny 是治理行为，不是替身台词。**
 *
 * <p><b>取消语义</b>（FR-RET-01 薄）：「客户端断连 → 取消标志」由传输层（SSE sink 写失败 /
 * emitter 回调）置位；模型流中停止向 sink 投递，模型完成后当前实现以检查点终止后续步骤
 * （**工具绝不执行**——副作用保护优先）。真实上游的流式中断留待真实适配器（ADR-012 代价段）。
 *
 * <p><b>线程与事务</b>：本类不做整体事务——检索 / 工具调用 / 计量各自携带事务边界（在组件内）。
 * SSE 端点在新线程执行时必须先 {@code TenantContext.snapshot()} → apply（ADR-003）。
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    /** 模型生成步骤号（工具幂等键的步骤指纹成分；同一请求重放命中同一键——ADR-012 决策 8）。 */
    static final int MODEL_STEP_NO = 1;

    /** 工具调用步骤号（M3 单工具决策场景下全部工具调用共用；多工具并行编排归 M4）。 */
    static final int TOOL_STEP_NO = 2;

    private final VisibilitySetCalculator visibilitySetCalculator;
    private final RetrievalService retrievalService;
    private final ToolInvocationService toolInvocationService;
    private final ModelClient modelClient;
    private final ChatMeteringWriter meteringWriter;
    private final MeterRegistry meterRegistry;
    private final int topK;
    private final BigDecimal promptCostPer1k;
    private final BigDecimal completionCostPer1k;

    public ChatOrchestrator(VisibilitySetCalculator visibilitySetCalculator,
                            RetrievalService retrievalService,
                            ToolInvocationService toolInvocationService,
                            ModelClient modelClient,
                            ChatMeteringWriter meteringWriter,
                            MeterRegistry meterRegistry,
                            @Value("${aiwarden.chat.top-k:5}") int topK,
                            @Value("${aiwarden.chat.cost.prompt-per-1k:0.002}") double promptCostPer1k,
                            @Value("${aiwarden.chat.cost.completion-per-1k:0.006}") double completionCostPer1k) {
        this.visibilitySetCalculator = visibilitySetCalculator;
        this.retrievalService = retrievalService;
        this.toolInvocationService = toolInvocationService;
        this.modelClient = modelClient;
        this.meteringWriter = meteringWriter;
        this.meterRegistry = meterRegistry;
        this.topK = topK;
        this.promptCostPer1k = BigDecimal.valueOf(promptCostPer1k);
        this.completionCostPer1k = BigDecimal.valueOf(completionCostPer1k);
    }

    /**
     * 执行一次问答编排。
     *
     * @param request 问答请求（身份从 {@link TenantContext} / {@link PrincipalContext} 取——缺失即拒绝）
     * @param sink    事件出口（SSE 实现 / 测试收集实现）
     * @param aborted 取消标志（传输层置位；编排在投递点与步骤间检查）
     */
    public OrchestrationResult chat(ChatAskRequest request, ChatStreamSink sink, AtomicBoolean aborted) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        long tenantId = TenantContext.requireTenantIdAsLong();
        long userId = PrincipalContext.requireUserIdAsLong();
        Long orgId = PrincipalContext.orgIdAsLong();
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId() : UUID.randomUUID().toString();

        // 取消检查点①（入口）：请求到达时客户端已断开——不做任何事（无检索、无模型、无计量）
        if (aborted.get()) {
            meterRegistry.counter("aiwarden_chat_aborted_total").increment();
            meterRegistry.counter("aiwarden_chat_total", "outcome", OrchestrationResult.ABORTED).increment();
            return new OrchestrationResult(sessionId, OrchestrationResult.ABORTED,
                    "客户端断连：请求取消（未执行任何步骤）", List.of(), List.of(),
                    0, 0, BigDecimal.ZERO, List.of());
        }

        List<OrchestrationResult.StepRecord> timeline = new ArrayList<>();
        List<RetrievalHit> citations = new ArrayList<>();
        List<OrchestrationResult.ToolOutcome> tools = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        long modelLatencyMs = 0;

        try {
            // ① 可见集计算（空集不在此拒绝——由检索入口统一执行，保证任何调用方不可绕过，ADR-008）
            VisibilitySet visible = timed(sink, timeline, "visibility",
                    () -> visibilitySetCalculator.calculate(tenantId, userId, orgId, request.kbId()),
                    set -> "kbIds=" + set.kbIds().size());

            // ② 检索下推（空可见集 / 租户不一致在此抛 SecurityException → deny）
            List<RetrievalHit> hits = timed(sink, timeline, "retrieval",
                    () -> retrievalService.search(visible, request.message(), topK),
                    h -> "hits=" + h.size());
            for (RetrievalHit hit : hits) {
                sink.citation(new ChatEvents.Citation(hit.docId(), hit.chunkId(),
                        hit.content(), 1.0 - hit.distance()));
            }
            citations.addAll(hits);

            // ③ Prompt 组装（片段来自真实下推检索；工具规格 = 白名单可见面，FR-PERM-03）
            ModelChatRequest modelRequest = timed(sink, timeline, "prompt",
                    () -> buildModelRequest(hits, request.message()),
                    r -> "snippets=" + hits.size() + ", tools=" + r.tools().size());

            // ④ 模型生成（流式；首 token 计时为 FR-RET-01 的口径探针）
            AtomicBoolean firstToken = new AtomicBoolean(true);
            long modelStartNanos = System.nanoTime();
            ModelChatResult modelResult = modelClient.chat(modelRequest, token -> {
                if (firstToken.compareAndSet(true, false)) {
                    meterRegistry.timer("aiwarden_chat_first_token_seconds")
                            .record(System.nanoTime() - modelStartNanos, TimeUnit.NANOSECONDS);
                }
                if (!aborted.get()) {
                    sink.token(new ChatEvents.Token(token));
                }
            });
            modelLatencyMs = (System.nanoTime() - modelStartNanos) / 1_000_000;
            promptTokens = modelResult.promptTokens();
            completionTokens = modelResult.completionTokens();
            appendStep(sink, timeline, "model", modelStartNanos,
                    "promptTokens=%d, completionTokens=%d".formatted(promptTokens, completionTokens));

            // ⑤ 计量（模型调用已完成、tokens 已产生——断连取消照记，计费不因传输结局消失）
            long meteringStartNanos = System.nanoTime();
            meteringWriter.recordModelCall(tenantId, sessionId, MODEL_STEP_NO, modelClient.model(),
                    promptTokens, completionTokens, (int) modelLatencyMs);
            appendStep(sink, timeline, "metering", meteringStartNanos,
                    "event=" + MeteringEventTypes.CALL_RECORDED);

            // ⑥ 取消检查点：客户端断连 → 终止（工具绝不执行——副作用保护优先于回答完整性）
            if (aborted.get()) {
                meterRegistry.counter("aiwarden_chat_aborted_total").increment();
                meterRegistry.counter("aiwarden_chat_total", "outcome", OrchestrationResult.ABORTED).increment();
                return new OrchestrationResult(sessionId, OrchestrationResult.ABORTED,
                        "客户端断连：后续步骤取消（工具未执行）", citations, tools,
                        promptTokens, completionTokens, BigDecimal.ZERO, timeline);
            }

            // ⑦ 工具调用（模型决策 → 真实 P3 管道）
            String outcome = ChatEvents.Outcome.ANSWER;
            String reason = null;
            if (!modelResult.toolCalls().isEmpty()) {
                ToolPhaseResult phase = invokeTools(modelResult.toolCalls(),
                        resolveBusinessKey(request, sessionId), sessionId, sink, timeline, tools);
                outcome = phase.outcome();
                reason = phase.reason();
            }

            // ⑧ 成本与结局
            BigDecimal cost = computeCost(promptTokens, completionTokens);
            sink.cost(new ChatEvents.Cost(promptTokens, completionTokens, cost, modelLatencyMs));
            sink.outcome(new ChatEvents.Outcome(outcome, reason));
            sink.done(new ChatEvents.Done(sessionId));
            meterRegistry.counter("aiwarden_chat_total", "outcome", outcome).increment();
            return new OrchestrationResult(sessionId, outcome, reason, citations, tools,
                    promptTokens, completionTokens, cost, timeline);
        } catch (SecurityException | QuotaExceededException | RateLimitExceededException e) {
            // 治理拒绝：检索入口（可见集空 / 租户不一致）——工具阶段的同类拒绝已在工具循环内消化
            return deny(sink, sessionId, "治理拒绝：" + e.getMessage(), citations, tools,
                    promptTokens, completionTokens, modelLatencyMs, timeline);
        } catch (RuntimeException e) {
            log.warn("编排内部错误（会话 {}）：{}", sessionId, e.getMessage(), e);
            return deny(sink, sessionId, "编排内部错误：" + e.getMessage(), citations, tools,
                    promptTokens, completionTokens, modelLatencyMs, timeline);
        }
    }

    /** 工具阶段：逐个经 P3 管道（白名单 → 限流 → 幂等 → 配额 → 审批）；拒绝即返回 deny。 */
    private ToolPhaseResult invokeTools(List<ModelToolCall> toolCalls, String businessKey, String sessionId,
                                        ChatStreamSink sink, List<OrchestrationResult.StepRecord> timeline,
                                        List<OrchestrationResult.ToolOutcome> outcomes) {
        for (ModelToolCall call : toolCalls) {
            long startNanos = System.nanoTime();
            try {
                ToolInvokeResponse resp = toolInvocationService.invoke(call.toolName(),
                        new ToolInvokeRequest(businessKey, sessionId, TOOL_STEP_NO, call.arguments()));
                outcomes.add(new OrchestrationResult.ToolOutcome(
                        resp.tool(), resp.status(), resp.replayed(), resp.invocationId(), resp.error()));
                sink.tool(new ChatEvents.Tool(resp.tool(), resp.status(), resp.replayed(),
                        resp.invocationId(), resp.error()));
                appendStep(sink, timeline, "tool", startNanos,
                        "tool=%s;status=%s;replayed=%s".formatted(resp.tool(), resp.status(), resp.replayed()));
                if ("PENDING_APPROVAL".equals(resp.status())) {
                    return new ToolPhaseResult(ChatEvents.Outcome.HUMAN_HANDOFF,
                            "写操作工具 " + resp.tool() + " 需人工确认（invocationId=" + resp.invocationId() + "）");
                }
                // SUCCEEDED / FAILED 均继续：FAILED 已走补偿计划（对用户为 answer，DB 终态由断言覆盖）
            } catch (SecurityException e) {
                appendStep(sink, timeline, "tool", startNanos,
                        "tool=%s;DENIED".formatted(call.toolName()));
                return new ToolPhaseResult(ChatEvents.Outcome.DENY,
                        "工具调用被治理拒绝（FR-PERM-03 可见面 / 未注册）：" + e.getMessage());
            } catch (QuotaExceededException | RateLimitExceededException e) {
                appendStep(sink, timeline, "tool", startNanos,
                        "tool=%s;DENIED".formatted(call.toolName()));
                return new ToolPhaseResult(ChatEvents.Outcome.DENY,
                        "工具调用被治理拒绝（配额 / 限流）：" + e.getMessage());
            }
        }
        return ToolPhaseResult.ANSWER;
    }

    /** Prompt 组装（system 含检索片段；工具规格只含业务字段——幂等键由治理层计算）。 */
    private ModelChatRequest buildModelRequest(List<RetrievalHit> hits, String userMessage) {
        StringBuilder system = new StringBuilder();
        system.append("你是 AIWarden 演示助手（数据面治理组件的问答链路）。\n");
        system.append("回答约束：仅基于下方【知识库片段】回答；引用以 [序号] 标注；不得声称执行未提供的操作。\n");
        system.append("【知识库片段】\n");
        if (hits.isEmpty()) {
            system.append("（无命中）\n");
        } else {
            int seq = 1;
            for (RetrievalHit hit : hits) {
                system.append('[').append(seq++).append("] doc=").append(hit.docId())
                        .append(" chunk=").append(hit.chunkId()).append('\n')
                        .append(hit.content()).append('\n');
            }
        }
        List<ModelToolSpec> tools = toolInvocationService.visibleTools().stream()
                .map(name -> new ModelToolSpec(name, ToolCatalog.description(name),
                        ToolCatalog.businessSchema(name)))
                .toList();
        return new ModelChatRequest(system.toString(), userMessage, tools);
    }

    /** 业务键缺省 = 会话 + 消息指纹：同一请求重放捕获为同一逻辑工具调用（ADR-012 决策 8）。 */
    private String resolveBusinessKey(ChatAskRequest request, String sessionId) {
        if (StringUtils.hasText(request.businessKey())) {
            return request.businessKey();
        }
        return "chat-" + sha256Hex16(sessionId + "|" + request.message());
    }

    /** 演示单价口径的成本（元；真实价目表接入后替换，ADR-012「代价与放弃」）。 */
    private BigDecimal computeCost(int promptTokens, int completionTokens) {
        return BigDecimal.valueOf(promptTokens).multiply(promptCostPer1k)
                .add(BigDecimal.valueOf(completionTokens).multiply(completionCostPer1k))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    /** deny 出口：模型已发生（tokens > 0）则成本照报——成本的产生不因结局而消失。 */
    private OrchestrationResult deny(ChatStreamSink sink, String sessionId, String reason,
                                     List<RetrievalHit> citations, List<OrchestrationResult.ToolOutcome> tools,
                                     int promptTokens, int completionTokens, long modelLatencyMs,
                                     List<OrchestrationResult.StepRecord> timeline) {
        BigDecimal cost = BigDecimal.ZERO;
        if (promptTokens > 0 || completionTokens > 0) {
            cost = computeCost(promptTokens, completionTokens);
            sink.cost(new ChatEvents.Cost(promptTokens, completionTokens, cost, modelLatencyMs));
        }
        sink.outcome(new ChatEvents.Outcome(ChatEvents.Outcome.DENY, reason));
        sink.done(new ChatEvents.Done(sessionId));
        meterRegistry.counter("aiwarden_chat_total", "outcome", ChatEvents.Outcome.DENY).increment();
        return new OrchestrationResult(sessionId, ChatEvents.Outcome.DENY, reason, citations, tools,
                promptTokens, completionTokens, cost, timeline);
    }

    private <T> T timed(ChatStreamSink sink, List<OrchestrationResult.StepRecord> timeline,
                        String step, Supplier<T> action, Function<T, String> detailFn) {
        long startNanos = System.nanoTime();
        T result = action.get();
        appendStep(sink, timeline, step, startNanos, detailFn.apply(result));
        return result;
    }

    private void appendStep(ChatStreamSink sink, List<OrchestrationResult.StepRecord> timeline,
                            String step, long startNanos, String detail) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        OrchestrationResult.StepRecord record = new OrchestrationResult.StepRecord(step, elapsedMs, detail);
        timeline.add(record);
        sink.step(new ChatEvents.Step(record.step(), record.elapsedMs(), record.detail()));
    }

    private static String sha256Hex16(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 工具阶段结果（outcome=null 表示无需干预——继续 answer）。 */
    private record ToolPhaseResult(String outcome, String reason) {

        static final ToolPhaseResult ANSWER = new ToolPhaseResult(ChatEvents.Outcome.ANSWER, null);
    }
}
