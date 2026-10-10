<script setup lang="ts">
/** 身份配置入口（开发态模拟认证）：值存 localStorage，改完刷新页面生效。 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { identity, resetIdentity, saveIdentity, type Identity } from '@/stores/identity'

const visible = ref(false)
const draft = ref<Identity>({ ...identity })

const tenantValid = computed(() => /^\d+$/.test(draft.value.tenantId.trim()))
const userValid = computed(() => /^\d+$/.test(draft.value.userId.trim()))
const orgValid = computed(() => draft.value.orgId.trim() === '' || /^\d+$/.test(draft.value.orgId.trim()))

function open(): void {
  draft.value = { ...identity }
  visible.value = true
}

function submit(): void {
  if (!tenantValid.value || !userValid.value || !orgValid.value) {
    ElMessage.error('租户 / 用户必须是数值字符串；组织可留空，留空即不发送该请求头')
    return
  }
  saveIdentity({ ...draft.value })
  visible.value = false
  ElMessage.success('身份已保存，正在刷新页面')
  window.location.reload()
}

function reset(): void {
  draft.value = { ...resetIdentity() }
  ElMessage.info('已恢复缺省身份（tenant=1 / user=1 / org 空），刷新后生效')
}
</script>

<template>
  <div class="identity">
    <el-button size="small" @click="open">
      身份
      <span class="identity__hint aw-mono">
        T{{ identity.tenantId }} / U{{ identity.userId }} / O{{ identity.orgId === '' ? '空' : identity.orgId }}
      </span>
    </el-button>

    <el-drawer v-model="visible" title="身份请求头（开发态配置）" size="420px">
      <el-alert type="info" :closable="false" show-icon class="identity__alert">
        <template #title>后端所有接口都要求身份头，缺失即 400，不回落默认值</template>
        <div class="identity__alert-body">
          这三个值只存在浏览器 localStorage，由前端作为请求头注入，仅供本地开发联调使用。
          保存后页面会刷新一次，让所有页面的请求都带上新身份。
        </div>
      </el-alert>

      <el-form label-width="150px" class="identity__form">
        <el-form-item label="X-Aiwarden-Tenant-Id" required>
          <el-input v-model="draft.tenantId" placeholder="数值字符串，必填，缺省 1" />
          <div v-if="!tenantValid" class="identity__error">必须是数值字符串（缺失即 400）</div>
        </el-form-item>
        <el-form-item label="X-Aiwarden-User-Id" required>
          <el-input v-model="draft.userId" placeholder="数值字符串，必填，缺省 1" />
          <div v-if="!userValid" class="identity__error">必须是数值字符串（缺失即 400）</div>
        </el-form-item>
        <el-form-item label="X-Aiwarden-Org-Id">
          <el-input v-model="draft.orgId" placeholder="可留空；留空即不发送该头" />
          <div v-if="!orgValid" class="identity__error">留空或数值字符串</div>
          <div class="aw-muted identity__tip">留空 = 无组织归属，仅可见租户公共内容</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="identity__footer">
          <el-button @click="reset">恢复缺省</el-button>
          <div>
            <el-button @click="visible = false">取消</el-button>
            <el-button type="primary" @click="submit">保存并刷新</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.identity__hint {
  margin-left: 6px;
  font-size: 11px;
  color: var(--aw-muted);
}

.identity__alert-body {
  margin-top: 4px;
  line-height: 1.6;
}

.identity__form {
  margin-top: 16px;
}

.identity__error {
  color: #f56c6c;
  font-size: 12px;
}

.identity__tip {
  font-size: 12px;
}

.identity__footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
