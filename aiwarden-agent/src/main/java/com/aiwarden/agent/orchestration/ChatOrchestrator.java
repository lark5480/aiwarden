package com.aiwarden.agent.orchestration;

import com.aiwarden.agent.checkpoint.AgentDagEngine;
import com.aiwarden.contract.chat.ChatAskRequest;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答编排入口（M3 / ADR-012；M4 起由确定性 DAG + 检查点承载，FR-CKPT-01）。
 *
 * <p>保留这个门面而不是让调用方直接依赖引擎：评测 / C 端 / 测试都只认
 * {@link #chat(ChatAskRequest, ChatStreamSink, AtomicBoolean)} 这一条语义，
 * 内部从「线性编排」升级为「每步可检查点的 DAG」不改变调用契约。
 *
 * <p>链路与治理边界不变：可见集计算 → 检索下推 → Prompt 组装 → 模型生成 → 计量 →
 * 工具调用（真实 P3 管道）→ 成本 → 结局。取消语义也不变：断连后工具绝不执行、
 * 已产生的模型 tokens 照常计量。
 */
@Service
public class ChatOrchestrator {

    private final AgentDagEngine engine;

    public ChatOrchestrator(AgentDagEngine engine) {
        this.engine = engine;
    }

    /**
     * 执行一次问答编排。
     *
     * @param request 问答请求（身份从 TenantContext / PrincipalContext 取——缺失即拒绝）
     * @param sink    事件出口（SSE 实现 / 测试收集实现）
     * @param aborted 取消标志（传输层置位；编排在投递点与步骤间检查）
     */
    public OrchestrationResult chat(ChatAskRequest request, ChatStreamSink sink, AtomicBoolean aborted) {
        return engine.run(request, sink, aborted);
    }
}
