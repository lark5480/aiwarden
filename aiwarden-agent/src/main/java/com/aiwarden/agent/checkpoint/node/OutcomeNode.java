package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 步骤 9：结局（outcome + done 事件在检查点之后投递；结局本身可续跑恢复）。 */
@Component
@Order(9)
public class OutcomeNode implements AgentNode {

    @Override
    public String name() {
        return "outcome";
    }

    @Override
    public int stepNo() {
        return 9;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Outcome.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        return context.state().require("cost", AgentNodeOutputs.Cost.class);
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Cost cost = context.state().require("cost", AgentNodeOutputs.Cost.class);
        return new AgentNodeOutputs.Outcome(cost.outcome(), cost.reason());
    }

    @Override
    public boolean emitsStepEvent(AgentRunContext context, Object output) {
        return false;
    }

    @Override
    public void afterCheckpoint(AgentRunContext context, Object output) {
        context.outcome((AgentNodeOutputs.Outcome) output);
    }
}
