package com.aiwarden.core.spi;

/**
 * 嵌入模型 SPI（模型接入边界，PRD §7.3：业务代码不直接依赖 LangChain4j / 模型厂商 SDK）。
 *
 * <p>演示期由确定性替身实现（knowledge 的 DeterministicEmbeddingClient）供货；
 * 真实模型接入 = 新增一个实现本接口的适配器，治理动作（计量 / 配额）挂在 SPI 切面。
 */
public interface EmbeddingClient {

    /** 向量维度：与 V2 迁移的 {@code vector(1536)} 对齐（对齐 OpenAI text-embedding-3-small；模型化时改为可配置）。 */
    int DIMENSION = 1536;

    /** 生成文本的嵌入向量（长度恒为 {@link #DIMENSION}）。 */
    float[] embed(String text);
}
