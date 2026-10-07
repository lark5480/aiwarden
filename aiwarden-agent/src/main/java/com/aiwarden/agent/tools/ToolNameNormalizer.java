package com.aiwarden.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具名装配期归一化（FR-TOOL-05）：**非法字符换下划线 + 撞名加哈希后缀**。
 *
 * <p><b>为什么必须归一化</b>：模型协议对工具名有字符集约束（常见 `^[a-zA-Z0-9_-]{1,64}$`）——
 * 带点号（如 {@code web.search}）或其它非法字符的工具名会导致**模型整轮 400**；
 * 而两个不同原始名归一化后可能撞名（如 {@code a.b} 与 {@code a-b} 都变 {@code a_b}），
 * 必须加基于**原始名**的稳定哈希后缀区分。
 *
 * <p>归一化发生在装配期（MCP 暴露 / 模型请求体组装前）；执行时仍以原始名路由到
 * {@code ToolRegistry}——映射关系由本类产出、可测试、可复现（同输入同输出）。
 */
public final class ToolNameNormalizer {

    /** 模型侧工具名长度上限（保守取 64，超出截断 + 哈希后缀）。 */
    static final int MAX_LENGTH = 64;

    private ToolNameNormalizer() {
    }

    /**
     * 批量归一化（撞名安全）：
     * ① 每个名字替换非法字符为下划线；
     * ② 归一化后组内撞名的，各自追加 {@code _<原始名哈希前8位>}；
     * ③ 超长截断到 55 字符再追加哈希——结果长度 ≤ 64、不同原始名不碰撞。
     *
     * @return 原始名 → 归一化名（保序）
     */
    public static Map<String, String> normalizeAll(List<String> rawNames) {
        // 第一轮：基础归一（含长度处理）
        Map<String, String> base = new LinkedHashMap<>();
        for (String raw : rawNames) {
            base.put(raw, normalizeBase(raw));
        }
        // 第二轮：撞名组加哈希后缀（基于原始名，稳定）
        Map<String, List<String>> groups = new HashMap<>();
        base.forEach((raw, normalized) -> groups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(raw));
        Map<String, String> result = new LinkedHashMap<>();
        groups.forEach((normalized, raws) -> {
            if (raws.size() == 1) {
                result.put(raws.get(0), normalized);
            } else {
                for (String raw : raws) {
                    result.put(raw, withHashSuffix(normalized, raw));
                }
            }
        });
        // 恢复输入顺序
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String raw : rawNames) {
            ordered.put(raw, result.get(raw));
        }
        return ordered;
    }

    /** 单个工具名归一化（无撞名上下文时使用；撞名场景请用 {@link #normalizeAll}）。 */
    public static String normalize(String rawName) {
        return normalizeBase(rawName);
    }

    private static String normalizeBase(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        String normalized = rawName.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (normalized.length() > MAX_LENGTH) {
            normalized = normalized.substring(0, MAX_LENGTH - 9) + "_" + hash8(rawName);
        }
        return normalized;
    }

    private static String withHashSuffix(String normalized, String rawName) {
        String suffix = "_" + hash8(rawName);
        String head = normalized.length() + suffix.length() > MAX_LENGTH
                ? normalized.substring(0, MAX_LENGTH - suffix.length())
                : normalized;
        return head + suffix;
    }

    private static String hash8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
