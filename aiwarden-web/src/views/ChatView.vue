<script setup lang="ts">
/**
 * C 端问答页（切片③）：PRD §5.11 FR-APP-01~05。
 *  01 对话式问答：流式输出 + Markdown 渲染 + 可中断
 *  02 引用溯源：citation 事件 → 侧栏清单，可点开看 snippet / docId / chunkId / score
 *  03 步骤时间线：step 事件按到达顺序渲染（中文可读名 + elapsedMs）
 *  04 本次成本：cost 事件（promptTokens / completionTokens / cost / latencyMs）
 *  05 人工确认卡片：tool.status === 'PENDING_APPROVAL' → approve / reject
 *
 * deny 路径没有 token / cost 事件（ChatEvents.java:10），本页不假设事件齐全。
 */
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownBlock from '@/components/MarkdownBlock.vue'
import { ask, type CitationEvent, type CostEvent, type ToolEvent } from '@/api/chat'
import { ApiError } from '@/api/client'
import { approveToolInvocation, getVisibleTools, rejectToolInvocation } from '@/api/tools'

/** step 标识 → 中文可读名（取值全集见 ChatEvents.java:22 与 ChatOrchestrator 的调用点）。 */
const STEP_LABELS: Record<string, string> = {
  visibility: '可见集计算',
  retrieval: '检索（下推）',
  prompt: 'Prompt 组装',
  model: '模型生成',
  metering: '计量落库',
  tool: '工具调用',
}

const TOOL_STATUS_LABELS: Record<string, string> = {
  SUCCEEDED: '执行成功',
  FAILED: '执行失败',
  PENDING_APPROVAL: '待人工确认',
  REJECTED: '已驳回',
}

const OUTCOME_LABELS: Record<string, string> = {
  answer: '正常回答',
  deny: '治理拒绝',
  human_handoff: '人工交接（工具待确认）',
}

interface TimelineEntry {
  step: string
  elapsedMs: number
  detail: string
  seq: number
}

interface Message {
  id: number
  role: 'user' | 'assistant'
  text: string
  citations: CitationEvent[]
  timeline: TimelineEntry[]
  tools: ToolEvent[]
  cost: CostEvent | null
  outcome: string | null
  outcomeReason: string | null
  aborted: boolean
  failed: string | null
}

const input = ref('')
const messages = ref<Message[]>([])
const streaming = ref(false)
const actionError = ref<string | null>(null)
const sessionId = ref<string | null>(null)
const lastUserMessage = ref<string | null>(null)
const kbId = ref<string>('')
const businessKey = ref<string>('')

let controller: AbortController | null = null
let messageSeq = 0
let stepSeq = 0

const messageList = ref<HTMLElement | null>(null)

const tools = ref<string[]>([])
const toolsError = ref<string | null>(null)
const toolsLoaded = ref(false)
const toolsLoading = ref(false)
const showTools = ref(false)

const approval = reactive({
  pending: false,
  invocationId: null as number | null,
  tool: '',
  detail: '',
  busy: false,
  result: null as string | null,
  error: null as string | null,
})

const canSend = computed(() => !streaming.value && input.value.trim() !== '')
const lastAssistant = computed<Message | null>(() => {
  for (let i = messages.value.length - 1; i >= 0; i -= 1) {
    if (messages.value[i].role === 'assistant') {
      return messages.value[i]
    }
  }
  return null
})

