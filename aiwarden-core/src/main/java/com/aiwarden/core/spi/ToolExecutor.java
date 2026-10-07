package com.aiwarden.core.spi;

/**
 * 写操作工具 SPI（PRD §7.3：工具执行边界的正式定义）。
 *
 * <p><b>职责边界</b>：具体工具只实现「业务副作用」本身（如往业务表写一行），
 * 并且**自身幂等**（重复执行不产生重复副作用）；治理管道（幂等键仲裁 / 补偿计划 /
 * 审计 / 计量 / 可见面）由 agent 模块的调用入口统一承担——治理动作挂在 SPI 切面上，
 * 不侵入工具实现（与 EmbeddingClient / VectorStore 同一纪律）。
 *
 * <p><b>可见面纪律（FR-PERM-03）</b>：工具名参与装配期白名单判定；不在可见面内的工具
 * 不得被执行（调用层在进入本接口之前即拒绝并审计）。
 */
public interface ToolExecutor {

    /** 工具名（装配期白名单 / 调用记录与计量维度的标识）。 */
    String name();

    /** 是否可补偿（决定失败时是否进入补偿计划，ADR-009 决策 4）。 */
    boolean compensable();

    /**
     * 执行工具（写操作）。
     *
     * @param inputJson 输入快照（JSON；与幂等指纹、补偿上下文共用同一份）
     * @return 结果 JSON
     * @throws ToolExecutionException 业务失败（可重试性由调用方语义决定；失败不重放，走补偿）
     */
    String execute(String inputJson);

    /**
     * 补偿（**仅 {@link #compensable()} 为 true 的工具需要实现**）。
     *
     * <p>要求自身幂等（重复补偿不产生重复副作用）。
     *
     * <p><b>返回值是「补偿是否真的产生了效果」的证据</b>：实现必须返回<b>受影响行数</b>，
     * 而不是无脑 return 成功——「UPDATE … WHERE status='OPEN'」匹配 0 行时（工单已被取消 /
     * 目标本就不存在）返回 {@code 0}，由执行器记为 {@code NO_OP}，**不计入成功**。
     * 否则会得到「审计写 SUCCEEDED、计数器加一，但数据库什么都没变」的静默假成功。
     *
     * <p><b>默认实现抛异常而不是空实现</b>：空实现会让「不可补偿工具被误执行补偿」表现为
     * 静默成功（审计 SUCCEEDED + 计数 +1，实际什么都没做）。抛异常使这类误用立刻可见；
     * 正常路径上执行器还有 {@link #compensable()} 守卫，不会走到这里。
     *
     * @param inputJson  原调用的输入快照
     * @param resultJson 原调用的结果快照
     * @return 受影响行数（0 = 无效果 / 已处于目标终态，属幂等 no-op）
     */
    default int compensate(String inputJson, String resultJson) {
        throw new UnsupportedOperationException(
                "工具未实现补偿：" + name() + "（compensable=" + compensable() + "）");
    }
}
