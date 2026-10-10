package com.aiwarden.contract.chat;

/**
 * 问答请求（FR-RET-01：POST /api/v1/chat，SSE 响应）。
 *
 * @param message     用户问题（必填）
 * @param sessionId   会话标识（可选；缺省由服务端生成并在 done 事件返回——工具幂等键成分与四维归因维度）
 * @param kbId        限定知识库（可选；空 = 全部可见知识库）
 * @param businessKey 业务键（可选；缺省 = 会话 + 消息指纹——同一请求重放捕获为同一逻辑工具调用，
 *                    断网重放主场景的编程模型，ADR-012 决策 8）
 */
public record ChatAskRequest(String message, String sessionId, Long kbId, String businessKey) {
}
