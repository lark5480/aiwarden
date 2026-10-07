package com.aiwarden.contract.knowledge;

/**
 * 文档删除响应（FR-KB-03）。
 *
 * <p>deleteTaskId 即摄入账本的幂等键 {@code "docId:version"}——删除任务以账本行标识，
 * 进度用 {@code GET /api/v1/documents/{docId}/status} 查询。
 */
public record DocumentDeleteResponse(String deleteTaskId, long docId) {
}
