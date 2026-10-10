<script setup lang="ts">
/**
 * B 端 · 评测报告页（FR-ADM-05）。
 * 数据源：GET /api/v1/admin/eval/report（EvalReportController.java:36）→ EvalReportResponse
 *   { runAt, total, passed, denyTotal, denyBlocked, duplicateTickets, p95LatencyMs, avgCost, detail }
 * detail 元素形状 = EvalCaseResult.java:13-15
 *   { id, category, passed, expectOutcome, actualOutcome, failures, latencyMs, cost, excessTickets }
 * 无报告时后端抛 404，本页显示后端给的原文提示（不造数据）。
 */
import { computed, onMounted, ref } from 'vue'
import { getEvalReport, type EvalCaseDetail, type EvalReport } from '@/api/admin'
import { ApiError } from '@/api/client'

/** el-table 的行样式回调参数（Element Plus 未导出该类型，这里显式声明避免隐式 any）。 */
interface EvalTableRowClassArgs {
  row: EvalCaseDetail
}

const report = ref<EvalReport | null>(null)
const loading = ref(false)
const error = ref<{ status: number | null; message: string } | null>(null)
const onlyFailed = ref(false)

const detail = computed<EvalCaseDetail[]>(() => report.value?.detail ?? [])
const shownDetail = computed(() => (onlyFailed.value ? detail.value.filter((row) => !row.passed) : detail.value))

const passRate = computed(() => {
  const current = report.value
  if (!current || current.total === 0) {
    return null
  }
  return (current.passed / current.total) * 100
})

/** 拦截率 = denyBlocked / denyTotal（门禁口径，EvalReportResponse.java:14-15） */
const denyRate = computed(() => {
  const current = report.value
  if (!current || current.denyTotal === 0) {
    return null
  }
  return (current.denyBlocked / current.denyTotal) * 100
})

