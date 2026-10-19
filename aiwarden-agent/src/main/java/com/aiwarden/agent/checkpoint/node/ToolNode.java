package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.agent.invocation.ToolInvocationService;
import com.aiwarden.common.exception.QuotaExceededException;
import com.aiwarden.common.exception.RateLimitExceededException;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
import com.aiwarden.contract.chat.ChatEvents;
import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤 7：工具调用（真实 P3 管道：白名单 / 限流 / 幂等 / 配额 / 审批）。
 *
 * <p><b>P5 的关键步骤</b>：业务写副作用只发生在这里（计量事件另带 {@code dedupKey} 仲裁）。
 * 崩溃注入点最常见的位置就是「工具已执行、检查点未提交」——续跑会重做本节点，但幂等键
 * （businessKey + session + 步骤指纹）命中 {@code t_tool_invocation} 的 SUCCEEDED 终态，
 * 返回 {@code replayed=true}，工单数不变（FR-CKPT-04）。
 */
@Component
@Order(7)
public class ToolNode implements AgentNode {

    /** 与 M3 一致：工具调用的逻辑步骤号（幂等键指纹成分，不改写）。 */
    public static final int TOOL_STEP_NO = 2;

    private final ToolInvocationService toolInvocationService;
    private final TraceSupport traceSupport;

    public ToolNode(ToolInvocationService toolInvocationService, TraceSupport traceSupport) {
        this.toolInvocationService = toolInvocationService;
        this.traceSupport = traceSupport;
    }

    @Override
    public String name() {
        return "tool";
    }

    @Override
    public int stepNo() {
        return 7;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.ToolPhase.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        AgentNodeOutputs.Model model = context.state().require("model", AgentNodeOutputs.Model.class);
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("sessionKey", context.sessionKey());
        input.put("businessKey", context.businessKey());
        input.put("toolCalls", model.toolCalls());
        return input;
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Model model = context.state().require("model", AgentNodeOutputs.Model.class);
        if (model.toolCalls().isEmpty()) {
            return new AgentNodeOutputs.ToolPhase(List.of(), null, null);
        }
        List<AgentNodeOutputs.ToolOutcome> outcomes = new ArrayList<>();
        for (AgentNodeOutputs.ToolCall call : model.toolCalls()) {
            Span span = traceSupport.startSpan("tool.invoke." + call.toolName());
            span.tag("aiwarden.tool", call.toolName());
            span.tag("aiwarden.tenant_id", String.valueOf(context.tenantId()));
            span.tag("aiwarden.session_id", context.sessionKey());
            try (Tracer.SpanInScope ignored = traceSupport.withSpan(span)) {
                ToolInvokeResponse response = toolInvocationService.invoke(call.toolName(),
                        new ToolInvokeRequest(context.businessKey(), context.sessionKey(),
                                TOOL_STEP_NO, call.arguments()));
                outcomes.add(new AgentNodeOutputs.ToolOutcome(response.tool(), response.status(),
                        response.replayed(), response.invocationId(), response.error()));
                if ("PENDING_APPROVAL".equals(response.status())) {
                    return new AgentNodeOutputs.ToolPhase(outcomes, ChatEvents.Outcome.HUMAN_HANDOFF,
                            "写操作工具 " + response.tool() + " 需人工确认（invocationId="
                                    + response.invocationId() + "）");
                }
                // SUCCEEDED / FAILED 均继续：FAILED 已走补偿计划（对用户为 answer，DB 终态由断言覆盖）
            } catch (SecurityException e) {
                outcomes.add(new AgentNodeOutputs.ToolOutcome(call.toolName(), "DENIED", false,
                        null, e.getMessage()));
                return new AgentNodeOutputs.ToolPhase(outcomes, ChatEvents.Outcome.DENY,
                        "工具调用被治理拒绝（FR-PERM-03 可见面 / 未注册）：" + e.getMessage());
            } catch (QuotaExceededException | RateLimitExceededException e) {
                outcomes.add(new AgentNodeOutputs.ToolOutcome(call.toolName(), "DENIED", false,
                        null, e.getMessage()));
                return new AgentNodeOutputs.ToolPhase(outcomes, ChatEvents.Outcome.DENY,
                        "工具调用被治理拒绝（配额 / 限流）：" + e.getMessage());
            } finally {
                traceSupport.end(span);
            }
        }
        return new AgentNodeOutputs.ToolPhase(outcomes, null, null);
    }

    @Override
    public String detail(AgentRunContext context, Object output) {
        AgentNodeOutputs.ToolPhase phase = (AgentNodeOutputs.ToolPhase) output;
        if (phase.outcomes().isEmpty()) {
            return "";
        }
        StringBuilder detail = new StringBuilder();
        for (AgentNodeOutputs.ToolOutcome outcome : phase.outcomes()) {
            if (!detail.isEmpty()) {
                detail.append(" | ");
            }
            detail.append("tool=").append(outcome.tool())
                    .append(";status=").append(outcome.status())
                    .append(";replayed=").append(outcome.replayed());
        }
        return detail.toString();
    }

    @Override
    public boolean emitsStepEvent(AgentRunContext context, Object output) {
        return !((AgentNodeOutputs.ToolPhase) output).outcomes().isEmpty();
    }

    @Override
    public java.util.Map<String, Object> attributes(AgentRunContext context, Object output) {
        AgentNodeOutputs.ToolPhase phase = (AgentNodeOutputs.ToolPhase) output;
        if (phase.outcomes().isEmpty()) {
            return java.util.Map.of("aiwarden.tool.calls", 0);
        }
        return java.util.Map.of(
                "aiwarden.tool.calls", phase.outcomes().size(),
                "aiwarden.tool.statuses", phase.outcomes().stream()
                        .map(AgentNodeOutputs.ToolOutcome::status).toList());
    }

    @Override
    public void afterCheckpoint(AgentRunContext context, Object output) {
        for (AgentNodeOutputs.ToolOutcome outcome : ((AgentNodeOutputs.ToolPhase) output).outcomes()) {
            context.tool(outcome);
        }
    }
}