function newTurn(): Message {
  messageSeq += 1
  return {
    id: messageSeq,
    role: 'assistant',
    text: '',
    citations: [],
    timeline: [],
    tools: [],
    cost: null,
    outcome: null,
    outcomeReason: null,
    aborted: false,
    failed: null,
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const element = messageList.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

async function send(): Promise<void> {
  const text = input.value.trim()
  if (text === '' || streaming.value) {
    return
  }
  actionError.value = null
  approval.pending = false
  approval.result = null
  approval.error = null

  messageSeq += 1
  messages.value.push({
    id: messageSeq,
    role: 'user',
    text,
    citations: [],
    timeline: [],
    tools: [],
    cost: null,
    outcome: null,
    outcomeReason: null,
    aborted: false,
    failed: null,
  })
  const turn = newTurn()
  messages.value.push(turn)
  input.value = ''
  lastUserMessage.value = text
  streaming.value = true
  stepSeq = 0
  controller = new AbortController()
  await scrollToBottom()

  try {
    await ask(
      {
        message: text,
        sessionId: sessionId.value ?? undefined,
        kbId: kbId.value.trim() === '' ? undefined : Number(kbId.value.trim()),
        businessKey: businessKey.value.trim() === '' ? undefined : businessKey.value.trim(),
      },
      controller.signal,
      {
        step: (event) => {
          stepSeq += 1
          turn.timeline.push({ ...event, seq: stepSeq })
          void scrollToBottom()
        },
        token: (event) => {
          turn.text += event.text
          void scrollToBottom()
        },
        citation: (event) => {
          turn.citations.push(event)
        },
        tool: (event) => {
          turn.tools.push(event)
          if (event.status === 'PENDING_APPROVAL' && event.invocationId !== null) {
            approval.pending = true
            approval.invocationId = event.invocationId
            approval.tool = event.tool
            approval.detail = event.detail ?? ''
            approval.result = null
            approval.error = null
          }
        },
        outcome: (event) => {
          turn.outcome = event.outcome
          turn.outcomeReason = event.reason
        },
        cost: (event) => {
          turn.cost = event
        },
        done: (event) => {
          sessionId.value = event.sessionId
        },
        unknown: (frame) => {
          actionError.value = `收到未知 SSE 事件「${frame.event}」：${JSON.stringify(frame.data)}`
        },
      },
    )
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      turn.aborted = true
    } else if (error instanceof ApiError) {
      turn.failed = error.message
      actionError.value = `请求失败（HTTP ${error.status}）：${error.message}`
    } else {
      turn.failed = error instanceof Error ? error.message : String(error)
      actionError.value = `请求失败：${turn.failed}`
    }
  } finally {
    streaming.value = false
    controller = null
    await scrollToBottom()
  }
}

/** 中断（FR-APP-01）：AbortController.abort() 断连——后端据此停止后续步骤、工具不执行。 */
function abort(): void {
  controller?.abort()
}

async function loadTools(): Promise<void> {
  toolsLoading.value = true
  toolsError.value = null
  try {
    const response = await getVisibleTools()
    tools.value = response.tools
    toolsLoaded.value = true
  } catch (error) {
    tools.value = []
    toolsError.value = error instanceof ApiError ? `HTTP ${error.status}：${error.message}` : String(error)
  } finally {
    toolsLoading.value = false
  }
}

async function decide(approve: boolean): Promise<void> {
  const id = approval.invocationId
  if (id === null) {
    return
  }
  approval.busy = true
  approval.error = null
  try {
    const result = approve ? await approveToolInvocation(id) : await rejectToolInvocation(id)
    approval.result = `调用 ${result.invocationId} · 工具 ${result.tool} · 状态 ${result.status}${
      result.replayed ? '（重放首见终态，未重复执行）' : ''
    }${result.error ? ` · 错误：${result.error}` : ''}`
    approval.pending = false
    ElMessage.success(approve ? '已批准，后端从输入快照恢复执行' : '已驳回（REJECTED 终态，不执行、不触发补偿）')
  } catch (error) {
    approval.error = error instanceof ApiError ? `HTTP ${error.status}：${error.message}` : String(error)
  } finally {
    approval.busy = false
  }
}

function retry(): void {
  if (lastUserMessage.value === null) {
    return
  }
  input.value = lastUserMessage.value
  void send()
}

function costText(cost: CostEvent): string {
  return `prompt ${cost.promptTokens} / completion ${cost.completionTokens} tokens · 成本 ¥${Number(cost.cost).toFixed(
    6,
  )} · 模型耗时 ${cost.latencyMs} ms`
}

function statusTagType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'REJECTED') return 'danger'
  if (status === 'PENDING_APPROVAL') return 'warning'
  return 'info'
}

