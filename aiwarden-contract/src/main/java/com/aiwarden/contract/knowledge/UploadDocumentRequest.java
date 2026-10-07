package com.aiwarden.contract.knowledge;

/**
 * 上传文档请求（FR-KB-02）。
 *
 * <p>M1 演示替身：直接携带文本内容（不做文件存储 / OCR / 复杂解析，PRD §10 明确排除）。
 */
public record UploadDocumentRequest(String name, String content) {
}
