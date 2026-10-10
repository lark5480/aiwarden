package com.aiwarden.agent.model;

import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelToolCall;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 模型响应 fixture 的格式与读写（FR-EVAL-05 双轨机制；ADR-012 决策 4）。
 *
 * <p><b>指纹定位</b>：fixture 文件名 = sha256(系统提示 ⊕ 用户消息 ⊕ 工具名列表) 前 16 hex——
 * 同一请求命中同一 fixture；请求不匹配即显式异常（不静默错配）。
 *
 * <p><b>fixture 的真实模型录制待可用端点后补</b>：当前机制由 roundtrip 测试保真
 * （Mock 录制 → 回放一致），fixture 文件的 {@code source} 字段如实标注来源。
 */
public final class ModelFixtures {

    private ModelFixtures() {
    }

    /** 根目录下的 fixture 文件路径（指纹 + .json）。 */
    static Path fileOf(Path directory, ModelChatRequest request) {
        return directory.resolve(fingerprint(request) + ".json");
    }

    static void write(Path directory, ModelFixture fixture, ObjectMapper objectMapper) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(fingerprintOf(fixture.systemPrompt(), fixture.userMessage(),
                    fixture.toolNames()) + ".json");
            Files.writeString(file, objectMapper.writeValueAsString(fixture), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("fixture 写入失败：" + directory, e);
        }
    }

    static ModelFixture readOrThrow(Path directory, ModelChatRequest request, ObjectMapper objectMapper) {
        Path file = fileOf(directory, request);
        if (!Files.exists(file)) {
            throw new IllegalStateException(
                    "模型 fixture 缺失：%s——录制回放轨要求该请求已有录制（机制就绪、fixture 待录，ADR-012）"
                            .formatted(file.getFileName()));
        }
        try {
            ModelFixture fixture = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    ModelFixture.class);
            requireMatch(fixture, request, file);
            return fixture;
        } catch (IOException e) {
            throw new UncheckedIOException("fixture 读取失败：" + file, e);
        }
    }

    private static void requireMatch(ModelFixture fixture, ModelChatRequest request, Path file) {
        List<String> requestToolNames = request.tools().stream().map(t -> t.name()).toList();
        if (!fixture.systemPrompt().equals(request.systemPrompt())
                || !fixture.userMessage().equals(request.userMessage())
                || !fixture.toolNames().equals(requestToolNames)) {
            throw new IllegalStateException(
                    "fixture 与请求不匹配（指纹碰撞或文件被改写）：" + file.getFileName());
        }
    }

    static String fingerprint(ModelChatRequest request) {
        return fingerprintOf(request.systemPrompt(), request.userMessage(),
                request.tools().stream().map(t -> t.name()).toList());
    }

    private static String fingerprintOf(String systemPrompt, String userMessage, List<String> toolNames) {
        String material = systemPrompt + '\u0000' + userMessage + '\u0000' + String.join(",", toolNames);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 一次模型调用的完整 fixture。
     *
     * @param source 录制来源（如 "mock" / 真实模型标识）——如实标注，不伪造
     */
    public record ModelFixture(String model, String systemPrompt, String userMessage,
                               List<String> toolNames, String text, List<ModelToolCall> toolCalls,
                               int promptTokens, int completionTokens,
                               String recordedAt, String source) {
    }
}
