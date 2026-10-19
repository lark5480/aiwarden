package com.aiwarden.agent.checkpoint;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DAG 节点的输入/输出快照（FR-CKPT-01/02）：每个记录都是可 JSON 序列化的不可变值，
 * 因此「进程重启后从最近完成步骤续跑」不需要复用任何旧实例（FR-CKPT-03 的过期实例问题）。
 *
 * <p>记录字段名即 {@code t_agent_step.input/output} 的 JSON 字段名——它们是**检查点格式**，
 * 改名等同改协议，需要与新节点版本一起迁移。
 */
public final class AgentNodeOutputs {

    private AgentNodeOutputs() {
    }

    /** init：本次运行的身份与环境（续跑时据此恢复主体，不信任客户端）。 */
    public record Session(String sessionKey, String businessKey) {
    }

    /** visibility：可见集（与 {@code VisibilitySet} 同构，但保持检查点独立于实现类型）。 */
    public record Visibility(long tenantId, List<Long> kbIds, List<Long> allowDocIds, List<Long> denyDocIds) {
    }

    public record Hit(long chunkId, long docId, String content, double distance) {
    }

    public record Retrieval(List<Hit> hits) {
    }

    public record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    /** prompt：模型输入快照（续跑时不再重新组装，Prompt 变更也不会改写已开始的运行）。 */
    public record Prompt(String systemPrompt, String userMessage, List<ToolSpec> tools) {
    }

    public record ToolCall(String toolName, Map<String, Object> arguments) {
    }

    /** model：模型决策与 token 统计（流式 token 不回放——续跑从下一步继续）。 */
    public record Model(String model, String text, List<ToolCall> toolCalls,
                        int promptTokens, int completionTokens, long latencyMs) {
    }

    public record Metering(String event) {
    }

    public record ToolOutcome(String tool, String status, boolean replayed, Long invocationId, String error) {
    }

    /** tool：副作用结果 + 阶段结局（null = 继续 answer）。 */
    public record ToolPhase(List<ToolOutcome> outcomes, String outcome, String reason) {
    }

    public record Cost(int promptTokens, int completionTokens, BigDecimal cost,
                       long modelLatencyMs, String outcome, String reason) {
    }

    public record Outcome(String outcome, String reason) {
    }

    public record Finalize(String status) {
    }
}
