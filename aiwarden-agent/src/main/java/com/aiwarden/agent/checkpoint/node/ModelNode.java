package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelChatResult;
import com.aiwarden.core.spi.ModelClient;
import com.aiwarden.core.spi.ModelToolSpec;
import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 步骤 5：模型生成（流式；首 token 计时为 FR-RET-01 的口径探针）。
 *
 * <p>token 事件在<b>执行期</b>投递（流式无法延后）；它们不写检查点——
 * 断在生成中途时，续跑会重新调用模型并重新流式输出（此时还没有任何副作用）。
 */
@Component
@Order(5)
public class ModelNode implements AgentNode {

    private final ModelClient modelClient;
    private final MeterRegistry meterRegistry;
    private final TraceSupport traceSupport;

    public ModelNode(ModelClient modelClient, MeterRegistry meterRegistry, TraceSupport traceSupport) {
        this.modelClient = modelClient;
        this.meterRegistry = meterRegistry;
        this.traceSupport = traceSupport;
    }

    @Override
    public String name() {
        return "model";
    }

    @Override
    public int stepNo() {
        return 5;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Model.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        return context.state().require("prompt", AgentNodeOutputs.Prompt.class);
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Prompt prompt = context.state()
                .require("prompt", AgentNodeOutputs.Prompt.class);
        ModelChatRequest request = new ModelChatRequest(prompt.systemPrompt(), prompt.userMessage(),
                prompt.tools().stream()
                        .map(tool -> new ModelToolSpec(tool.name(), tool.description(), tool.parameters()))
                        .toList());

        Span span = traceSupport.startSpan("model.chat");
        span.tag("aiwarden.model", modelClient.model());
        span.tag("aiwarden.tenant_id", String.valueOf(context.tenantId()));
        span.tag("aiwarden.session_id", context.sessionKey());
        AtomicBoolean firstToken = new AtomicBoolean(true);
        long startNanos = System.nanoTime();
        ModelChatResult result;
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(span)) {
            result = modelClient.chat(request, token -> {
                if (firstToken.compareAndSet(true, false)) {
                    meterRegistry.timer("aiwarden_chat_first_token_seconds")
                            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                }
                // 断连后不再投递；模型仍跑完（tokens 照常计量，ADR-012 取消语义）
                if (!context.aborted()) {
                    context.token(token);
                }
            });
        } finally {
            traceSupport.end(span);
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new AgentNodeOutputs.Model(modelClient.model(), result.text(),
                result.toolCalls().stream()
                        .map(call -> new AgentNodeOutputs.ToolCall(call.toolName(), call.arguments()))
                        .toList(),
                result.promptTokens(), result.completionTokens(), latencyMs);
    }

    @Override
    public String detail(AgentRunContext context, Object output) {
        AgentNodeOutputs.Model model = (AgentNodeOutputs.Model) output;
        return "promptTokens=%d, completionTokens=%d"
                .formatted(model.promptTokens(), model.completionTokens());
    }
}
