package com.aiwarden.agent.model;

import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelChatResult;
import com.aiwarden.core.spi.ModelClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 录制装饰器（FR-EVAL-05 双轨机制的「录制」轨；ADR-012 决策 4）。
 *
 * <p>包住任一 {@link ModelClient}（替身或真实适配器），把每次调用的请求 + 响应写为 JSON fixture。
 * 真实模型接入时用它录制 fixture，之后 CI 用 {@link ReplayModelClient} 回放（不打真实 API）。
 *
 * @param source 录制来源标注（写入 fixture，如实标注——"mock" / 真实模型标识）
 */
public class RecordingModelClient implements ModelClient {

    private final ModelClient delegate;
    private final Path directory;
    private final ObjectMapper objectMapper;
    private final String source;

    public RecordingModelClient(ModelClient delegate, Path directory,
                                ObjectMapper objectMapper, String source) {
        this.delegate = delegate;
        this.directory = directory;
        this.objectMapper = objectMapper;
        this.source = source;
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public ModelChatResult chat(ModelChatRequest request, TokenListener tokenListener) {
        ModelChatResult result = delegate.chat(request, tokenListener);
        ModelFixtures.write(directory, new ModelFixtures.ModelFixture(
                delegate.model(), request.systemPrompt(), request.userMessage(),
                request.tools().stream().map(t -> t.name()).toList(),
                result.text(), result.toolCalls(), result.promptTokens(), result.completionTokens(),
                Instant.now().toString(), source), objectMapper);
        return result;
    }
}
