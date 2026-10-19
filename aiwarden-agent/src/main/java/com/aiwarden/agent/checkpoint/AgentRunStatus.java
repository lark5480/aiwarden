package com.aiwarden.agent.checkpoint;

/** Agent 运行状态（t_agent_session.status）。 */
public final class AgentRunStatus {

    /** 执行中（或执行实例崩溃后残留——租约过期即可被续跑实例接管）。 */
    public static final String RUNNING = "RUNNING";

    /** 正常完成（含 answer / human_handoff）。 */
    public static final String SUCCEEDED = "SUCCEEDED";

    /** 治理拒绝（可见集空 / 工具不可见 / 配额 / 限流）。 */
    public static final String DENIED = "DENIED";

    /** 编排内部错误（可续跑，续跑会从最近检查点重试失败步骤）。 */
    public static final String FAILED = "FAILED";

    /** 客户端断连 / 人工中断（可按需续跑——FR-CKPT-02 的「超时 / 人工中断」）。 */
    public static final String ABORTED = "ABORTED";

    public static boolean resumable(String status) {
        // FAILED 刻意不可续跑：内部错误重放同一输入只会得到同一错误，留给人看检查点而不是自动重试。
        return RUNNING.equals(status) || ABORTED.equals(status);
    }

    private AgentRunStatus() {
    }
}
