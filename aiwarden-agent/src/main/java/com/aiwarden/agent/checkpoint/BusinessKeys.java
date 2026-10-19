package com.aiwarden.agent.checkpoint;

import com.aiwarden.contract.chat.ChatAskRequest;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 业务键解析（与 M3 同一口径）：显式 businessKey 优先；缺省 = 会话 + 消息指纹。
 *
 * <p>续跑必须得到<b>同一个</b>业务键，否则工具幂等键变化 → 重放变成第二次真实调用（重复副作用）。
 * 因此算法是检查点语义的一部分，不能各自复制一份——这里是唯一实现。
 */
public final class BusinessKeys {

    private BusinessKeys() {
    }

    public static String resolve(ChatAskRequest request, String sessionKey) {
        if (StringUtils.hasText(request.businessKey())) {
            return request.businessKey();
        }
        return "chat-" + sha256Hex16(sessionKey + "|" + request.message());
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
