<script setup lang="ts">
/**
 * B 端 · 知识库与文档管理（M4 / FR-ADM-02）。
 *
 * 为什么要有这一页：M3 的 B 端只有「读」——建库 / 传文档只能手工插库或 curl，
 * 演示时必须在终端与浏览器之间来回切。本页把「建库 → 传文档 → 看摄入状态」收进界面。
 *
 * 口径（照抄后端，不在前端编造）：
 * - 上传返回 { docId, version, status }，**不含 kbId**——当前库由本页记住（localStorage）。
 * - 摄入是异步的：状态从 PENDING 起，由 Kafka 消费者推进到 INDEXED / FAILED；
 *   本页轮询列表刷新，不做「假装实时」的 SSE。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createKnowledgeBase,
  deleteDocument,
  listDocuments,
  listKnowledgeBases,
  uploadDocument,
  type DocumentSummary,
  type KnowledgeBase,
} from '@/api/knowledge'
import { ApiError } from '@/api/client'

const CURRENT_KB_KEY = 'aiwarden.kbId'

const kbs = ref<KnowledgeBase[]>([])
const currentKbId = ref<number | null>(readStoredKbId())
const docs = ref<DocumentSummary[]>([])

const loadingKbs = ref(false)
const loadingDocs = ref(false)
const creating = ref(false)
const uploading = ref(false)
const error = ref<{ status: number | null; message: string } | null>(null)

const kbName = ref('')
const docName = ref('')
const docContent = ref('')
const autoRefresh = ref(true)
let timer: number | null = null

const currentKb = computed(() => kbs.value.find((kb) => kb.id === currentKbId.value) ?? null)
const pendingCount = computed(() => docs.value.filter((doc) => doc.status === 'PENDING').length)

function readStoredKbId(): number | null {
  const raw = window.localStorage.getItem(CURRENT_KB_KEY)
  if (raw === null || raw.trim() === '') {
    return null
  }
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

function describe(error_: unknown): { status: number | null; message: string } {
  if (error_ instanceof ApiError) {
    return { status: error_.status, message: error_.message }
  }
  return { status: null, message: error_ instanceof Error ? error_.message : String(error_) }
}

function statusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'INDEXED':
      return 'success'
    case 'PENDING':
      return 'warning'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

async function loadKbs(): Promise<void> {
  loadingKbs.value = true
  try {
    kbs.value = await listKnowledgeBases()
    if (kbs.value.length === 0) {
      currentKbId.value = null
    } else if (currentKbId.value === null || !kbs.value.some((kb) => kb.id === currentKbId.value)) {
      currentKbId.value = kbs.value[0].id
      return
    } else {
      await loadDocs()
    }
    error.value = null
  } catch (error_) {
    error.value = describe(error_)
  } finally {
    loadingKbs.value = false
  }
}

async function loadDocs(silent = false): Promise<void> {
  if (currentKbId.value === null) {
    docs.value = []
    return
  }
  if (!silent) {
    loadingDocs.value = true
  }
  try {
    docs.value = await listDocuments(currentKbId.value)
    error.value = null
  } catch (error_) {
    error.value = describe(error_)
  } finally {
    if (!silent) {
      loadingDocs.value = false
    }
  }
}

async function createKb(): Promise<void> {
  if (kbName.value.trim() === '') {
    ElMessage.warning('知识库名称不能为空')
    return
  }
  creating.value = true
  try {
    const created = await createKnowledgeBase(kbName.value.trim())
    kbName.value = ''
    currentKbId.value = created.id
    window.localStorage.setItem(CURRENT_KB_KEY, String(created.id))
    await loadKbs()
    ElMessage.success(`已创建知识库 ${created.name}（id=${created.id}）`)
  } catch (error_) {
    const described = describe(error_)
    error.value = described
    ElMessage.error(`创建失败：${described.message}`)
  } finally {
    creating.value = false
  }
}

async function upload(): Promise<void> {
  if (currentKbId.value === null) {
    ElMessage.warning('请先选择或创建知识库')
    return
  }
  if (docName.value.trim() === '') {
    ElMessage.warning('文档名称不能为空')
    return
  }
  if (docContent.value.trim() === '') {
    ElMessage.warning('文档内容不能为空（演示替身直接存文本，不做文件上传）')
    return
  }
  uploading.value = true
  try {
    const response = await uploadDocument(currentKbId.value, docName.value.trim(), docContent.value)
    ElMessage.success(
      `已提交：docId=${response.docId} version=${response.version}；摄入异步进行（状态先为 ${response.status}）`,
    )
    docContent.value = ''
    await loadDocs()
  } catch (error_) {
    const described = describe(error_)
    error.value = described
    ElMessage.error(`上传失败：${described.message}`)
  } finally {
    uploading.value = false
  }
}

async function remove(doc: DocumentSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除文档「${doc.name}」（docId=${doc.id}）？删除是异步的：先置 DELETED，再由消费者清理向量与切片。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const response = await deleteDocument(doc.id)
    ElMessage.success(`已提交删除：${response.deleteTaskId}`)
    await loadDocs()
  } catch (error_) {
    const described = describe(error_)
    error.value = described
    ElMessage.error(`删除失败：${described.message}`)
  }
}

function toggleAutoRefresh(): void {
  stopTimer()
  if (autoRefresh.value) {
    timer = window.setInterval(() => {
      void loadDocs(true)
    }, 3000)
  }
}

function stopTimer(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

watch(currentKbId, (value) => {
  if (value === null) {
    window.localStorage.removeItem(CURRENT_KB_KEY)
  } else {
    window.localStorage.setItem(CURRENT_KB_KEY, String(value))
  }
  void loadDocs()
})

onMounted(() => {
  void loadKbs()
  toggleAutoRefresh()
})

onUnmounted(stopTimer)
</script>

<template>
  <div class="aw-page">
    <h1 class="aw-page__title">知识库与文档</h1>
    <p class="aw-page__subtitle">
      数据源：GET/POST <span class="aw-mono">/api/v1/knowledge-bases</span> ·
      GET/POST <span class="aw-mono">/api/v1/knowledge-bases/{kbId}/documents</span> ·
      DELETE <span class="aw-mono">/api/v1/documents/{docId}</span>。
      上传只接受文本（演示替身，PRD §10 明确排除文件存储 / OCR）。
    </p>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="aw-section">
      <template #title>接口返回的错误（原文）{{ error.status === 404 ? '· 404：资源不存在' : '' }}</template>
      <div class="aw-mono">{{ error.message }}</div>
    </el-alert>

    <div class="kb__grid aw-section">
      <el-card shadow="never">
        <template #header>
          <div class="kb__head">
            <span class="aw-section__title">知识库</span>
            <div class="kb__actions">
              <el-switch v-model="autoRefresh" active-text="自动刷新" @change="toggleAutoRefresh" />
              <el-button size="small" :loading="loadingKbs" @click="loadKbs">刷新</el-button>
            </div>
          </div>
        </template>

        <el-empty v-if="kbs.length === 0 && !loadingKbs" description="还没有知识库：先在下方创建一个" :image-size="70" />
        <el-select v-else v-model="currentKbId" placeholder="选择知识库" style="width: 100%">
          <el-option v-for="kb in kbs" :key="kb.id" :label="`${kb.name}（id=${kb.id}）`" :value="kb.id" />
        </el-select>

        <div class="kb__create">
          <el-input v-model="kbName" placeholder="新知识库名称（租户内唯一）" maxlength="128" show-word-limit
            @keyup.enter="createKb" />
          <el-button type="primary" :loading="creating" @click="createKb">创建</el-button>
        </div>
        <div class="aw-muted kb__note">
          当前库会被页面记住（localStorage），刷新后仍指向同一个：上传接口的返回体不含 kbId。
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="kb__head">
            <span class="aw-section__title">上传文档</span>
            <el-tag v-if="currentKb" size="small" type="info" effect="plain">{{ currentKb.name }}</el-tag>
          </div>
        </template>
        <el-form label-position="top">
          <el-form-item label="文档名称">
            <el-input v-model="docName" placeholder="同名重传 = version + 1" maxlength="256" show-word-limit />
          </el-form-item>
          <el-form-item label="文档内容（纯文本）">
            <el-input v-model="docContent" type="textarea" :rows="6"
              placeholder="例如：退款政策的说明：退款将在七个工作日内原路退回……" />
          </el-form-item>
          <el-button type="primary" :loading="uploading" :disabled="currentKbId === null" @click="upload">
            提交上传
          </el-button>
          <span class="aw-muted kb__inline-note">摄入由 Kafka 消费者异步完成，提交后状态先为 PENDING。</span>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="aw-section">
      <template #header>
        <div class="kb__head">
          <span class="aw-section__title">文档与摄入状态</span>
          <div class="kb__actions">
            <el-tag v-if="pendingCount > 0" type="warning" size="small" effect="plain">
              {{ pendingCount }} 个待摄入
            </el-tag>
            <span class="aw-muted aw-mono">共 {{ docs.length }} 个</span>
          </div>
        </div>
      </template>

      <el-skeleton v-if="loadingDocs && docs.length === 0" :rows="3" animated />
      <el-empty v-else-if="docs.length === 0"
        description="当前库还没有文档（或文档已删除）——用上方“上传文档”添加" :image-size="70" />
      <el-table v-else :data="docs" size="small" border>
        <el-table-column prop="id" label="docId" width="90" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="version" label="version" width="90" />
        <el-table-column label="status" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="dark">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="updatedAt（UTC）" min-width="190" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="aw-muted kb__note">
        PENDING 超过预期仍不变化时，先确认本机 Kafka 消费者在跑（宿主机端口需 29092 + profile local）；
        失败原因由 <span class="aw-mono">GET /api/v1/documents/{docId}/status</span> 的 ledger 明细给出。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.kb__grid {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(320px, 1.4fr);
  gap: 12px;
}

@media (max-width: 900px) {
  .kb__grid {
    grid-template-columns: 1fr;
  }
}

.kb__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.kb__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.kb__create {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.kb__note {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
}

.kb__inline-note {
  margin-left: 10px;
  font-size: 12px;
}
</style>
