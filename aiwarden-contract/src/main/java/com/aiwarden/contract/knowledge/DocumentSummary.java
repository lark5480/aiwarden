package com.aiwarden.contract.knowledge;

/**
 * 文档列表项（M4 / FR-ADM-02：B 端知识库管理页需要「看得到摄入状态」）。
 *
 * @param id        文档 id
 * @param name      文档名（同库内唯一；同名重传 = version + 1）
 * @param version   当前版本
 * @param status    PENDING / INDEXED / DELETED / FAILED
 * @param updatedAt 最近更新时间（ISO-8601 字符串，避免前端解析多种日期形态）
 */
public record DocumentSummary(long id, String name, int version, String status, String updatedAt) {
}
