package com.aiwarden.agent.checkpoint;

import com.aiwarden.agent.orchestration.ChatStreamSink;
import com.aiwarden.agent.orchestration.OrchestrationResult;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.contract.chat.ChatEvents;
import com.aiwarden.contract.knowledge.RetrievalHit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次运行的上下文（FR-CKPT-01）：请求快照 + 检查点状态 + 事件出口。
 *
 * <p>它刻意不持有数据库连接 / 事务：节点执行与检查点提交分离（检查点用独立短事务），
 * 长耗时的模型调用不会把数据库事务挂住（与 M3 的「本类不做整体事务」一致）。
 */
public final class AgentRunContext {

    private final ChatAskRequest request;
    private final long runId;
    private final String sessionKey;
    private final long tenantId;
    private final long userId;
    private final Long orgId;
    private final String businessKey;
    private final ChatStreamSink sink;
    private final AtomicBoolean aborted;
    private final AgentState state;
    private final List<OrchestrationResult.StepRecord> timeline = new ArrayList<>();

    public AgentRunContext(ChatAskRequest request, long runId, String sessionKey,
                           long tenantId, long userId, Long orgId, String businessKey,
                           ChatStreamSink sink, AtomicBoolean aborted, AgentState state) {
        this.request = request;
        this.runId = runId;
        this.sessionKey = sessionKey;
        this.tenantId = tenantId;
        this.userId = userId;
        this.orgId = orgId;
        this.businessKey = businessKey;
        this.sink = sink;
        this.aborted = aborted;
        this.state = state;
    }

    public ChatAskRequest request() {
        return request;
    }

    public long runId() {
        return runId;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public long tenantId() {
        return tenantId;
    }

    public long userId() {
        return userId;
    }

    public Long orgId() {
        return orgId;
    }

    public String businessKey() {
        return businessKey;
    }

    public ChatStreamSink sink() {
        return sink;
    }

    public boolean aborted() {
        return aborted.get();
    }

    public AgentState state() {
        return state;
    }

    public List<OrchestrationResult.StepRecord> timeline() {
        return timeline;
    }

    /** 步骤完成（检查点已提交）→ 时间线 + step 事件同源记录。 */
    public void recordStep(String step, long elapsedMs, String detail) {
        OrchestrationResult.StepRecord record =
                new OrchestrationResult.StepRecord(step, elapsedMs, detail);
        timeline.add(record);
        sink.step(new ChatEvents.Step(record.step(), record.elapsedMs(), record.detail()));
    }

    /** 续跑：把已提交步骤放回时间线（不重发 SSE step——客户端看到的是「恢复 + 新增」的完整时间线）。 */
    public void restoreStep(String step, long elapsedMs, String detail) {
        timeline.add(new OrchestrationResult.StepRecord(step, elapsedMs, detail));
    }

    public void citation(RetrievalHit hit) {
        sink.citation(new ChatEvents.Citation(hit.docId(), hit.chunkId(),
                hit.content(), 1.0 - hit.distance()));
    }

    public void token(String token) {
        sink.token(new ChatEvents.Token(token));
    }

    public void tool(AgentNodeOutputs.ToolOutcome outcome) {
        sink.tool(new ChatEvents.Tool(outcome.tool(), outcome.status(), outcome.replayed(),
                outcome.invocationId(), outcome.error()));
    }

    public void cost(AgentNodeOutputs.Cost cost) {
        sink.cost(new ChatEvents.Cost(cost.promptTokens(), cost.completionTokens(),
                cost.cost(), cost.modelLatencyMs()));
    }

    public void outcome(AgentNodeOutputs.Outcome outcome) {
        sink.outcome(new ChatEvents.Outcome(outcome.outcome(), outcome.reason()));
        sink.done(new ChatEvents.Done(sessionKey));
    }
}
