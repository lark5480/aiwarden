package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.agent.checkpoint.ChatCost;
import com.aiwarden.contract.chat.ChatEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 步骤 8：成本汇总（演示单价；cost 事件在检查点之后投递）。 */
@Component
@Order(8)
public class CostNode implements AgentNode {

    private final BigDecimal promptCostPer1k;
    private final BigDecimal completionCostPer1k;

    public CostNode(@Value("${aiwarden.chat.cost.prompt-per-1k:0.002}") double promptCostPer1k,
                    @Value("${aiwarden.chat.cost.completion-per-1k:0.006}") double completionCostPer1k) {
        this.promptCostPer1k = BigDecimal.valueOf(promptCostPer1k);
        this.completionCostPer1k = BigDecimal.valueOf(completionCostPer1k);
    }

    @Override
    public String name() {
        return "cost";
    }

    @Override
    public int stepNo() {
        return 8;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Cost.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        AgentNodeOutputs.Model model = context.state().require("model", AgentNodeOutputs.Model.class);
        return java.util.Map.of("promptTokens", model.promptTokens(),
                "completionTokens", model.completionTokens());
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Model model = context.state().require("model", AgentNodeOutputs.Model.class);
        AgentNodeOutputs.ToolPhase phase = context.state()
                .require("tool", AgentNodeOutputs.ToolPhase.class);
        String outcome = phase.outcome() == null ? ChatEvents.Outcome.ANSWER : phase.outcome();
        BigDecimal cost = ChatCost.compute(model.promptTokens(), model.completionTokens(),
                promptCostPer1k, completionCostPer1k);
        return new AgentNodeOutputs.Cost(model.promptTokens(), model.completionTokens(), cost,
                model.latencyMs(), outcome, phase.reason());
    }

    @Override
    public boolean emitsStepEvent(AgentRunContext context, Object output) {
        return false;
    }

    @Override
    public void afterCheckpoint(AgentRunContext context, Object output) {
        context.cost((AgentNodeOutputs.Cost) output);
    }
}
