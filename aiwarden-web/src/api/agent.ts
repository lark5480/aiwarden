/**
 * Agent 运行 / 检查点查询（M4 / FR-CKPT-02、FR-ADM-03）。
 *
 * 数据源：GET /api/v1/agent/runs/by-trace/{traceId}（AgentRunController）。
 * 返回的是**数据库里的检查点时间线**（每步耗时 / 版本 / 状态）；
 * span 明细（父子关系、属性）在同一 traceId 下由 Tempo 提供（Grafana → Explore）。
 */
import { getJson } from './client'

export interface AgentStepView {
  stepNo: number
  name: string
  status: string
  version: number
  elapsedMs: number
  detail: string | null
  traceId: string | null
}

export interface AgentRunView {
  runId: number
  sessionId: string
  status: string
  lastStepNo: number
  version: number
  traceId: string | null
  outcome: string | null
  reason: string | null
  steps: AgentStepView[]
}

export function getAgentRunByTrace(traceId: string): Promise<AgentRunView> {
  return getJson<AgentRunView>(`/api/v1/agent/runs/by-trace/${encodeURIComponent(traceId)}`)
}
