package com.aiwarden.agent.api;

import com.aiwarden.agent.orchestration.ChatOrchestrator;
import com.aiwarden.agent.orchestration.ChatStreamSink;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.contract.chat.ChatEvents;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答接口（FR-RET-01 / FR-APP-01~04）：`POST /api/v1/chat`（SSE，7 类事件见 {@link ChatEvents}）。
 *
 * <p><b>线程边界（ADR-003）</b>：编排在独立虚拟线程执行——执行前 capture 租户快照、apply 主体，
 * 执行后清理；在 Servlet 请求线程上直接用 ThreadLocal 会丢上下文（编排内的可见集 / 检索 / 工具
 * 全部 require 租户与主体）。
 *
 * <p><b>断连语义（FR-RET-01）</b>：sink 写失败（客户端断连）即置位取消标志；emitter 的
 * onError / onTimeout 同样置位。编排在投递点与步骤间检查标志——**工具绝不因断连执行**
 * （副作用保护优先），模型调用已产生的 tokens 照常计量（计费不因传输结局消失）。
 */
@RestController
public class ChatController {

    /** SSE 超时（毫秒）；超时触发 onTimeout → 取消标志。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatOrchestrator orchestrator;

    /** 虚拟线程执行器（LLM 长阻塞 IO 的标准承载，ADR-002；虚拟线程为守护线程，随 JVM 退出）。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/api/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatAskRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // 身份在请求线程校验（缺失即 400，不经 SSE 通道）；跨线程显式传播（ADR-003）
        TenantContext.requireTenantIdAsLong();
        String userId = PrincipalContext.requireUserId();
        String orgId = PrincipalContext.orgId();
        TenantContext.Snapshot tenantSnapshot = TenantContext.snapshot();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean aborted = new AtomicBoolean(false);
        emitter.onError(throwable -> aborted.set(true));
        emitter.onTimeout(() -> aborted.set(true));

        executor.execute(() -> tenantSnapshot.runWith(() -> {
            PrincipalContext.setPrincipal(userId, orgId);
            try {
                orchestrator.chat(request, new SseChatStreamSink(emitter, aborted), aborted);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                PrincipalContext.clear();
            }
        }));
        return emitter;
    }

    /** SSE 输出口：写失败即置位取消标志（不抛出——编排以标志感知并终止，避免异常穿透流式循环）。 */
    private record SseChatStreamSink(SseEmitter emitter, AtomicBoolean aborted) implements ChatStreamSink {

        @Override
        public void step(ChatEvents.Step event) {
            send("step", event);
        }

        @Override
        public void token(ChatEvents.Token event) {
            send("token", event);
        }

        @Override
        public void citation(ChatEvents.Citation event) {
            send("citation", event);
        }

        @Override
        public void tool(ChatEvents.Tool event) {
            send("tool", event);
        }

        @Override
        public void outcome(ChatEvents.Outcome event) {
            send("outcome", event);
        }

        @Override
        public void cost(ChatEvents.Cost event) {
            send("cost", event);
        }

        @Override
        public void done(ChatEvents.Done event) {
            send("done", event);
        }

        private void send(String eventName, Object data) {
            if (aborted.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                // IOException：连接已断（写失败）——这是断连检测的实际触发点；
                // IllegalStateException：emitter 已完成 / 超时。两者统一为「取消」。
                aborted.set(true);
            }
        }
    }
}
