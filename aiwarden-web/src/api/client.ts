/**
 * 薄 fetch 客户端（不引 axios）。
 *
 * 职责：API base 拼接、身份头注入、错误体解析（后端 ProblemDetail → detail 原文）、
 * SSE 手工解析（EventSource 不能发 POST，故 /api/v1/chat 必须走 fetch + ReadableStream）。
 */
import { identityHeaders } from '@/stores/identity'

/** API 基地址：缺省空串走 Vite dev proxy；可用 VITE_API_BASE 覆盖。 */
export const API_BASE: string = (import.meta.env.VITE_API_BASE ?? '').replace(/\/+$/, '')

/** 后端错误（ProblemDetail 形态：{ status, detail, title, ... }）——detail 即后端原文。 */
export class ApiError extends Error {
  readonly status: number
  readonly raw: unknown

  constructor(status: number, message: string, raw: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.raw = raw
  }
}

export type QueryValue = string | number | boolean | null | undefined

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = `${API_BASE}${path}`
  if (!query) {
    return url
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') {
      continue
    }
    params.append(key, String(value))
  }
  const qs = params.toString()
  return qs === '' ? url : `${url}?${qs}`
}

/**
 * 把响应体解析成后端原文提示。
 * 后端统一用 ProblemDetail（aiwarden-start/.../api/ApiExceptionHandler.java），
 * 字段名 `detail`；非 ProblemDetail 时退回文本 / 原始 statusText。
 */
async function describeError(response: Response): Promise<string> {
  const text = await response.text().catch(() => '')
  if (text.trim() !== '') {
    try {
      const body = JSON.parse(text) as Record<string, unknown>
      const detail = body['detail']
      if (typeof detail === 'string' && detail.trim() !== '') {
        return detail
      }
      const message = body['message']
      if (typeof message === 'string' && message.trim() !== '') {
        return message
      }
      return text
    } catch {
      return text
    }
  }
  return `HTTP ${response.status} ${response.statusText}`.trim()
}

export interface RequestOptions {
  method?: string
  query?: Record<string, QueryValue>
  /** JSON 请求体；与 body 互斥 */
  json?: unknown
  body?: BodyInit
  headers?: Record<string, string>
  signal?: AbortSignal
  /** 额外响应处理（SSE 用） */
  accept?: string
}

/** 发一次请求并返回原始 Response（已检查 HTTP 状态）。 */
export async function rawRequest(path: string, options: RequestOptions = {}): Promise<Response> {
  const headers: Record<string, string> = { ...identityHeaders(), ...(options.headers ?? {}) }
  if (options.accept) {
    headers['Accept'] = options.accept
  }
  let body = options.body
  if (options.json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.json)
  }

  const response = await fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body,
    signal: options.signal,
  })

  if (!response.ok) {
    throw new ApiError(response.status, await describeError(response), null)
  }
  return response
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await rawRequest(path, options)
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  if (text.trim() === '') {
    return undefined as T
  }
  return JSON.parse(text) as T
}

export function getJson<T>(path: string, query?: Record<string, QueryValue>): Promise<T> {
  return request<T>(path, { query })
}

export function postJson<T>(path: string, json?: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', json: json ?? {} })
}

/** 取纯文本响应（Prometheus 文本暴露格式用）。 */
export async function getText(path: string): Promise<string> {
  const response = await rawRequest(path, { accept: 'text/plain' })
  return response.text()
}

/** SSE 一帧：event 名 + 已解析的 data。 */
export interface SseFrame {
  event: string
  data: unknown
}

/**
 * POST + 手工解析 SSE 流。
 *
 * 帧格式（Spring `SseEmitter.event().name(x).data(obj)`，见
 * aiwarden-agent/.../api/ChatController.java:114-125）：`event:<名字>\ndata:<JSON>\n\n`。
 * 这里按 SSE 规范容错：data 允许多行（拼接）、允许 CRLF、忽略注释与其它字段。
 *
 * 错误处理：非 2xx 直接抛 ApiError（后端身份缺失 / 参数错误走此路径，不经 SSE 通道）。
 * 中断：调用方传 AbortSignal，abort 后 fetch 抛 AbortError——后端检测断连即停止后续步骤
 * （ChatController 的 sink 写失败置位取消标志），工具不会执行。
 */
export async function postSse(
  path: string,
  json: unknown,
  signal: AbortSignal,
  onFrame: (frame: SseFrame) => void,
): Promise<void> {
  const response = await rawRequest(path, {
    method: 'POST',
    json,
    signal,
    accept: 'text/event-stream',
    headers: { Accept: 'text/event-stream' },
  })
  if (!response.body) {
    throw new ApiError(response.status, '响应没有可读流（浏览器不支持 ReadableStream 或后端未返回流）', null)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      buffer = drainFrames(buffer, onFrame)
    }
    buffer += decoder.decode()
    if (buffer.trim() !== '') {
      drainFrames(`${buffer}\n\n`, onFrame)
    }
  } finally {
    reader.releaseLock()
  }
}

/** 从缓冲区中取出完整帧，返回剩余尾部。 */
function drainFrames(buffer: string, onFrame: (frame: SseFrame) => void): string {
  let rest = buffer
  for (;;) {
    const match = /\r?\n\r?\n/.exec(rest)
    if (!match || match.index === undefined) {
      return rest
    }
    const block = rest.slice(0, match.index)
    rest = rest.slice(match.index + match[0].length)
    const frame = parseFrame(block)
    if (frame) {
      onFrame(frame)
    }
  }
}

function parseFrame(block: string): SseFrame | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (line === '' || line.startsWith(':')) {
      continue
    }
    const sep = line.indexOf(':')
    const field = sep === -1 ? line : line.slice(0, sep)
    let value = sep === -1 ? '' : line.slice(sep + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  const raw = dataLines.join('\n')
  let data: unknown = raw
  try {
    data = JSON.parse(raw)
  } catch {
    // 非 JSON 帧：保留原文，交由调用方显示（不静默丢）
  }
  return { event, data }
}
