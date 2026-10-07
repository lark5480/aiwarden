package com.aiwarden.contract.knowledge;

/**
 * 文档上传响应（FR-KB-02：返回 docId 与 version；摄入异步，状态从 PENDING 起）。
 */
public record DocumentUploadResponse(long docId, int version, String status) {
}
