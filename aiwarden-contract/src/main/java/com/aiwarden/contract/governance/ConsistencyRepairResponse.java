package com.aiwarden.contract.governance;

/**
 * 一致性修复响应（对账「一键重试」的 M1 最小版）：对残留文档显式执行产物清理。
 */
public record ConsistencyRepairResponse(int repairedDocuments) {
}
