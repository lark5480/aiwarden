/**
 * 问答接口（C 端）。
 * 契约：aiwarden-contract/.../chat/ChatAskRequest.java、chat/ChatEvents.java
 * 端点：aiwarden-agent/.../api/ChatController.java:46
 */
import { postSse, type SseFrame } from './client'

/** ChatAskRequest：{ message, sessionId?, kbId?, businessKey? } */
export interface ChatAskRequest {
  message: string
  sessionId?: string
  kbId?: number
  businessKey?: string
}

/** ChatEvents.Step：step ∈ visibility|retrieval|prompt|model|metering|tool */
export interface StepEvent {
  step: string
  elapsedMs: number
  detail: string
}

/** ChatEvents.Token */
export interface TokenEvent {
  text: string
}

/** ChatEvents.Citation：docId + chunkId + 片段原文 + 相似度（1 - 余弦距离） */
export interface CitationEvent {
  docId: number
  chunkId: number
  snippet: string
  score: number
}

/** ChatEvents.Tool：status ∈ SUCCEEDED|FAILED|PENDING_APPROVAL|REJECTED */
export interface ToolEvent {
  tool: string
  status: string
  replayed: boolean
  invocationId: number | null
  detail: string | null
}

/** ChatEvents.Outcome：outcome ∈ answer|deny|human_handoff */
export interface OutcomeEvent {
  outcome: string
  reason: string | null
}

/** ChatEvents.Cost（cost 为演示单价口径的估算金额，元） */
export interface CostEvent {
  promptTokens: number
  completionTokens: number
  cost: number
  latencyMs: number
}

/** ChatEvents.Done */
export interface DoneEvent {
  sessionId: string
}

/** SSE event 名 = 记录类名小写，共 7 类（ChatEvents.java 注释 + ChatController.send 调用点）。 */
export const SSE_EVENT_NAMES = ['step', 'token', 'citation', 'tool', 'outcome', 'cost', 'done'] as const

export interface ChatStreamHandlers {
  step?: (event: StepEvent) => void
  token?: (event: TokenEvent) => void
  citation?: (event: CitationEvent) => void
  tool?: (event: ToolEvent) => void
  outcome?: (event: OutcomeEvent) => void
  cost?: (event: CostEvent) => void
  done?: (event: DoneEvent) => void
  /** 未知事件名 / 非 JSON 帧：不静默丢弃，交调用方展示 */
  unknown?: (frame: SseFrame) => void
}

export async function ask(
  payload: ChatAskRequest,
  signal: AbortSignal,
  handlers: ChatStreamHandlers,
): Promise<void> {
  await postSse('/api/v1/chat', payload, signal, (frame) => {
    switch (frame.event) {
      case 'step':
        handlers.step?.(frame.data as StepEvent)
        return
      case 'token':
        handlers.token?.(frame.data as TokenEvent)
        return
      case 'citation':
        handlers.citation?.(frame.data as CitationEvent)
        return
      case 'tool':
        handlers.tool?.(frame.data as ToolEvent)
        return
      case 'outcome':
        handlers.outcome?.(frame.data as OutcomeEvent)
        return
      case 'cost':
        handlers.cost?.(frame.data as CostEvent)
        return
      case 'done':
        handlers.done?.(frame.data as DoneEvent)
        return
      default:
        handlers.unknown?.(frame)
    }
  })
}
