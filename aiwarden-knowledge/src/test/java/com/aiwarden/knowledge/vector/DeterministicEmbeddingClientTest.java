package com.aiwarden.knowledge.vector;

import com.aiwarden.core.spi.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性嵌入替身：同文本恒定同向量、维度对齐 schema、L2 归一化。
 */
class DeterministicEmbeddingClientTest {

    private final DeterministicEmbeddingClient client = new DeterministicEmbeddingClient();

    @Test
    void sameText_producesIdenticalVector() {
        float[] first = client.embed("AIWarden 治理层");
        float[] second = client.embed("AIWarden 治理层");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentText_producesDifferentVector() {
        float[] first = client.embed("文档 A");
        float[] second = client.embed("文档 B");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void dimension_matchesSchema() {
        assertThat(client.embed("任意文本")).hasSize(EmbeddingClient.DIMENSION);
        assertThat(client.embed(null)).hasSize(EmbeddingClient.DIMENSION);
    }

    @Test
    void vector_isL2Normalized() {
        float[] vector = client.embed("归一化校验");

        double norm = Math.sqrt(Arrays.stream(toDoubleArray(vector)).map(v -> v * v).sum());

        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    private double[] toDoubleArray(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }
}
