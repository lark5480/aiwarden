package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.agent.invocation.ToolInvocationService;
import com.aiwarden.agent.tools.ToolCatalog;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤 4：Prompt 组装（片段来自真实下推检索；工具规格 = 白名单可见面，FR-PERM-03）。
 *
 * <p>组装结果整体进检查点：续跑不会因为 Prompt 模板变更而改写一次**已经开始**的运行
 * （可复现性优先于「用新模板重跑」——后者是显式的重新发起，不是续跑）。
 */
@Component
@Order(4)
public class PromptNode implements AgentNode {

    private final ToolInvocationService toolInvocationService;

    public PromptNode(ToolInvocationService toolInvocationService) {
        this.toolInvocationService = toolInvocationService;
    }

    @Override
    public String name() {
        return "prompt";
    }

    @Override
    public int stepNo() {
        return 4;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Prompt.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("message", context.request().message());
        input.put("hits", context.state().require("retrieval", AgentNodeOutputs.Retrieval.class).hits());
        return input;
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Retrieval retrieval = context.state()
                .require("retrieval", AgentNodeOutputs.Retrieval.class);
        StringBuilder system = new StringBuilder();
        system.append("你是 AIWarden 演示助手（数据面治理组件的问答链路）。\n");
        system.append("回答约束：仅基于下方【知识库片段】回答；引用以 [序号] 标注；不得声称执行未提供的操作。\n");
        system.append("【知识库片段】\n");
        if (retrieval.hits().isEmpty()) {
            system.append("（无命中）\n");
        } else {
            int seq = 1;
            for (AgentNodeOutputs.Hit hit : retrieval.hits()) {
                system.append('[').append(seq++).append("] doc=").append(hit.docId())
                        .append(" chunk=").append(hit.chunkId()).append('\n')
                        .append(hit.content()).append('\n');
            }
        }
        List<AgentNodeOutputs.ToolSpec> tools = toolInvocationService.visibleTools().stream()
                .map(name -> new AgentNodeOutputs.ToolSpec(name, ToolCatalog.description(name),
                        ToolCatalog.businessSchema(name)))
                .toList();
        return new AgentNodeOutputs.Prompt(system.toString(), context.request().message(), tools);
    }

    @Override
    public String detail(AgentRunContext context, Object output) {
        AgentNodeOutputs.Prompt prompt = (AgentNodeOutputs.Prompt) output;
        return "snippets=" + context.state()
                .require("retrieval", AgentNodeOutputs.Retrieval.class).hits().size()
                + ", tools=" + prompt.tools().size();
    }
}
