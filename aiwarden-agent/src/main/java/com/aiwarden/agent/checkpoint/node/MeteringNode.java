package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.agent.orchestration.ChatMeteringWriter;
import com.aiwarden.core.event.MeteringEventTypes;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 步骤 6：模型调用计量（真实 Outbox；P4a 四维归因的模型维度）。
 *
 * <p>计量与「模型生成」分成两个检查点：模型已完成但计量未落时崩溃，续跑会重做计量步骤。
 * 计量事件带幂等键 {@code model:<runId>:<stepNo>}，消费端以 {@code t_llm_call_log.dedup_key}
 * 唯一索引仲裁（{@code ON CONFLICT DO NOTHING}），所以重做不会重复计费；
 * 工具维度另由 t_tool_invocation 唯一键与 {@code tool:<invocationId>} 保证不重复。
 */
@Component
@Order(6)
public class MeteringNode implements AgentNode {

    /** 与 M3 一致：模型生成的逻辑步骤号（工具幂等键与计量归因的既有口径，不改写）。 */
    public static final int MODEL_STEP_NO = 1;

    private final ChatMeteringWriter meteringWriter;

    public MeteringNode(ChatMeteringWriter meteringWriter) {
        this.meteringWriter = meteringWriter;
    }

    @Override
    public String name() {
        return "metering";
    }

    @Override
    public int stepNo() {
        return 6;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Metering.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        AgentNodeOutputs.Model model = context.state().require("model", AgentNodeOutputs.Model.class);
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("sessionKey", context.sessionKey());
        input.put("model", model.model());
        input.put("promptTokens", model.promptTokens());
        input.put("completionTokens", model.completionTokens());
        input.put("latencyMs", model.latencyMs());
        return input;
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Model model = context.state().require("model", AgentNodeOutputs.Model.class);
        meteringWriter.recordModelCall(context.tenantId(), context.runId(), context.sessionKey(),
                MODEL_STEP_NO, model.model(), model.promptTokens(), model.completionTokens(),
                (int) model.latencyMs());
        return new AgentNodeOutputs.Metering(MeteringEventTypes.CALL_RECORDED);
    }

    @Override
    public String detail(AgentRunContext context, Object output) {
        return "event=" + ((AgentNodeOutputs.Metering) output).event();
    }
}
