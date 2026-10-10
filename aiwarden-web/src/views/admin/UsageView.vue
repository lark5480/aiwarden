<script setup lang="ts">
/**
 * B 端 · 用量看板（FR-ADM-04）。
 * 数据源：GET /api/v1/admin/usage?sessionId&stepNo&tool&from&to&limit（UsageController.java:29）
 *   from / to 为 ISO-8601 OffsetDateTime 字符串；limit 后端 clamp 到 1–500，缺省 100。
 * 聚合在**前端**基于同一份明细完成（UsageController 的注释即此口径：聚合由 B 端看板完成）。
 * 不引入 Grafana（PRD §5.10 裁决 19：用量看板自绘）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { getUsage, type UsageRecord } from '@/api/admin'
import { ApiError } from '@/api/client'

const filters = reactive({
  sessionId: '',
  stepNo: '' as string | number,
  tool: '',
  from: '',
  to: '',
  limit: 100 as string | number,
})

const rows = ref<UsageRecord[]>([])
const loading = ref(false)
const error = ref<{ status: number | null; message: string } | null>(null)
const loaded = ref(false)

/** el-date-picker 的 datetimerange 值（本地时间字符串，转成 ISO-8601 带时区后发给后端）。 */
const range = ref<[Date, Date] | null>(null)

function toOffsetIso(date: Date): string {
  const pad = (value: number, size = 2) => String(value).padStart(size, '0')
  const offsetMinutes = -date.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const abs = Math.abs(offsetMinutes)
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  )
}

function describe(error_: unknown): { status: number | null; message: string } {
  if (error_ instanceof ApiError) {
    return { status: error_.status, message: error_.message }
  }
  return { status: null, message: error_ instanceof Error ? error_.message : String(error_) }
}

const totalPrompt = computed(() => rows.value.reduce((sum, row) => sum + row.promptTokens, 0))
const totalCompletion = computed(() => rows.value.reduce((sum, row) => sum + row.completionTokens, 0))
const totalTokens = computed(() => totalPrompt.value + totalCompletion.value)

interface Group {
  key: string
  count: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

function groupBy(pick: (row: UsageRecord) => string | number | null): Group[] {
  const map = new Map<string, Group>()
  for (const row of rows.value) {
    const raw = pick(row)
    const key = raw === null || raw === undefined || raw === '' ? '（空）' : String(raw)
    let group = map.get(key)
    if (!group) {
      group = { key, count: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 }
      map.set(key, group)
    }
    group.count += 1
    group.promptTokens += row.promptTokens
    group.completionTokens += row.completionTokens
    group.totalTokens += row.promptTokens + row.completionTokens
  }
  return [...map.values()].sort((a, b) => b.totalTokens - a.totalTokens)
}

const bySession = computed(() => groupBy((row) => row.sessionId))
const byStep = computed(() => groupBy((row) => row.stepNo))
const byTool = computed(() => groupBy((row) => row.tool))
const byModel = computed(() => groupBy((row) => row.model))

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  const [from, to] = range.value ?? [null, null]
  try {
    rows.value = await getUsage({
      sessionId: filters.sessionId.trim(),
      stepNo: filters.stepNo === '' ? undefined : Number(filters.stepNo),
      tool: filters.tool.trim(),
      from: from ? toOffsetIso(from) : undefined,
      to: to ? toOffsetIso(to) : undefined,
      limit: Number(filters.limit) || 100,
    })
    loaded.value = true
  } catch (error_) {
    rows.value = []
    error.value = describe(error_)
  } finally {
    loading.value = false
  }
}