function scrollToCitation(index: number): void {
  document.getElementById(`citation-${index}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

onMounted(() => {
  void loadTools()
})
</script>

<template>
  <div class="chat">
    <section class="chat__main">
      <header class="chat__head">
        <div>
          <h1 class="aw-page__title">C 端问答</h1>
          <p class="aw-page__subtitle">
            POST <span class="aw-mono">/api/v1/chat</span> · SSE 流式（7 类事件：step / token / citation / tool /
            outcome / cost / done）。本页把可见集、步骤耗时、单次成本、引用溯源这些治理产物显示出来。
          </p>
        </div>
        <div class="chat__head-right">
          <el-tag v-if="sessionId" type="info" effect="plain" size="small" class="aw-mono">
            session {{ sessionId }}
          </el-tag>
          <el-tag v-else type="info" effect="plain" size="small">会话：由服务端在 done 事件返回</el-tag>
        </div>
      </header>

      <el-alert
        v-if="actionError"
        type="error"
        :closable="true"
        show-icon
        class="chat__alert"
        @close="actionError = null"
      >
        <template #title>接口返回的错误（原文）</template>
        <div class="chat__alert-body aw-mono">{{ actionError }}</div>
      </el-alert>

      <div ref="messageList" class="chat__messages">
        <el-empty v-if="messages.length === 0" description="还没有对话。输入问题后，回答、步骤时间线与引用会一起出现。" />

        <div v-for="message in messages" :key="message.id" class="chat__message" :class="`chat__message--${message.role}`">
          <div class="chat__role">{{ message.role === 'user' ? '我' : '助手' }}</div>
          <div class="chat__bubble">
            <template v-if="message.role === 'user'">
              <div class="chat__user-text">{{ message.text }}</div>
            </template>
            <template v-else>
              <div v-if="message.failed" class="chat__failed">
                <el-alert type="error" :closable="false" show-icon>
                  <template #title>本次请求失败（后端原文）</template>
                  <div class="aw-mono">{{ message.failed }}</div>
                </el-alert>
                <el-button v-if="lastUserMessage" size="small" class="chat__retry" @click="retry">重试这条问题</el-button>
              </div>

              <MarkdownBlock
                v-if="message.text !== ''"
                :source="message.text"
                :citation-count="message.citations.length"
                @pick-citation="scrollToCitation"
              />
              <div v-else-if="streaming && !message.outcome" class="aw-muted">等待模型输出…</div>

              <div v-if="message.aborted" class="chat__note">
                <el-tag type="warning" size="small" effect="plain">已中断</el-tag>
                <span class="aw-muted">客户端断连，后端停止后续步骤（工具不执行）</span>
              </div>

              <div v-if="message.outcome" class="chat__note">
                <el-tag
                  :type="message.outcome === 'answer' ? 'success' : message.outcome === 'deny' ? 'danger' : 'warning'"
                  size="small"
                  effect="plain"
                >
                  结局：{{ OUTCOME_LABELS[message.outcome] ?? message.outcome }}
                </el-tag>
                <span v-if="message.outcomeReason" class="aw-muted">{{ message.outcomeReason }}</span>
                <span v-if="message.outcome === 'deny'" class="aw-muted">
                  （治理拒绝路径没有 token / cost 事件——未发生模型调用）
                </span>
              </div>

              <div v-if="message.tools.length > 0" class="chat__tools">
                <div v-for="(tool, index) in message.tools" :key="`${message.id}-tool-${index}`" class="chat__tool">
                  <el-tag :type="statusTagType(tool.status)" size="small">
                    {{ TOOL_STATUS_LABELS[tool.status] ?? tool.status }}
                  </el-tag>
                  <span class="aw-mono">{{ tool.tool }}</span>
                  <span class="aw-muted">invocationId={{ tool.invocationId ?? '—' }}</span>
                  <el-tag v-if="tool.replayed" size="small" type="info" effect="plain">重放首见终态</el-tag>
                  <span v-if="tool.detail" class="aw-muted">{{ tool.detail }}</span>
                </div>
              </div>

              <div v-if="message.timeline.length > 0" class="chat__timeline">
                <div class="chat__sub-title">步骤时间线（按到达顺序）</div>
                <div v-for="entry in message.timeline" :key="`${message.id}-step-${entry.seq}`" class="chat__step">
                  <span class="chat__step-idx">{{ entry.seq }}</span>
                  <span class="chat__step-name">{{ STEP_LABELS[entry.step] ?? entry.step }}</span>
                  <span class="aw-mono aw-muted">{{ entry.step }}</span>
                  <span class="chat__step-ms">{{ entry.elapsedMs }} ms</span>
                  <span v-if="entry.detail" class="aw-muted chat__step-detail">{{ entry.detail }}</span>
                </div>
              </div>

              <div v-if="message.cost" class="chat__cost">
                <div class="chat__sub-title">本次成本（演示单价口径）</div>
                <div class="aw-mono">{{ costText(message.cost) }}</div>
                <div class="aw-muted chat__cost-note">
                  金额由后端按 <span class="aw-mono">aiwarden.chat.cost.prompt-per-1k</span> /
                  <span class="aw-mono">completion-per-1k</span> 的演示单价估算，非真实价目表。
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <footer class="chat__composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入问题（Ctrl/⌘ + Enter 发送）"
          @keydown.enter.ctrl.prevent="send"
          @keydown.enter.meta.prevent="send"
        />
        <div class="chat__composer-row">
          <div class="chat__options">
            <el-input v-model="kbId" size="small" placeholder="kbId（可选，限定知识库）" class="chat__option-input" />
            <el-input v-model="businessKey" size="small" placeholder="businessKey（可选，幂等业务键）" class="chat__option-input" />
          </div>
          <div class="chat__composer-actions">
            <el-button v-if="streaming" type="danger" @click="abort">中断</el-button>
            <el-button v-else type="primary" :disabled="!canSend" @click="send">发送</el-button>
          </div>
        </div>
      </footer>
    </section>

    <aside class="chat__side">
      <el-card shadow="never" class="chat__card">
        <template #header>
          <div class="chat__card-head">
            <span>引用溯源</span>
            <el-tag size="small" type="info" effect="plain">{{ lastAssistant?.citations.length ?? 0 }} 条</el-tag>
          </div>
        </template>
        <el-empty
          v-if="!lastAssistant || lastAssistant.citations.length === 0"
          description="本轮没有 citation 事件（检索为空或该路径未检索）"
          :image-size="60"
        />
        <div v-else class="chat__citations">
          <el-collapse>
            <el-collapse-item
              v-for="(citation, index) in lastAssistant.citations"
              :key="citation.chunkId"
              :id="`citation-${index + 1}`"
              :name="String(index + 1)"
            >
              <template #title>
                <span class="chat__citation-title">
                  <span class="chat__citation-idx">[{{ index + 1 }}]</span>
                  <span class="aw-mono">doc {{ citation.docId }} / chunk {{ citation.chunkId }}</span>
                  <el-tag size="small" effect="plain">相似度 {{ Number(citation.score).toFixed(4) }}</el-tag>
                </span>
              </template>
              <div class="chat__snippet">{{ citation.snippet }}</div>
            </el-collapse-item>
          </el-collapse>
          <div class="aw-muted chat__side-note">
            回答中的 <span class="aw-mono">[序号]</span> 与上面的条目一一对应，可点击跳转；相似度 = 1 − 余弦距离。
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="chat__card">
        <template #header>
          <div class="chat__card-head">
            <span>人工确认（FR-APP-05）</span>
            <el-tag v-if="approval.pending" type="warning" size="small">待处理</el-tag>
          </div>
        </template>
        <el-empty
          v-if="!approval.pending && !approval.result && !approval.error"
          description="没有待确认的工具调用"
          :image-size="60"
        />
        <div v-else>
          <div v-if="approval.pending" class="chat__approval">
            <div>
              工具 <span class="aw-mono">{{ approval.tool }}</span> 处于
              <span class="aw-mono">PENDING_APPROVAL</span>，invocationId
              <span class="aw-mono">{{ approval.invocationId }}</span>。
            </div>
            <div v-if="approval.detail" class="aw-muted">{{ approval.detail }}</div>
            <div class="chat__approval-actions">
              <el-button type="primary" size="small" :loading="approval.busy" @click="decide(true)">批准执行</el-button>
              <el-button type="danger" size="small" :loading="approval.busy" @click="decide(false)">驳回</el-button>
            </div>
            <div class="aw-muted chat__side-note">
              该决定只作用于这次挂起的工具调用；本轮 SSE 流已结束（结局为 human_handoff），批准后后端从输入快照恢复执行。
            </div>
          </div>
          <el-alert v-if="approval.result" type="success" :closable="false" class="aw-mono">
            {{ approval.result }}
          </el-alert>
          <el-alert v-if="approval.error" type="error" :closable="false" class="chat__side-note">
            <template #title>确认接口返回的错误（原文）</template>
            <div class="aw-mono">{{ approval.error }}</div>
          </el-alert>
        </div>
      </el-card>

      <el-card shadow="never" class="chat__card">
        <template #header>
          <div class="chat__card-head">
            <span>当前可见工具面</span>
            <el-button size="small" text @click="showTools = !showTools">{{ showTools ? '收起' : '展开' }}</el-button>
          </div>
        </template>
        <div v-if="showTools">
          <div v-if="toolsLoading" class="aw-muted">读取中…</div>
          <template v-else>
            <el-alert v-if="toolsError" type="error" :closable="false">
              <template #title>GET /api/v1/agent/tools 失败（原文）</template>
              <div class="aw-mono">{{ toolsError }}</div>
            </el-alert>
            <el-empty v-else-if="toolsLoaded && tools.length === 0" description="可见工具面为空" :image-size="60" />
            <div v-else class="chat__tool-list">
              <el-tag v-for="tool in tools" :key="tool" class="aw-mono">{{ tool }}</el-tag>
            </div>
          </template>
        </div>
        <div v-else class="aw-muted">由 GET /api/v1/agent/tools 返回装配期白名单内的工具。</div>
      </el-card>
    </aside>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  gap: 12px;
  height: calc(100vh - 61px);
  padding: 12px;
}

.chat__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #fff;
  border: 1px solid var(--aw-border);
  border-radius: 8px;
  padding: 14px 16px;
}

.chat__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.chat__head-right {
  flex-shrink: 0;
}

.chat__alert {
  margin-top: 10px;
}

.chat__alert-body {
  line-height: 1.6;
  word-break: break-all;
}

.chat__messages {
  flex: 1;
  overflow-y: auto;
  margin-top: 12px;
  padding-right: 4px;
}

.chat__message {
  margin-bottom: 14px;
}

.chat__role {
  font-size: 12px;
  color: var(--aw-muted);
  margin-bottom: 4px;
}

.chat__message--user .chat__bubble {
  background: #ecf5ff;
  border: 1px solid #d9ecff;
}

.chat__bubble {
  border: 1px solid var(--aw-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: #fff;
}

.chat__user-text {
  white-space: pre-wrap;
  line-height: 1.7;
}

.chat__failed {
  margin-bottom: 8px;
}

.chat__retry {
  margin-top: 8px;
}

.chat__note {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
  font-size: 12px;
}

.chat__sub-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 6px;
}

.chat__tools,
.chat__timeline,
.chat__cost {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--aw-border);
}

.chat__tool {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  margin-bottom: 4px;
}

.chat__step {
  display: grid;
  grid-template-columns: 22px 130px 100px 80px 1fr;
  gap: 8px;
  align-items: baseline;
  font-size: 12px;
  padding: 3px 0;
}

.chat__step-idx {
  color: var(--aw-muted);
}

.chat__step-name {
  font-weight: 600;
}

.chat__step-ms {
  color: #409eff;
}

.chat__step-detail {
  word-break: break-all;
}

.chat__cost-note,
.chat__side-note {
  font-size: 12px;
  margin-top: 6px;
  line-height: 1.6;
}

.chat__composer {
  border-top: 1px solid var(--aw-border);
  padding-top: 10px;
  margin-top: 10px;
}

.chat__composer-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

.chat__options {
  display: flex;
  gap: 8px;
}

.chat__option-input {
  width: 220px;
}

.chat__side {
  width: 400px;
  flex-shrink: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat__card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.chat__citation-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.chat__citation-idx {
  color: #409eff;
  font-weight: 600;
}

.chat__snippet {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 13px;
  background: var(--aw-bg-soft);
  border-radius: 6px;
  padding: 8px 10px;
}

.chat__approval {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  line-height: 1.7;
}

.chat__approval-actions {
  display: flex;
  gap: 8px;
}

.chat__tool-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
