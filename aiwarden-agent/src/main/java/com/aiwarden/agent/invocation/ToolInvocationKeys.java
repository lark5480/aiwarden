package com.aiwarden.agent.invocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 工具幂等键（FR-TOOL-01 / ADR-009 决策 1）：{@code 业务键 + ':' + 会话ID + ':' + 步骤指纹}。
 *
 * <p>步骤指纹 = {@code sha256(tool|stepNo|输入规范 JSON)} 前 16 hex——输入按 key 排序序列化
 * （同输入同键），工具名与步骤号参与指纹（同一步骤换工具或换输入即为不同调用）。
 */
public final class ToolInvocationKeys {

    private ToolInvocationKeys() {
    }

    public static String of(String businessKey, String sessionId, int stepNo,
                            String tool, String canonicalInputJson) {
        String fingerprint = sha256Hex16(tool + "|" + stepNo + "|" + canonicalInputJson);
        return businessKey + ":" + sessionId + ":" + fingerprint;
    }

    private static String sha256Hex16(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
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
}
