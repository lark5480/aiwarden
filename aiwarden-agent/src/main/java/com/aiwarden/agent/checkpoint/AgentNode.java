package com.aiwarden.agent.checkpoint;

/**
 * 确定性 DAG 的节点（FR-CKPT-01）：**显式顺序 + 每步可独立测试**。
 *
 * <p>节点只依赖 {@link AgentRunContext}（前序步骤的输出快照 + 请求上下文），不持有运行期可变状态——
 * 因此「实例复用」在本设计里没有立足点：续跑时从数据库读回快照、重新构造节点，旧实例直接丢弃。
 *
 * <p>SSE 事件的投递时机分两段：执行期（流式 token）与 {@link #afterCheckpoint}（步骤落库之后）。
 * 后者保证「客户端看到某步完成」时，该步的检查点已经持久化——事件与状态不会互相矛盾。
 */
public interface AgentNode {

    /** 节点名（同时是 step 事件名 / 检查点 name；init/finalize 不发 step 事件）。 */
    String name();

    /** DAG 中的步骤号（1 起，连续）。 */
    int stepNo();

    /** 输出快照类型（续跑时据此反序列化）。 */
    Class<?> outputType();

    /** 输入快照（持久化到 t_agent_step.input；默认 null = 节点不需要额外输入）。 */
    default Object input(AgentRunContext context) {
        return null;
    }

    /** 执行节点（不得修改前序输出；副作用只能发生在 tool 节点）。 */
    Object execute(AgentRunContext context);

    /** step 事件的 detail 文本（默认空串）。 */
    default String detail(AgentRunContext context, Object output) {
        return "";
    }

    /** 追加到该节点 span 的属性（FR-OBS-04：关键业务 ID / 诊断量注入 span）。 */
    default java.util.Map<String, Object> attributes(AgentRunContext context, Object output) {
        return java.util.Map.of();
    }

    /** 是否投递 step 事件（init/cost/outcome/finalize 不投递；tool 节点无工具决策时也不投递）。 */
    default boolean emitsStepEvent(AgentRunContext context, Object output) {
        return true;
    }

    /** 检查点提交之后再投递非 step 事件（citation / tool / cost / outcome / done）。 */
    default void afterCheckpoint(AgentRunContext context, Object output) {
    }
}
