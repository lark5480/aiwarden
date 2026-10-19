package com.aiwarden.agent.checkpoint.node;

import com.aiwarden.agent.checkpoint.AgentNode;
import com.aiwarden.agent.checkpoint.AgentNodeOutputs;
import com.aiwarden.agent.checkpoint.AgentRunContext;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

/**
 * 步骤 3：检索下推（真实组件；空可见集 / 租户不一致在此抛 SecurityException → deny）。
 *
 * <p>citation 事件在检查点提交之后投递（{@link #afterCheckpoint}）——SSE 上「看到引用」时，
 * 检索步骤已经持久化，续跑不会重放这一段。
 */
@Component
@Order(3)
public class RetrievalNode implements AgentNode {

    private final RetrievalService retrievalService;
    private final int topK;

    public RetrievalNode(RetrievalService retrievalService,
                         @Value("${aiwarden.chat.top-k:5}") int topK) {
        this.retrievalService = retrievalService;
        this.topK = topK;
    }

    @Override
    public String name() {
        return "retrieval";
    }

    @Override
    public int stepNo() {
        return 3;
    }

    @Override
    public Class<?> outputType() {
        return AgentNodeOutputs.Retrieval.class;
    }

    @Override
    public Object input(AgentRunContext context) {
        AgentNodeOutputs.Visibility visible = context.state()
                .require("visibility", AgentNodeOutputs.Visibility.class);
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("query", context.request().message());
        input.put("topK", topK);
        input.put("kbIds", visible.kbIds());
        return input;
    }

    @Override
    public Object execute(AgentRunContext context) {
        AgentNodeOutputs.Visibility visible = context.state()
                .require("visibility", AgentNodeOutputs.Visibility.class);
        VisibilitySet visibleSet = new VisibilitySet(visible.tenantId(),
                new HashSet<>(visible.kbIds()), new HashSet<>(visible.allowDocIds()),
                new HashSet<>(visible.denyDocIds()));
        List<RetrievalHit> hits = retrievalService.search(visibleSet, context.request().message(), topK);
        return new AgentNodeOutputs.Retrieval(hits.stream()
                .map(hit -> new AgentNodeOutputs.Hit(hit.chunkId(), hit.docId(),
                        hit.content(), hit.distance()))
                .toList());
    }

    @Override
    public String detail(AgentRunContext context, Object output) {
        return "hits=" + ((AgentNodeOutputs.Retrieval) output).hits().size();
    }

    @Override
    public java.util.Map<String, Object> attributes(AgentRunContext context, Object output) {
        AgentNodeOutputs.Retrieval retrieval = (AgentNodeOutputs.Retrieval) output;
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("aiwarden.retrieval.hits", retrieval.hits().size());
        if (!retrieval.hits().isEmpty()) {
            attributes.put("aiwarden.retrieval.min_distance",
                    retrieval.hits().stream().mapToDouble(AgentNodeOutputs.Hit::distance).min().orElse(0));
        }
        return attributes;
    }

    @Override
    public void afterCheckpoint(AgentRunContext context, Object output) {
        for (AgentNodeOutputs.Hit hit : ((AgentNodeOutputs.Retrieval) output).hits()) {
            context.citation(new RetrievalHit(hit.chunkId(), hit.docId(), hit.content(), hit.distance()));
        }
    }
}
