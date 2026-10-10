package com.aiwarden.start.chat;

import com.aiwarden.agent.model.MockModelClient;
import com.aiwarden.agent.model.RecordingModelClient;
import com.aiwarden.agent.model.ReplayModelClient;
import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelChatResult;
import com.aiwarden.core.spi.ModelToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 录制回放双轨机制的保真验证（FR-EVAL-05；ADR-012 决策 4）：
 * ① 录制 → 回放 roundtrip：文本 / 工具决策 / token 统计 / 流式分块全等；
 * ② fixture 缺失 → 显式异常（不静默回退）；
 * ③ fixture 被改写（与请求不匹配）→ 显式拒绝。
 *
 * <p>真实模型的 fixture 录制待可用端点后补——本测试证明的是**机制保真**（ADR-012 如实标注）。
 */
class ModelFixturesRoundTripTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private static ModelChatRequest request(String message) {
        return new ModelChatRequest(
                "你是 AIWarden 演示助手。\n【知识库片段】\n（无命中）\n", message,
                List.of(new ModelToolSpec("create_ticket", "创建工单", Map.of("type", "object"))));
    }

    @Test
    void recordThenReplay_isFaithful_roundTrip(@TempDir Path directory) throws Exception {
        MockModelClient mock = new MockModelClient();
        RecordingModelClient recorder = new RecordingModelClient(mock, directory, objectMapper, "mock");
        ModelChatRequest chatRequest = request("帮我建工单 ORDER-7777");

        List<String> recordedTokens = new ArrayList<>();
        ModelChatResult recorded = recorder.chat(chatRequest, recordedTokens::add);

        try (var files = Files.list(directory)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                    .as("录制后应恰有一个 fixture 文件（按请求指纹命名）")
                    .hasSize(1);
        }

        ReplayModelClient replay = new ReplayModelClient(directory, objectMapper, "replay-recorded-v1");
        List<String> replayedTokens = new ArrayList<>();
        ModelChatResult replayed = replay.chat(chatRequest, replayedTokens::add);

        assertThat(replayed.text()).isEqualTo(recorded.text());
        assertThat(replayed.toolCalls()).isEqualTo(recorded.toolCalls());
        assertThat(replayed.promptTokens()).isEqualTo(recorded.promptTokens());
        assertThat(replayed.completionTokens()).isEqualTo(recorded.completionTokens());
        assertThat(replayedTokens).as("回放分块与录制分块一致").isEqualTo(recordedTokens);
        assertThat(String.join("", replayedTokens)).isEqualTo(recorded.text());
    }

    @Test
    void missingFixture_failsExplicitly(@TempDir Path directory) {
        ReplayModelClient replay = new ReplayModelClient(directory, objectMapper, "replay-recorded-v1");
        assertThatThrownBy(() -> replay.chat(request("没有录制过的请求"), t -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture 缺失");
    }

    @Test
    void tamperedFixture_isRejected(@TempDir Path directory) throws Exception {
        MockModelClient mock = new MockModelClient();
        RecordingModelClient recorder = new RecordingModelClient(mock, directory, objectMapper, "mock");
        ModelChatRequest chatRequest = request("帮我建工单 ORDER-8888");
        recorder.chat(chatRequest, t -> {
        });

        // 改写 fixture 的用户消息（模拟指纹碰撞 / 文件被篡改）——请求指纹不变，但内容与请求不再匹配
        Path fixture = Files.list(directory).findFirst().orElseThrow();
        String content = Files.readString(fixture, StandardCharsets.UTF_8);
        Files.writeString(fixture, content.replace("ORDER-8888", "ORDER-9999"), StandardCharsets.UTF_8);

        ReplayModelClient replay = new ReplayModelClient(directory, objectMapper, "replay-recorded-v1");
        assertThatThrownBy(() -> replay.chat(chatRequest, t -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不匹配");
    }
}
