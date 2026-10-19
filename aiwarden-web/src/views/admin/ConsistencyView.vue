<script setup lang="ts">
/**
 * B 端 · 一致性报告页（FR-ADM-03）。
 * 数据源：GET /api/v1/admin/consistency/report（ConsistencyController.java:28）
 *        POST /api/v1/admin/consistency/scan（:39）、POST /api/v1/admin/consistency/repair（:63）
 * detailsJson 是字符串，解析后为 { staleDocuments, orphanVectors, stuckIngest, failedOutboxEvents, scannedAt }
 * （ConsistencyReconciler.runOnce()）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import {
  getConsistencyReport,
  getConsistencyReports,
  parseConsistencyDetails,
  parseOrphanGauge,
  repairConsistency,
  scanConsistency,
  type ConsistencyDetails,
  type ConsistencyReport,
  type StaleDocument,
  type StuckIngestDocument,
} from '@/api/governance'
import { getAgentRunByTrace, type AgentRunView } from '@/api/agent'
import { getPrometheusText } from '@/api/admin'
import { ApiError } from '@/api/client'

// echarts 按需注册
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

const report = ref<ConsistencyReport | null>(null)
const details = ref<ConsistencyDetails | null>(null)
const detailsError = ref<string | null>(null)

const loading = ref(false)
const error = ref<{ status: number | null; message: string } | null>(null)
const scanning = ref(false)
const repairing = ref(false)
const repairResult = ref<string | null>(null)

/** 曲线：仅来自本页对 /actuator/prometheus 的轮询采样（指标本身只有当前值）。 */
interface Sample {
  at: string
  value: number
}
const samples = ref<Sample[]>([])
const sampling = ref(false)
const sampleError = ref<string | null>(null)
const autoSample = ref(false)
const sampleIntervalMs = ref(5000)
const gaugeHelp = ref<string | null>(null)
let timer: number | null = null

const staleDocuments = computed<StaleDocument[]>(() => details.value?.staleDocuments ?? [])
const stuckIngest = computed<StuckIngestDocument[]>(() => details.value?.stuckIngest ?? [])
/** repair 只处理残留产物和当前版本索引事件；账本/文档超时需摄入完成或人工重传。 */
const repairable = computed(
  () => staleDocuments.value.length > 0 || stuckIngest.value.some((row) => row.reason === 'OUTBOX_FAILED'),
)

/** 最近 N 轮扫描（M4）：用于区分「不一致清单为空」与「系统根本没在扫」。 */
const recentReports = ref<ConsistencyReport[]>([])
const recentLoading = ref(false)
const recentError = ref<string | null>(null)
const recentMismatchRounds = computed(() => recentReports.value.filter((item) => item.mismatchCount > 0).length)
const recentMismatchTotal = computed(() =>
  recentReports.value.reduce((sum, item) => sum + item.mismatchCount, 0),
)
const recentRange = computed(() => {
  if (recentReports.value.length === 0) {
    return null
  }
  const ids = recentReports.value.map((item) => item.reportId)
  return { newest: Math.max(...ids), oldest: Math.min(...ids), count: ids.length }
})

/** 按 traceId 查一次调用的检查点时间线（M4 / FR-ADM-03；span 明细在 Tempo）。 */
const traceIdInput = ref('')
const traceRun = ref<AgentRunView | null>(null)
const traceLoading = ref(false)
const traceError = ref<string | null>(null)

const chartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    valueFormatter: (value: unknown) => `${value}`,
  },
  grid: { left: 44, right: 16, top: 24, bottom: 28 },
  xAxis: {
    type: 'category',
    data: samples.value.map((sample) => sample.at),
    axisLabel: { fontSize: 10 },
  },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 10 } },
  series: [
    {
      name: 'aiwarden_vector_orphan_total（导出名 aiwarden_vector_orphan）',
      type: 'line',
      step: 'end',
      showSymbol: true,
      data: samples.value.map((sample) => sample.value),
    },
  ],
}))

function describe(error_: unknown): { status: number | null; message: string } {
  if (error_ instanceof ApiError) {
    return { status: error_.status, message: error_.message }
  }
  return { status: null, message: error_ instanceof Error ? error_.message : String(error_) }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const response = await getConsistencyReport()
    report.value = response
    try {
      details.value = parseConsistencyDetails(response.detailsJson)
      detailsError.value = null
    } catch (parseError) {
      details.value = null
      detailsError.value = `detailsJson 不是合法 JSON（原文如下），明细区无法渲染：${
        parseError instanceof Error ? parseError.message : String(parseError)
      }`
    }
  } catch (error_) {
    report.value = null
    details.value = null
    detailsError.value = null
    error.value = describe(error_)
  } finally {
    loading.value = false
  }
  void loadRecent()
}

