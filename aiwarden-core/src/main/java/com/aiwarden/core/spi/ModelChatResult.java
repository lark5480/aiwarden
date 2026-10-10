package com.aiwarden.core.spi;

import java.util.List;

/**
 * 对话结果。
 *
 * @param text             完整文本（与流式回调的 token 拼接一致）
 * @param toolCalls        工具调用决策（空列表 = 纯文本回答）
 * @param promptTokens     输入 token 数（真实实现为上游统计；替身为估算口径，ADR-012）
 * @param completionTokens 输出 token 数（同上）
 */
public record ModelChatResult(String text, List<ModelToolCall> toolCalls,
                              int promptTokens, int completionTokens) {
}
