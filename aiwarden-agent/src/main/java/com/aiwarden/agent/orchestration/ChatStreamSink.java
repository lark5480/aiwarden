package com.aiwarden.agent.orchestration;

import com.aiwarden.contract.chat.ChatEvents;

/**
 * 编排的事件出口（ADR-012 决策 1）：SSE 端点实现为「写 SseEmitter」，测试实现为「收集到列表」——
 * 编排内核不感知传输形态，治理链路可以脱离 HTTP 被评测。
 */
public interface ChatStreamSink {

    /** 无传输端点（续跑 / 评测直调）：事件丢弃，不影响编排语义。 */
    ChatStreamSink NOOP = new ChatStreamSink() {
        @Override
        public void step(ChatEvents.Step event) {
        }

        @Override
        public void token(ChatEvents.Token event) {
        }

        @Override
        public void citation(ChatEvents.Citation event) {
        }

        @Override
        public void tool(ChatEvents.Tool event) {
        }

        @Override
        public void outcome(ChatEvents.Outcome event) {
        }

        @Override
        public void cost(ChatEvents.Cost event) {
        }

        @Override
        public void done(ChatEvents.Done event) {
        }
    };

    void step(ChatEvents.Step event);

    void token(ChatEvents.Token event);

    void citation(ChatEvents.Citation event);

    void tool(ChatEvents.Tool event);

    void outcome(ChatEvents.Outcome event);

    void cost(ChatEvents.Cost event);

    void done(ChatEvents.Done event);
}