const failedCount = computed(() => detail.value.filter((row) => !row.passed).length)

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
    report.value = await getEvalReport()
  } catch (error_) {
    report.value = null
    error.value = describe(error_)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

/** 失败样本整行标红（Element Plus 的 row-class-name 回调用法）。 */
function rowClassName({ row }: EvalTableRowClassArgs): string {
  return row.passed ? '' : 'eval__row--failed'
}
</script>

<template>
  <div class="aw-page">
    <h1 class="aw-page__title">评测报告</h1>
    <p class="aw-page__subtitle">
      数据源：GET <span class="aw-mono">/api/v1/admin/eval/report</span>——最近一次评测门禁运行（随
      <span class="aw-mono">mvn verify</span> 执行并写入 t_eval_report）的结论表；本页只读。
      接口 404 时说明「评测门禁尚未运行」，本页显示后端原文。
    </p>

    <div class="eval__toolbar">
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-switch v-model="onlyFailed" active-text="只看失败样本" />
      <span v-if="report" class="aw-muted">
        runAt：<span class="aw-mono">{{ report.runAt }}</span>
      </span>
    </div>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="aw-section">
      <template #title>
        接口返回的错误（原文）{{ error.status ? ` · HTTP ${error.status}` : '' }}
        {{ error.status === 404 ? '· 404：尚无评测报告' : '' }}
      </template>
      <div class="aw-mono">{{ error.message }}</div>
      <div v-if="error.status === 404" class="aw-muted">
        该提示来自后端 NotFoundException。<strong>本地开发库里这是预期状态</strong>：评测结论由评测门禁测试写进
        <span class="aw-mono">Testcontainers</span> 的临时数据库，测试结束后容器销毁，<span class="aw-mono">t_eval_report</span>
        在本地库始终为空。要看真实数据：跑 <span class="aw-mono">mvn verify</span> 并读测试输出里的
        <span class="aw-mono">EVAL-SUMMARY</span> 行，或把结论自行 INSERT 进本地库（表结构见
        <span class="aw-mono">V9__eval_report.sql</span>）。有了报告后本页会显示结论表与逐样本明细。
      </div>
    </el-alert>

    <el-skeleton v-if="loading && !report" :rows="5" animated class="aw-section" />

    <template v-if="report">
      <div class="eval__cards aw-section">
        <el-card shadow="never">
          <div class="metric__label">通过率（passed / total）</div>
          <div class="metric__value">{{ report.passed }} / {{ report.total }}</div>
          <div class="metric__note">
            {{ passRate === null ? '总样本数为 0，比率不可计算' : `${passRate.toFixed(1)}%` }}
          </div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">拦截率（denyBlocked / denyTotal）</div>
          <div class="metric__value">{{ report.denyBlocked }} / {{ report.denyTotal }}</div>
          <div class="metric__note">
            {{ denyRate === null ? '（本报告无 expect=deny 样本，比率不可计算）' : `${denyRate.toFixed(1)}%（门禁口径）` }}
          </div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">重复建单数（duplicateTickets）</div>
          <div class="metric__value">{{ report.duplicateTickets }}</div>
          <div class="metric__note">期望 0</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">P95 延迟（p95LatencyMs）</div>
          <div class="metric__value">{{ report.p95LatencyMs }} ms</div>
          <div class="metric__note">逐样本耗时 P95（nearest-rank）</div>
        </el-card>
        <el-card shadow="never">
          <div class="metric__label">平均成本（avgCost）</div>
          <div class="metric__value">¥{{ Number(report.avgCost).toFixed(6) }}</div>
          <div class="metric__note">演示单价口径，非真实价目表</div>
        </el-card>
      </div>

      <el-card shadow="never" class="aw-section">
        <template #header>
          <div class="card__head">
            <span class="aw-section__title">逐样本明细（detail）</span>
            <div class="card__head-tags">
              <el-tag size="small" type="info" effect="plain">共 {{ detail.length }} 条</el-tag>
              <el-tag v-if="failedCount > 0" size="small" type="danger" effect="plain">失败 {{ failedCount }} 条</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">全部通过</el-tag>
            </div>
          </div>
        </template>

        <el-empty
          v-if="shownDetail.length === 0"
          :description="onlyFailed ? '没有失败样本' : '报告没有逐样本明细'"
        />
        <el-table v-else :data="shownDetail" size="small" border height="460" :row-class-name="rowClassName">
          <el-table-column prop="id" label="id" width="150" show-overflow-tooltip />
          <el-table-column prop="category" label="category" width="150" show-overflow-tooltip />
          <el-table-column label="passed" width="90">
            <template #default="{ row }">
              <el-tag :type="row.passed ? 'success' : 'danger'" size="small" effect="plain">
                {{ row.passed ? '通过' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="expectOutcome" label="expectOutcome" width="140" />
          <el-table-column prop="actualOutcome" label="actualOutcome" width="140" />
          <el-table-column prop="latencyMs" label="latencyMs" width="110" />
          <el-table-column label="cost" width="120">
            <template #default="{ row }">¥{{ Number(row.cost).toFixed(6) }}</template>
          </el-table-column>
          <el-table-column prop="excessTickets" label="excessTickets" width="130" />
          <el-table-column label="failures" min-width="380">
            <template #default="{ row }">
              <span v-if="!row.failures || row.failures.length === 0" class="aw-muted">—</span>
              <ul v-else class="eval__failures">
                <li v-for="(failure, index) in row.failures" :key="index" class="aw-mono">{{ failure }}</li>
              </ul>
            </template>
          </el-table-column>
        </el-table>

        <div class="aw-muted card__note">
          列名即后端字段名（EvalCaseResult：id / category / passed / expectOutcome / actualOutcome / failures /
          latencyMs / cost / excessTickets）。失败样本的 failures 逐条列出（后端 List&lt;String&gt;，未做截断或改写）。
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.eval__toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.eval__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
}

.metric__label {
  font-size: 12px;
  color: var(--aw-muted);
}

.metric__value {
  font-size: 22px;
  font-weight: 700;
  margin: 6px 0 4px;
}

.metric__note {
  font-size: 12px;
  color: var(--aw-muted);
}

.card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.card__head-tags {
  display: flex;
  gap: 6px;
}

.card__note {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
}

.eval__failures {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  line-height: 1.6;
}

:deep(.eval__row--failed) {
  --el-table-tr-bg-color: #fef0f0;
}
</style>
