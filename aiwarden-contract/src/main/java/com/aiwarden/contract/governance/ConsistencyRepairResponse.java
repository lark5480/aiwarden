package com.aiwarden.contract.governance;

/**
 * 一致性修复响应（对账「一键重试」）：显式修复的两类动作各返回计数。
 *
 * @param repairedDocuments 补做产物清理的残留文档数（先向量后切片，账本置 DELETED）
 * @param redrivenEvents    重驱动的**当前版本永久失败索引事件**数（{@code FAILED → PENDING}，交回 relay 重发；
 *                          删除事件与旧版本索引事件不重驱动；消费端按 {@code (docId, version)} 幂等仲裁，
 *                          并有当前版本守卫）
 */
public record ConsistencyRepairResponse(int repairedDocuments, int redrivenEvents) {
}
