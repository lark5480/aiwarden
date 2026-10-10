package com.aiwarden.contract.chat;

import java.math.BigDecimal;

/**
 * 问答 SSE 事件契约（ADR-012 决策 6：7 类事件；SSE event 名 = 记录类名小写）。
 *
 * <p><b>事件序列</b>：`step*`（时间线逐步推进）→ `citation*`（检索后一次性下发）→
 * `token*`（模型流式）→ `tool*`（工具调用结果）→ `cost` → `outcome` → `done`。
 * 治理拒绝（deny）路径无 token / cost（未发生模型调用），以 `outcome` + `done` 收尾。
 *
 * <p><b>deny 的产生规则</b>（ADR-012 决策 7）：由治理管道映射——
 * `SecurityException`（可见集空 / 工具不可见）/ `QuotaExceededException` / `RateLimitExceededException`；
 * `human_handoff` = 工具 `PENDING_APPROVAL`（二态审批挂起）。**deny 是治理行为，不是模型台词。**
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    /**
     * 时间线步骤（FR-APP-03）：`visibility` / `retrieval` / `prompt` / `model` / `metering` / `tool`。
     *
     * @param step      步骤标识
     * @param elapsedMs 本步耗时（毫秒）
     * @param detail    人类可读明细（如 hits=5 / promptTokens=120）
     */
    public record Step(String step, long elapsedMs, String detail) {
    }

    /** 流式回答文本块。 */
    public record Token(String text) {
    }

    /**
     * 引用溯源（FR-RET-02）：docId + chunkId + 片段原文 + 相似度（1 - 余弦距离）。
     */
    public record Citation(long docId, long chunkId, String snippet, double score) {
    }

    /**
     * 工具调用结果（经 P3 管道）。
     *
     * @param status       SUCCEEDED / FAILED / PENDING_APPROVAL / REJECTED
     * @param replayed     true = 重放首次结果，未重复执行
     * @param invocationId 调用记录 id（需确认挂起时 C 端凭此批准 / 驳回，FR-APP-05）
     * @param detail       错误或说明（可空）
     */
    public record Tool(String tool, String status, boolean replayed, Long invocationId, String detail) {
    }

    /** 本次问答的结局。 */
    public record Outcome(String outcome, String reason) {

        /** 正常回答（含工具业务失败——失败走补偿，终态由数据库断言覆盖）。 */
        public static final String ANSWER = "answer";

        /** 治理拒绝（可见集空 / 工具不可见 / 配额超限 / 限流）。 */
        public static final String DENY = "deny";

        /** 人工交接（写操作工具二态审批挂起，待批准 / 驳回）。 */
        public static final String HUMAN_HANDOFF = "human_handoff";
    }

    /**
     * 本次成本（FR-APP-04；P4a 的用户可见性）。
     *
     * @param cost 估算成本（演示单价口径，元；ADR-012「代价与放弃」：真实价目表接入后替换）
     */
    public record Cost(int promptTokens, int completionTokens, BigDecimal cost, long latencyMs) {
    }

    /** 结束（携带服务端会话标识：请求未带 sessionId 时由此处返回，供后续轮次与工具幂等复用）。 */
    public record Done(String sessionId) {
    }
}
