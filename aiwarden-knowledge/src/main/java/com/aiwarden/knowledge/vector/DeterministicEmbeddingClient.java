package com.aiwarden.knowledge.vector;

import com.aiwarden.core.spi.EmbeddingClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;

/**
 * 确定性嵌入替身（演示 / 测试用，不调真实模型）：
 * 同一文本恒定产出同一 {@value EmbeddingClient#DIMENSION} 维、L2 归一化向量（SHA-256 播种 PRNG）。
 *
 * <p>替换路径：真实模型接入 = 新增一个 {@link EmbeddingClient} 实现（OpenAI 兼容 / Ollama），
 * 复用方零改动；本项目不承诺检索质量（B1），演示不依赖向量语义相似度。
 */
@Component
public class DeterministicEmbeddingClient implements EmbeddingClient {

    @Override
    public float[] embed(String text) {
        SplittableRandom random = new SplittableRandom(seedOf(text));
        float[] vector = new float[DIMENSION];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            float value = (float) random.nextDouble(-1.0, 1.0);
            vector[i] = value;
            norm += (double) value * value;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private long seedOf(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                seed = (seed << 8) | (hash[i] & 0xFFL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
