/**
 * B 端用量与评测接口。
 */
import { getJson, getText } from './client'

/** UsageRecordResponse（contract/governance/UsageRecordResponse.java:9）
 *  端点：GET /api/v1/admin/usage（governance/api/UsageController.java:29-35）
 *  参数：sessionId / stepNo / tool / from / to（ISO-8601 OffsetDateTime 字符串）/ limit（1–500，缺省 100） */
export interface UsageRecord {
  id: number
  sessionId: string
  stepNo: number | null
  tool: string | null
  model: string | null
  promptTokens: number
  completionTokens: number
  latencyMs: number | null
  createdAt: string
}

export interface UsageQuery {
  sessionId?: string
  stepNo?: number | string
  tool?: string
  from?: string
  to?: string
  limit?: number | string
}

export function getUsage(query: UsageQuery): Promise<UsageRecord[]> {
  return getJson<UsageRecord[]>('/api/v1/admin/usage', { ...query })
}

/** EvalReportResponse（contract/governance/EvalReportResponse.java:21）
 *  端点：GET /api/v1/admin/eval/report（governance/api/EvalReportController.java:36）
 *  detail 为 List<Map<String,Object>>，元素形状见 EvalCaseResult.java:13 */
export interface EvalReport {
  runAt: string
  total: number
  passed: number
  denyTotal: number
  denyBlocked: number
  duplicateTickets: number
  p95LatencyMs: number
  avgCost: number
  detail: EvalCaseDetail[]
}

/** EvalCaseResult.java:13-15（经 Map<String,Object> 序列化，字段名同 record 组件名） */
export interface EvalCaseDetail {
  id: string
  category: string
  passed: boolean
  expectOutcome: string
  actualOutcome: string
  failures: string[] | null
  latencyMs: number
  cost: number
  excessTickets: number
}

export function getEvalReport(): Promise<EvalReport> {
  return getJson<EvalReport>('/api/v1/admin/eval/report')
}

/**
 * 运维端点：Prometheus 文本暴露（application.yml:24-28 暴露 health,prometheus）。
 * 走同一个 Vite dev proxy（vite.config.ts 的 /actuator）。
 */
export function getPrometheusText(): Promise<string> {
  return getText('/actuator/prometheus')
}
