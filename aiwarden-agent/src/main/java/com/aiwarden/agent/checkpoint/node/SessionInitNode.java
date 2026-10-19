package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 步骤 1：运行初始化（检查点身份；不发 step 事件——保持 M3 的 SSE 帧序）。 */
@Component
@Order(1)
public class SessionInitNode implements AgentNode {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public int stepNo() {
        return 1;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Session.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        return context.request();
    }

    @Override
    public Object execute(AgentRunContext context) {
        return new AgentNodeOutputs.Session(context.sessionKey(), context.businessKey());
    }

    @Override
    public boolean emitsStepEvent(AgentRunContext context, Object output) {
        return false;
    }
}
