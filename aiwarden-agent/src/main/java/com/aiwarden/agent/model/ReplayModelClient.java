package com.aiwarden.agent.model;

import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelChatResult;
import com.aiwarden.core.spi.ModelClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * 回放客户端（FR-EVAL-05 双轨机制的「录制回放」轨；ADR-012 决策 4）。
 *
 * <p>按请求指纹从 fixture 目录读取录制结果并回放（分块回调与录制文本一致）。
 * <b>fixture 缺失即显式异常</b>——不静默回退到其它实现（「不报错、只是静默不生效」的坑形态，
 * 见 AGENTS §4）；请求与 fixture 不匹配同样拒绝（防指纹碰撞 / 文件被改写）。
 */
public class ReplayModelClient implements ModelClient {

    /** 回放分块大小（字符；与录制时的流式语义一致的确定性分块）。 */
    private static final int CHUNK_SIZE = 16;

    private final Path directory;
    private final ObjectMapper objectMapper;
    private final String modelName;

    /**
     * @param modelName 计量口径的模型标识（如 "replay-recorded-v1"——回放的 fixture 可能多来源，
     *                  以「回放」聚合是诚实口径）
     */
    public ReplayModelClient(Path directory, ObjectMapper objectMapper, String modelName) {
        this.directory = directory;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    @Override
    public String model() {
        return modelName;
    }

    @Override
    public ModelChatResult chat(ModelChatRequest request, TokenListener tokenListener) {
        ModelFixtures.ModelFixture fixture = ModelFixtures.readOrThrow(directory, request, objectMapper);
        for (int i = 0; i < fixture.text().length(); i += CHUNK_SIZE) {
            tokenListener.onToken(fixture.text().substring(i,
                    Math.min(fixture.text().length(), i + CHUNK_SIZE)));
        }
        return new ModelChatResult(fixture.text(), fixture.toolCalls(),
                fixture.promptTokens(), fixture.completionTokens());
    }
}
