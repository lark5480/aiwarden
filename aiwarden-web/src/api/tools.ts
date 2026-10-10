/**
 * 工具面与人工确认接口。
 * 契约：contract/agent/ToolVisibilityResponse.java、ToolInvokeResponse.java
 * 端点：agent/api/ToolInvocationController.java:42（GET /tools）、:49（approve）、:55（reject）
 */
import { getJson, postJson } from './client'

/** ToolVisibilityResponse（ToolVisibilityResponse.java:9）：只含装配期白名单内的工具 */
export interface ToolVisibility {
  tools: string[]
}

/** ToolInvokeResponse（ToolInvokeResponse.java:16）：
 * status ∈ PROCESSING|SUCCEEDED|FAILED|PENDING_APPROVAL|REJECTED（后两者由二态审批产生——FR-TOOL-03） */
export interface ToolInvokeResult {
  invocationId: number
  tool: string
  status: string
  result: Record<string, unknown> | null
  error: string | null
  idemKey: string
  replayed: boolean
}

export function getVisibleTools(): Promise<ToolVisibility> {
  return getJson<ToolVisibility>('/api/v1/agent/tools')
}

/** FR-APP-05：批准需确认的挂起调用（从输入快照恢复执行）。**无请求体**。 */
export function approveToolInvocation(id: number): Promise<ToolInvokeResult> {
  return postJson<ToolInvokeResult>(`/api/v1/agent/tool-invocations/${id}/approve`)
}

/** FR-APP-05：驳回挂起调用（REJECTED 终态，不执行、不触发补偿）。**无请求体**。 */
export function rejectToolInvocation(id: number): Promise<ToolInvokeResult> {
  return postJson<ToolInvokeResult>(`/api/v1/agent/tool-invocations/${id}/reject`)
}
