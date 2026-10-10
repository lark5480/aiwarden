<script setup lang="ts">
/**
 * B 端 · 一致性报告页（FR-ADM-03）。
 * 数据源：GET /api/v1/admin/consistency/report（ConsistencyController.java:28）
 *        POST /api/v1/admin/consistency/scan（:37）、POST /api/v1/admin/consistency/repair（:43）
 * detailsJson 是字符串，解析后为 { staleDocuments, orphanVectors, stuckProcessing, scannedAt }
 * （ConsistencyReconciler.runOnce() :107-111）。
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
  parseConsistencyDetails,
  parseOrphanGauge,
  repairConsistency,
  scanConsistency,
  type ConsistencyDetails,
  type ConsistencyReport,
  type StaleDocument,
} from '@/api/governance'
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
    repairResult.value = `本次清理残留文档 ${response.repairedDocuments} 个（对残留文档补做产物清理：先向量后切片，账本置 DELETED，幂等可重跑）`
    ElMessage.success('修复完成')
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
      <el-button type="danger" plain :loading="repairing" :disabled="staleDocuments.length === 0" @click="repair">
        一键重试（repair）
      </el-button>
      <span v-if="report" class="aw-muted aw-mono">
        reportId {{ report.reportId }} · createdAt {{ report.createdAt }}
      </span>
    </div>

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
          <div class="metric__note">= 有残留的已删除文档数 + 超时未收敛的账本数</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">孤儿向量（orphanVectors）</div>
          <div class="metric__value">{{ details?.orphanVectors ?? '—' }}</div>
          <div class="metric__note">已删除文档的残留向量数</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">超时未收敛（stuckProcessing）</div>
          <div class="metric__value">{{ details?.stuckProcessing ?? '—' }}</div>
          <div class="metric__note">PROCESSING 卡死超过 5 分钟的账本数</div>
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

    <el-card shadow="never" class="aw-section placeholder">
      <template #header>
        <div class="card__head">
          <span class="aw-section__title">按 traceId 查完整步骤时间线</span>
          <el-tag type="warning" size="small" effect="plain">待 M4（OTel span）</el-tag>
        </div>
      </template>
      <div class="placeholder__body">
        <p>
          PRD §5.10 的 FR-ADM-03 要求把原 FR-ADM-06 的 Trace 查询并入本页（按 traceId 查一次调用的完整步骤时间线与各步耗时 / Token）。
          <strong>当前仓库没有对应端点</strong>：全仓库只有 11 个 Controller，其中没有任何 trace / span 查询接口；
          <span class="aw-mono">/actuator</span> 只暴露了 health 与 prometheus 两个端点。
        </p>
        <p>
          因此本区块是<strong>占位</strong>，不发请求、不展示任何造出来的数据。现在能看到的「步骤时间线 / 各步耗时 / Token」
          只有两条真实路径：① C 端问答页的 <span class="aw-mono">step</span> 事件（本次调用的实时时间线）；
          ② 用量看板按 <span class="aw-mono">stepNo</span> 过滤的计量明细。
        </p>
        <p class="aw-muted">
          待 M4 可观测性成套（OTel Collector / Tempo 已随 docker compose 就位）并暴露按 traceId 的查询端点后，再在此接入。
        </p>
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

.placeholder__body p {
  margin: 0 0 8px;
}
</style>