/** 最近 N 轮扫描：空态上下文（M4）。接口失败不覆盖主报告的错误展示，单独提示。 */
async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    recentReports.value = await getConsistencyReports(20)
    recentError.value = null
  } catch (error_) {
    recentReports.value = []
    recentError.value = `GET /api/v1/admin/consistency/reports 失败：${describe(error_).message}`
  } finally {
    recentLoading.value = false
  }
}

/** 按 traceId 查一次调用的检查点时间线（M4 / FR-ADM-03）。 */
async function lookupTrace(): Promise<void> {
  const traceId = traceIdInput.value.trim()
  traceRun.value = null
  traceError.value = null
  if (traceId === '') {
    traceError.value = '请输入 traceId（32 位十六进制；C 端响应的 X-Trace-Id 头或运行视图里都有）'
    return
  }
  traceLoading.value = true
  try {
    traceRun.value = await getAgentRunByTrace(traceId)
  } catch (error_) {
    const described = describe(error_)
    traceError.value =
      described.status === 404
        ? `没有找到 traceId=${traceId} 的运行（当前租户内）；确认它来自本环境，且不是只存在于 Tempo 的外部 trace。`
        : `查询失败：${described.message}`
  } finally {
    traceLoading.value = false
  }
}

async function scan(): Promise<void> {
  scanning.value = true
  repairResult.value = null
  try {
    const response = await scanConsistency()
    report.value = response
    try {
      details.value = parseConsistencyDetails(response.detailsJson)
      detailsError.value = null
    } catch (parseError) {
      details.value = null
      detailsError.value = `detailsJson 不是合法 JSON：${parseError instanceof Error ? parseError.message : String(parseError)}`
    }
    error.value = null
    ElMessage.success('已触发一轮对账扫描（同步执行）')
    void loadRecent()
  } catch (error_) {
    error.value = describe(error_)
  } finally {
    scanning.value = false
  }
}

async function repair(): Promise<void> {
  repairing.value = true
  repairResult.value = null
  try {
    const response = await repairConsistency()
    const actions: string[] = []
    if (response.repairedDocuments > 0) {
      actions.push(`清理残留文档 ${response.repairedDocuments} 个（先向量后切片，账本置 DELETED）`)
    }
    if (response.redrivenEvents > 0) {
      actions.push(`重驱动当前版本失败索引事件 ${response.redrivenEvents} 条（FAILED → PENDING，交回 relay）`)
    }
    const summary =
      actions.length > 0
        ? `${actions.join('；')}。重驱动不等于收敛：需消费者补齐后才会从清单消失。`
        : '没有可执行的修复动作：LEDGER_PROCESSING / DOCUMENT_OVERDUE 需摄入完成或人工重传。'
    // repair 本身不落新报告；重扫一轮才能真正刷新清单与指标。
    await scan()
    repairResult.value = summary
    if (error.value === null) {
      if (actions.length > 0) {
        ElMessage.success('修复动作已提交')
      } else {
        ElMessage.info('没有可执行的修复动作')
      }
    }
  } catch (error_) {
    repairResult.value = `repair 失败：${describe(error_).message}`
  } finally {
    repairing.value = false
  }
}

async function sampleOnce(): Promise<void> {
  sampling.value = true
  try {
    const text = await getPrometheusText()
    const parsed = parseOrphanGauge(text)
    if (parsed === null) {
      sampleError.value = 'Prometheus 文本里既没有 aiwarden_vector_orphan_total（注册名）也没有 aiwarden_vector_orphan（Micrometer 导出去掉 _total 后缀后的名字）——该 Gauge 尚未注册，或对账组件未装配'
      return
    }
    sampleError.value = null
    gaugeHelp.value = parsed.help
    samples.value.push({ at: new Date().toLocaleTimeString('zh-CN', { hour12: false }), value: parsed.value })
    if (samples.value.length > 120) {
      samples.value.shift()
    }
  } catch (error_) {
    sampleError.value = `GET /actuator/prometheus 失败：${describe(error_).message}`
  } finally {
    sampling.value = false
  }
}

