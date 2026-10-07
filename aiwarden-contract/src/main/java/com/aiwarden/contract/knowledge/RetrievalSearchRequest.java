package com.aiwarden.contract.knowledge;

/**
 * 检索请求（M2：可见集四级下推）。
 *
 * @param query 查询文本
 * @param topK  Top-K（可空，默认 10，上限 50）
 * @param kbId  可选：限定在某个知识库内检索；不在可见集内（含不存在 / 跨租户 / 无权）时
 *              与「可见集为空」同语义——检索前拒绝（403），不可区分，不泄露存在性（ADR-008）
 */
public record RetrievalSearchRequest(String query, Integer topK, Long kbId) {
}