function resetFilters(): void {
  filters.sessionId = ''
  filters.stepNo = ''
  filters.tool = ''
  filters.limit = 100
  range.value = null
  void load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="aw-page">
    <h1 class="aw-page__title">用量看板</h1>
    <p class="aw-page__subtitle">
      数据源：GET <span class="aw-mono">/api/v1/admin/usage</span>（四维过滤：会话 / 步骤 / 工具 / 时间范围）。
      后端按租户行级隔离，返回明细数组；<strong>下面的聚合全部在前端基于同一份明细算出</strong>，
      不是后端聚合接口。不引入 Grafana（PRD §5.10 裁决 19：用量看板自绘）。
    </p>

    <el-card shadow="never">
      <el-form :inline="true" class="usage__filters">
        <el-form-item label="会话 sessionId">
          <el-input v-model="filters.sessionId" placeholder="精确匹配" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="步骤 stepNo">
          <el-input v-model="filters.stepNo" placeholder="整数" clearable style="width: 110px" />
        </el-form-item>
        <el-form-item label="工具 tool">
          <el-input v-model="filters.tool" placeholder="如 create_ticket" clearable style="width: 170px" />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="range"
            type="datetimerange"
            start-placeholder="from"
            end-placeholder="to"
            :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
          />
        </el-form-item>
        <el-form-item label="limit">
          <el-input-number v-model="filters.limit" :min="1" :max="500" :step="10" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="load">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="aw-muted usage__hint">
        from / to 为 ISO-8601 OffsetDateTime 字符串（本页按浏览器本地时区生成，如
        <span class="aw-mono">2026-10-07T00:00:00+08:00</span>）；limit 后端会 clamp 到 1–500。
      </div>
    </el-card>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="aw-section">
      <template #title>接口返回的错误（原文）{{ error.status ? ` · HTTP ${error.status}` : '' }}</template>
      <div class="aw-mono">{{ error.message }}</div>
    </el-alert>

    <el-skeleton v-if="loading && rows.length === 0" :rows="5" animated class="aw-section" />

    <template v-else>
      <div class="usage__cards aw-section">
        <el-card shadow="never">
          <div class="metric__label">明细条数</div>
          <div class="metric__value">{{ rows.length }}</div>
          <div class="metric__note">t_llm_call_log 命中行数（受 limit 限制）</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">总 prompt tokens</div>
          <div class="metric__value">{{ totalPrompt }}</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">总 completion tokens</div>
          <div class="metric__value">{{ totalCompletion }}</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">总 tokens</div>
          <div class="metric__value">{{ totalTokens }}</div>
        </el-card>
      </div>

      <el-card shadow="never" class="aw-section">
        <template #header>
          <div class="card__head">
            <span class="aw-section__title">用量明细</span>
            <el-tag size="small" type="info" effect="plain">{{ rows.length }} 条</el-tag>
          </div>
        </template>
        <el-empty
          v-if="loaded && rows.length === 0"
          description="没有命中任何用量明细：换过滤条件，或该租户尚无计量记录"
        />
        <el-table v-else :data="rows" size="small" border height="380">
          <el-table-column prop="id" label="id" width="80" />
          <el-table-column prop="sessionId" label="sessionId" min-width="220" show-overflow-tooltip />
          <el-table-column prop="stepNo" label="stepNo" width="90" />
          <el-table-column prop="tool" label="tool" width="140" show-overflow-tooltip />
          <el-table-column prop="model" label="model" width="120" show-overflow-tooltip />
          <el-table-column prop="promptTokens" label="promptTokens" width="130" />
          <el-table-column prop="completionTokens" label="completionTokens" width="160" />
          <el-table-column prop="latencyMs" label="latencyMs" width="110" />
          <el-table-column prop="createdAt" label="createdAt" min-width="200" />
        </el-table>
        <div class="aw-muted card__note">
          列名即后端字段名（UsageRecordResponse：id / sessionId / stepNo / tool / model / promptTokens /
          completionTokens / latencyMs / createdAt），不做改名。
        </div>
      </el-card>

      <el-card shadow="never" class="aw-section">
        <template #header>
          <span class="aw-section__title">聚合（前端基于上方同一份明细计算）</span>
        </template>

        <el-tabs>
          <el-tab-pane label="按会话">
            <el-empty v-if="bySession.length === 0" description="暂无数据" :image-size="60" />
            <el-table v-else :data="bySession" size="small" border>
              <el-table-column prop="key" label="sessionId" min-width="240" show-overflow-tooltip />
              <el-table-column prop="count" label="条数" width="100" />
              <el-table-column prop="promptTokens" label="prompt tokens" width="150" />
              <el-table-column prop="completionTokens" label="completion tokens" width="170" />
              <el-table-column prop="totalTokens" label="合计 tokens" width="140" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="按步骤">
            <el-empty v-if="byStep.length === 0" description="暂无数据" :image-size="60" />
            <el-table v-else :data="byStep" size="small" border>
              <el-table-column prop="key" label="stepNo" width="120" />
              <el-table-column prop="count" label="条数" width="100" />
              <el-table-column prop="promptTokens" label="prompt tokens" width="150" />
              <el-table-column prop="completionTokens" label="completion tokens" width="170" />
              <el-table-column prop="totalTokens" label="合计 tokens" width="140" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="按工具">
            <el-empty v-if="byTool.length === 0" description="暂无数据" :image-size="60" />
            <el-table v-else :data="byTool" size="small" border>
              <el-table-column prop="key" label="tool" width="180" show-overflow-tooltip />
              <el-table-column prop="count" label="条数" width="100" />
              <el-table-column prop="promptTokens" label="prompt tokens" width="150" />
              <el-table-column prop="completionTokens" label="completion tokens" width="170" />
              <el-table-column prop="totalTokens" label="合计 tokens" width="140" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="按模型">
            <el-empty v-if="byModel.length === 0" description="暂无数据" :image-size="60" />
            <el-table v-else :data="byModel" size="small" border>
              <el-table-column prop="key" label="model" width="180" show-overflow-tooltip />
              <el-table-column prop="count" label="条数" width="100" />
              <el-table-column prop="promptTokens" label="prompt tokens" width="150" />
              <el-table-column prop="completionTokens" label="completion tokens" width="170" />
              <el-table-column prop="totalTokens" label="合计 tokens" width="140" />
            </el-table>
          </el-tab-pane>
        </el-tabs>

        <div class="aw-muted card__note">
          聚合口径：对当前页返回的明细做分组求和（条数 / prompt / completion / 合计 tokens），
          按合计 tokens 降序。因此 <strong>聚合结果只覆盖本次 limit 命中的明细</strong>，不是全量统计——
          要看全量需要把 limit 提到 500 或收窄时间范围。
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.usage__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
}

.usage__hint {
  font-size: 12px;
  line-height: 1.7;
}

.usage__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.metric__label {
  font-size: 12px;
  color: var(--aw-muted);
}

.metric__value {
  font-size: 24px;
  font-weight: 700;
  margin-top: 6px;
}

.metric__note {
  font-size: 12px;
  color: var(--aw-muted);
  margin-top: 4px;
}

.card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card__note {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
}
</style>
