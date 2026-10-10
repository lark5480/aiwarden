package com.aiwarden.eval;

import java.util.List;

/**
 * 评测样本（FR-EVAL-01/02；ADR-012 决策 9）。
 *
 * <p><b>样本语义</b>：`messages` 按序在同一会话执行（前置消息用于构造状态，如「先建单再转派」）；
 * **断言只针对最后一条消息**的运行（含重放），前置消息的影响由 DB 终态断言覆盖。
 *
 * @param id            样本 id（唯一；报告与失败信息里引用）
 * @param category      类别（normal / injection / dangerous / repeated_submit / cross_tenant / ...）
 * @param messages      用户消息序列（≥1；同会话顺序执行）
 * @param session       会话标识（工具幂等键成分——重放样本依赖它稳定）
 * @param kb            知识库逻辑引用（null=不限 / "FOREIGN"=跨租户 / "MISSING"=不存在 / "ORG"=组织限定）；
 *                      由门禁驱动的环境映射解析——样本不绑定具体自增 id
 * @param expectOutcome 期望结局：answer / deny / human_handoff（ADR-012 决策 7 的产生规则）
 * @param forbidTools   期望「零调用记录」的工具（forbid_tools；拦截必须发生在调用入口）
 * @param ticketRefs    涉及的单号（max_tickets 断言的对象）
 * @param expectedDocs  期望出现在引用中的文档（逻辑名，由驱动的环境映射解析）。
 *                      <b>使用前提</b>：查询与文档文本**完全一致**——演示期确定性伪嵌入不保留
 *                      语义相似度（SHA-256 播种随机向量，仅同文本距离为 0）；「语义相关必命中」
 *                      属 B1 检索质量域，不在治理门禁断言范围（ADR-012）
 * @param maxTickets    单个单号的工单数上限（期望值；重复提交样本 = 1）
 * @param minCitations  引用数下限（0 = 不约束）
 * @param replayTimes   最后一条消息的执行次数（>1 时断言重放命中幂等 replayed=true）
 */
public record EvalCase(String id, String category, List<String> messages, String session,
                       String kb, String expectOutcome, List<String> forbidTools,
                       List<String> ticketRefs, List<String> expectedDocs, Integer maxTickets,
                       Integer minCitations, Integer replayTimes) {

    public EvalCase {
        // JSON 缺省字段归一化（样本文件可只写关心的字段）
        forbidTools = forbidTools == null ? List.of() : List.copyOf(forbidTools);
        ticketRefs = ticketRefs == null ? List.of() : List.copyOf(ticketRefs);
        expectedDocs = expectedDocs == null ? List.of() : List.copyOf(expectedDocs);
        maxTickets = maxTickets == null ? 0 : maxTickets;
        minCitations = minCitations == null ? 0 : minCitations;
        replayTimes = replayTimes == null ? 1 : replayTimes;
    }
}
