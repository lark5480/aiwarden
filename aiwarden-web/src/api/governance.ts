/**
 * 后端 Java record → TS 类型镜像。字段名与 Java record 组件名**逐字一致**
 * （Jackson 直接按组件名序列化，改名即静默拿到 undefined）。
 */
import { getJson, postJson } from './client'

/** ConsistencyReportResponse（contract/governance/ConsistencyReportResponse.java:11）
 *  端点：GET /api/v1/admin/consistency/report（governance/api/ConsistencyController.java:28） */
export interface ConsistencyReport {
  reportId: number
  mismatchCount: number
  /** detailsJson 是**字符串**，需 JSON.parse；内含 staleDocuments/orphanVectors/stuckProcessing/scannedAt */
  detailsJson: string
  createdAt: string
}

/** detailsJson 解析后的形状（ConsistencyReconciler.runOnce() 的 Map.of 键，见 :107-111） */
export interface ConsistencyDetails {
  staleDocuments: StaleDocument[]
  orphanVectors: number
  stuckProcessing: number
  scannedAt: string
}

/** staleDocuments 元素 = SELECT c.doc_id, c.tenant_id, count(*) AS stale_chunks（:81-88） */
export interface StaleDocument {
  doc_id: number
  tenant_id: number
  stale_chunks: number
}

/** ConsistencyRepairResponse（contract/governance/ConsistencyRepairResponse.java:6）
 *  端点：POST /api/v1/admin/consistency/repair（ConsistencyController.java:43） */
export interface ConsistencyRepair {
  repairedDocuments: number
}

export function getConsistencyReport(): Promise<ConsistencyReport> {
  return getJson<ConsistencyReport>('/api/v1/admin/consistency/report')
}

/** POST /scan：同步跑一轮对账并返回最新报告（ConsistencyController.java:37-41） */
export function scanConsistency(): Promise<ConsistencyReport> {
  return postJson<ConsistencyReport>('/api/v1/admin/consistency/scan')
}

/** POST /repair：对残留文档补做产物清理（ConsistencyController.java:43-46） */
export function repairConsistency(): Promise<ConsistencyRepair> {
  return postJson<ConsistencyRepair>('/api/v1/admin/consistency/repair')
}

/** detailsJson 解析：坏 JSON 不伪造成空对象，抛错由调用方显示原文。 */
export function parseConsistencyDetails(detailsJson: string): ConsistencyDetails {
  return JSON.parse(detailsJson) as ConsistencyDetails
}

/**
 * 从 Prometheus 文本暴露格式中取孤儿向量 Gauge 的当前值。
 *
 * <p>指标注册处：ConsistencyReconciler.java:57-59 —— 注册名是 `aiwarden_vector_orphan_total`，
 * 但 **Micrometer 在导出时会剥掉 Gauge 名尾部的 `_total`**（`_total` 是 counter 的命名约定；
 * 实测该端点导出的是 `aiwarden_vector_orphan`）。PRD / README / ADR 引用的是**注册名**，
 * 本解析器两种形态都认，避免前端因导出名差异而永远采不到点（静默失效）。
 * 值是**最近一轮扫描的当前值**，不是历史序列——历史曲线必须由本页轮询采样自绘。
 * 端点暴露：aiwarden-start/src/main/resources/application.yml（include: health,prometheus）。
 * 覆盖样本行形态：`<name> 4.0` 与 `<name>{...} 4.0`（后者取行尾数值）。
 * 返回 null = 文本里没有该指标（不编造 0）。
 */
const ORPHAN_GAUGE_NAMES = ['aiwarden_vector_orphan_total', 'aiwarden_vector_orphan'] as const

export function parseOrphanGauge(text: string): { value: number; help: string | null } | null {
  let help: string | null = null
  let value: number | null = null
  for (const line of text.split(/\r?\n/)) {
    const helpName = ORPHAN_GAUGE_NAMES.find((name) => line.startsWith(`# HELP ${name}`))
    if (helpName !== undefined) {
      help = line.slice(`# HELP ${helpName}`.length).trim()
      continue
    }
    if (line.startsWith('#')) {
      continue
    }
    const isSample = ORPHAN_GAUGE_NAMES.some(
      (name) =>
        line === name || line.startsWith(`${name} `) || line.startsWith(`${name}{`),
    )
    if (!isSample) {
      continue
    }
    const parts = line.trim().split(/\s+/)
    const parsed = Number(parts[parts.length - 1])
    if (Number.isFinite(parsed)) {
      value = parsed
    }
  }
  return value === null ? null : { value, help }
}