function toggleAutoSample(): void {
  autoSample.value = !autoSample.value
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
  if (autoSample.value) {
    void sampleOnce()
    timer = window.setInterval(() => {
      void sampleOnce()
    }, sampleIntervalMs.value)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="aw-page">
    <h1 class="aw-page__title">一致性报告</h1>
    <p class="aw-page__subtitle">
      数据源：GET <span class="aw-mono">/api/v1/admin/consistency/report</span> ·
      POST <span class="aw-mono">/scan</span> 触发一轮对账 ·
      POST <span class="aw-mono">/repair</span> 显式修复。对账只读发现、不自动改数据（发现与修复分离）。
    </p>

    <div class="consistency__toolbar">
      <el-button :loading="loading" @click="load">刷新报告</el-button>
      <el-button type="primary" :loading="scanning" @click="scan">触发扫描</el-button>
      <el-button
        type="danger"
        plain
        :loading="repairing"
        :disabled="!repairable"
        @click="repair"
      >
        一键重试（repair）
      </el-button>
      <span v-if="report" class="aw-muted aw-mono">
        reportId {{ report.reportId }} · createdAt {{ report.createdAt }}
      </span>
    </div>

    <el-alert v-if="recentLoading" type="info" :closable="false" class="aw-section">
      正在读取最近扫描记录……
    </el-alert>
    <el-alert v-else-if="recentError" type="warning" :closable="false" show-icon class="aw-section">
      <template #title>最近扫描记录读取失败</template>
      <div class="aw-mono">{{ recentError }}</div>
    </el-alert>
    <el-alert v-else-if="recentReports.length === 0" type="info" :closable="false" show-icon class="aw-section">
      <template #title>尚无历史扫描记录</template>
      <div>
        对账定时任务默认每 5 分钟一轮；也可以点上方「触发扫描」。此刻「不一致清单为空」既可能是正常，也可能是从未扫过——
        先扫一轮再判断。
      </div>
    </el-alert>
    <el-alert v-else :type="recentMismatchRounds === 0 ? 'success' : 'warning'" :closable="false" show-icon
      class="aw-section">
      <template #title>
        近 {{ recentRange?.count }} 轮扫描（reportId {{ recentRange?.oldest }} → {{ recentRange?.newest }}）
      </template>
      <div v-if="recentMismatchRounds === 0">
        近 {{ recentReports.length }} 轮<strong>均未发现不一致</strong>（累计 0 条）——扫描在持续发生，
        「不一致清单为空」是正常工作状态，不是故障。
      </div>
      <div v-else>
        {{ recentReports.length }} 轮中有 <strong>{{ recentMismatchRounds }}</strong> 轮发现不一致，累计
        {{ recentMismatchTotal }} 条；最近一轮 mismatchCount={{ recentReports[0].mismatchCount }}。
        逐轮明细见下方最近一轮报告与不一致清单。
      </div>
    </el-alert>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="aw-section">
      <template #title>
        接口返回的错误（原文）{{ error.status === 404 ? '· 404：尚无报告' : '' }}
      </template>
      <div class="aw-mono">{{ error.message }}</div>
      <div v-if="error.status === 404" class="aw-muted">
        后端在无报告时抛 NotFoundException，提示「等待首轮扫描或调用 POST /scan」——可直接点上面的「触发扫描」。
      </div>
    </el-alert>

    <el-alert v-if="detailsError" type="warning" :closable="false" show-icon class="aw-section">
      <template #title>detailsJson 解析失败（原文保留）</template>
      <div class="aw-mono">{{ detailsError }}</div>
      <div class="aw-mono details__raw">{{ report?.detailsJson }}</div>
    </el-alert>

    <el-alert v-if="repairResult" type="info" :closable="false" class="aw-section">
      {{ repairResult }}
    </el-alert>

    <el-skeleton v-if="loading && !report" :rows="4" animated class="aw-section" />

    <template v-if="report">
      <div class="consistency__cards aw-section">
        <el-card shadow="never">
          <div class="metric__label">不一致数（mismatchCount）</div>
          <div class="metric__value">{{ report.mismatchCount }}</div>
          <div class="metric__note">= 有残留的已删除文档数 + 摄入停摆的文档数（按 doc_id 去重）</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">孤儿向量（orphanVectors）</div>
          <div class="metric__value">{{ details?.orphanVectors ?? '—' }}</div>
          <div class="metric__note">已删除文档的残留向量数</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">摄入停摆（stuckIngest）</div>
          <div class="metric__value">{{ stuckIngest.length }}</div>
          <div class="metric__note">
            当前版本索引事件永久失败 / 账本 PROCESSING 超时 / 文档 PENDING 超时，三判据去重；
            当前版本失败索引事件 {{ details?.failedOutboxEvents ?? '—' }} 条
          </div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">scannedAt</div>
          <div class="metric__value metric__value--time">{{ details?.scannedAt ?? '—' }}</div>
          <div class="metric__note">本轮扫描时刻（来自 detailsJson）</div>
        </el-card>
      </div>

      <el-card shadow="never" class="aw-section">
        <template #header>
          <div class="card__head">
            <span class="aw-section__title">不一致清单（staleDocuments）</span>
            <el-tag size="small" type="info" effect="plain">{{ staleDocuments.length }} 条</el-tag>
          </div>
        </template>
        <el-empty
          v-if="staleDocuments.length === 0"
          description="不一致清单为空：没有已删除文档的残留切片"
          :image-size="70"
        />
        <el-table v-else :data="staleDocuments" size="small" border>
          <el-table-column prop="doc_id" label="doc_id" width="120" />
          <el-table-column prop="tenant_id" label="tenant_id" width="120" />
          <el-table-column prop="stale_chunks" label="stale_chunks（残留切片数）" />
        </el-table>
        <div class="aw-muted card__note">
          字段名照抄后端结果集别名（doc_id / tenant_id / stale_chunks），不做驼峰改写。
        </div>
      </el-card>

      <el-card shadow="never" class="aw-section">
        <template #header>
          <div class="card__head">
            <span class="aw-section__title">摄入停摆清单（stuckIngest）</span>
            <el-tag size="small" type="info" effect="plain">{{ stuckIngest.length }} 条</el-tag>
          </div>
        </template>
        <el-empty
          v-if="stuckIngest.length === 0"
          description="停摆清单为空：没有当前版本失败索引事件、卡死的当前版本账本或超时未收敛的文档"
          :image-size="70"
        />
        <el-table v-else :data="stuckIngest" size="small" border>
          <el-table-column prop="doc_id" label="doc_id" width="110" />
          <el-table-column prop="tenant_id" label="tenant_id" width="110" />
          <el-table-column prop="reason" label="停摆原因（子判据）" width="220" />
          <el-table-column label="可重驱动">
            <template #default="{ row }">
              {{
                row.reason === 'OUTBOX_FAILED'
                  ? `是（event_id=${row.event_id}）`
                  : row.reason === 'LEDGER_PROCESSING'
                    ? '否（需人工核查/摄入完成）'
                    : '否（需摄入完成或人工重传）'
              }}
            </template>
          </el-table-column>
        </el-table>
        <div class="aw-muted card__note">
          reason 取命中的最高优先级子判据：<span class="aw-mono">OUTBOX_FAILED</span>（当前版本索引事件永久失败，repair 可重驱动）
          &gt; <span class="aw-mono">LEDGER_PROCESSING</span>（消费了但没收敛）
          &gt; <span class="aw-mono">DOCUMENT_OVERDUE</span>（事件没到消费者）。
          同一文档在停摆清单内只算一条；残留判据与停摆判据分别计数。
        </div>
      </el-card>

      <el-card shadow="never" class="aw-section">
        <template #header>
          <div class="card__head">
            <span class="aw-section__title">aiwarden_vector_orphan_total 曲线</span>
            <div class="card__head-actions">
              <el-switch v-model="autoSample" active-text="自动采样" @change="toggleAutoSample" />
              <el-select v-model="sampleIntervalMs" size="small" style="width: 110px" :disabled="!autoSample">
                <el-option :value="3000" label="3 秒" />
                <el-option :value="5000" label="5 秒" />
                <el-option :value="15000" label="15 秒" />
              </el-select>
              <el-button size="small" :loading="sampling" @click="sampleOnce">采样一次</el-button>
            </div>
          </div>
        </template>

        <el-alert type="info" :closable="false" class="card__note">
          <template #title>口径说明（重要）</template>
          <div>
            该指标是 Micrometer <span class="aw-mono">Gauge</span>，<strong>只有当前值</strong>（最近一轮扫描的结果），
            后端没有历史序列接口。因此这里的曲线是<strong>本页对
            <span class="aw-mono">GET /actuator/prometheus</span>
            的轮询采样自绘</strong>（Prometheus 端点已在 application.yml 暴露）——只在页面打开期间累计采样点，刷新页面即清空，
            不代表历史真实值。若要做持久化曲线，需接入 Prometheus 查询（本次未做）。
          </div>
        </el-alert>

        <el-alert v-if="sampleError" type="error" :closable="false" show-icon class="card__note">
          <template #title>采样失败（原文）</template>
          <div class="aw-mono">{{ sampleError }}</div>
        </el-alert>

        <el-empty v-if="samples.length === 0" description="还没有采样点：点「采样一次」或打开自动采样" :image-size="70" />
        <VChart v-else :option="chartOption" autoresize class="consistency__chart" />

        <div v-if="gaugeHelp" class="aw-muted card__note">指标 HELP：{{ gaugeHelp }}</div>
        <div v-if="samples.length > 0" class="aw-muted card__note">
          已累计 {{ samples.length }} 个采样点（最新值 {{ samples[samples.length - 1].value }}，采于
          {{ samples[samples.length - 1].at }}）。点位越密，曲线越接近真实变化——但采样间隔内的跳变仍会漏掉。
        </div>
      </el-card>
    </template>

    <el-card shadow="never" class="aw-section">
      <template #header>
        <div class="card__head">
          <span class="aw-section__title">按 traceId 查完整步骤时间线</span>
          <el-tag type="success" size="small" effect="plain">M4 · 已接入</el-tag>
        </div>
      </template>
      <div class="trace__query">
        <el-input v-model="traceIdInput" class="aw-mono" placeholder="32 位 traceId（C 端响应头 X-Trace-Id）"
          @keyup.enter="lookupTrace" />
        <el-button type="primary" :loading="traceLoading" @click="lookupTrace">查询</el-button>
      </div>

      <el-alert v-if="traceError" type="warning" :closable="false" show-icon class="card__note">
        <div class="aw-mono">{{ traceError }}</div>
      </el-alert>

      <template v-if="traceRun">
        <el-descriptions :column="3" border size="small" class="trace__meta">
          <el-descriptions-item label="runId">{{ traceRun.runId }}</el-descriptions-item>
          <el-descriptions-item label="session">{{ traceRun.sessionId }}</el-descriptions-item>
          <el-descriptions-item label="status">{{ traceRun.status }}</el-descriptions-item>
          <el-descriptions-item label="outcome">{{ traceRun.outcome ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="lastStepNo">{{ traceRun.lastStepNo }}</el-descriptions-item>
          <el-descriptions-item label="version">{{ traceRun.version }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="traceRun.reason" class="aw-muted card__note">原因：{{ traceRun.reason }}</div>

        <el-table :data="traceRun.steps" size="small" border class="trace__table">
          <el-table-column prop="stepNo" label="#" width="60" />
          <el-table-column prop="name" label="步骤" width="120" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="version" label="version" width="90" />
          <el-table-column prop="elapsedMs" label="耗时(ms)" width="100" />
          <el-table-column prop="detail" label="detail" />
        </el-table>
      </template>

      <div class="aw-muted card__note">
        数据源：<span class="aw-mono">GET /api/v1/agent/runs/by-trace/{traceId}</span>（数据库检查点，
        崩溃/续跑后的完整时间线）。span 的父子关系与属性本应在 Grafana → Explore → Tempo 按同一 traceId 查看，
        但 <strong>OTel Collector / Tempo 未加入 compose、本轮未实跑</strong>（span 树由内存 exporter 的
        全链路测试断言，边界见 docs/STATUS.md §3 ①）。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.consistency__toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.consistency__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.metric__label {
  font-size: 12px;
  color: var(--aw-muted);
}

.metric__value {
  font-size: 26px;
  font-weight: 700;
  margin: 6px 0 4px;
}

.metric__value--time {
  font-size: 14px;
  font-weight: 500;
  word-break: break-all;
}

.metric__note {
  font-size: 12px;
  color: var(--aw-muted);
  line-height: 1.6;
}

.card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.card__head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card__note {
  margin-top: 10px;
  line-height: 1.7;
}

.consistency__chart {
  height: 260px;
  margin-top: 10px;
}

.details__raw {
  margin-top: 6px;
  max-height: 120px;
  overflow: auto;
  font-size: 12px;
  word-break: break-all;
}

.placeholder__body {
  font-size: 13px;
  line-height: 1.8;
}

.trace__query {
  display: flex;
  gap: 8px;
}

.trace__meta {
  margin-top: 12px;
}

.trace__table {
  margin-top: 12px;
}

.placeholder__body p {
  margin: 0 0 8px;
}
</style>
