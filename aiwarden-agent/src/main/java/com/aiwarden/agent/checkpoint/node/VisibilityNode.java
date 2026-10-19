package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.governance.visibility.VisibilitySetCalculator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 步骤 2：可见集计算（真实组件；空集不在此拒绝，由检索入口统一执行，ADR-008）。 */
@Component
@Order(2)
public class VisibilityNode implements AgentNode {

    private final VisibilitySetCalculator visibilitySetCalculator;

    public VisibilityNode(VisibilitySetCalculator visibilitySetCalculator) {
        this.visibilitySetCalculator = visibilitySetCalculator;
    }

    @Override
    public String name() {
        return "visibility";
    }

    @Override
    public int stepNo() {
        return 2;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Visibility.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        return java.util.Collections.singletonMap("kbId", context.request().kbId());
    }

    @Override
    public Object execute(AgentRunContext context) {
        VisibilitySet visible = visibilitySetCalculator.calculate(
                context.tenantId(), context.userId(), context.orgId(), context.request().kbId());
        return new AgentNodeOutputs.Visibility(visible.tenantId(),
                List.copyOf(visible.kbIds()),
                List.copyOf(visible.allowDocIds()),
                List.copyOf(visible.denyDocIds()));
    }

    @Override
    public String detail(AgentRunContext context, Object output) {
        return "kbIds=" + ((AgentNodeOutputs.Visibility) output).kbIds().size();
    }

    @Override
    public java.util.Map<String, Object> attributes(AgentRunContext context, Object output) {
        return java.util.Map.of("aiwarden.visibility.kb_count",
                ((AgentNodeOutputs.Visibility) output).kbIds().size());
    }
}
