package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 步骤 10：收尾（运行状态由引擎在检查点提交后置 SUCCEEDED）。
 *
 * <p>单独成节点而不是复用 outcome：崩溃可能发生在「结局已落、运行状态未落」之间——
 * 有这一步，续跑能识别「只剩状态收尾」，而不是把整个结局重做一遍。
 */
@Component
@Order(10)
public class FinalizeNode implements AgentNode {

    @Override
    public String name() {
        return "finalize";
    }

    @Override
    public int stepNo() {
        return 10;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Finalize.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        return context.state().require("outcome", AgentNodeOutputs.Outcome.class);
    }

    @Override
    public Object execute(AgentRunContext context) {
        return new AgentNodeOutputs.Finalize("SUCCEEDED");
    }

    @Override
    public boolean emitsStepEvent(AgentRunContext context, Object output) {
        return false;
    }
}
