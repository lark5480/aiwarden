package com.aiwarden.contract.knowledge;

/**
 * 文档状态响应（FR-KB-04）：文档级状态 + 摄入账本明细（清理 / 索引进度可查）。
 *
 * @param status       文档状态：PENDING / INDEXED / DELETED / FAILED
 * @param ledgerStatus 账本状态：PROCESSING / INDEXED / DELETED / FAILED
 * @param error        最近一次失败原因（成功时为 null）
 */
public record DocumentStatusResponse(long docId, int version, String status,
                                     String ledgerStatus, String error) {
}
