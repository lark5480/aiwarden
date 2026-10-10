package com.aiwarden.core.spi;

import java.util.List;

/**
 * 对话模型 SPI（模型接入边界；PRD §7.1「模型接入 = OpenAI 兼容协议 + 适配器 SPI」）。
 *
 * <p><b>SPI 即治理切面</b>：计量 / 配额等治理动作挂在调用边界（编排层），不侵入模型实现；
 * 业务模块不得直接依赖 LangChain4j / 厂商 SDK（ArchUnit 红线在册，PRD §7.3 规则 2）。
 *
 * <p><b>演示期实现</b>：agent 模块的 MockModelClient（确定性剧本身份，ADR-012）；
 * 真实模型接入 = 新增实现本接口的适配器（在 start 装配根替换），录制 / 回放装饰器
 * 可直接包住任一实现（FR-EVAL-05 双轨机制）。
 *
 * <p><b>流式契约</b>：{@link #chat} 同步执行，文本按顺序经 {@link TokenListener} 分块回调；
 * 工具调用决策与 token 统计在返回的 {@link ModelChatResult} 中。
 */
public interface ModelClient {

    /** 模型标识（计量维度之一；如 "mock-deterministic-v1"）。 */
    String model();

    /**
     * 执行一次对话补全。
     *
     * @param request       请求（系统提示 + 用户消息 + 可见面工具规格；
     *                      FR-PERM-03：未在白名单内的工具不进请求体）
     * @param tokenListener 流式 token 回调（按顺序；实现必须同步回调）
     * @return 完整结果（文本 + 工具调用决策 + token 统计）
     */
    ModelChatResult chat(ModelChatRequest request, TokenListener tokenListener);

    /** 流式 token 回调（一次一个文本块）。 */
    @FunctionalInterface
    interface TokenListener {
        void onToken(String token);
    }
}
