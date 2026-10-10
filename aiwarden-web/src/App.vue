<script setup lang="ts">
/**
 * 应用外壳：导航（C 端 / B 端切换）+ 身份配置入口 + 路由出口。
 * 路由：/ → /chat；/chat 为 C 端；/admin/* 为 B 端三页（router/index.ts）。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import IdentityDrawer from '@/components/IdentityDrawer.vue'
import { identity, identityValid } from '@/stores/identity'

const route = useRoute()
const activeMenu = computed(() => route.path)
const valid = computed(() => identityValid())
</script>

<template>
  <el-container class="shell">
    <el-header class="shell__header">
      <div class="shell__brand">
        <span class="shell__name">AIWarden</span>
        <span class="shell__tag">治理能力演示终端</span>
      </div>

      <el-menu :default-active="activeMenu" mode="horizontal" router class="shell__menu" :ellipsis="false">
        <el-menu-item index="/chat">C 端 · 问答</el-menu-item>
        <el-menu-item index="/admin/consistency">B 端 · 一致性报告</el-menu-item>
        <el-menu-item index="/admin/usage">B 端 · 用量看板</el-menu-item>
        <el-menu-item index="/admin/eval">B 端 · 评测报告</el-menu-item>
      </el-menu>

      <div class="shell__right">
        <el-tag v-if="!valid" type="danger" size="small" effect="dark">身份头非法，接口会 400</el-tag>
        <el-tag v-else type="info" size="small" effect="plain" class="aw-mono">
          tenant {{ identity.tenantId }} / user {{ identity.userId }} / org
          {{ identity.orgId === '' ? '（未发送）' : identity.orgId }}
        </el-tag>
        <IdentityDrawer />
      </div>
    </el-header>

    <el-main class="shell__main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.shell {
  height: 100%;
}

.shell__header {
  display: flex;
  align-items: center;
  gap: 16px;
  border-bottom: 1px solid var(--aw-border);
  padding: 0 20px;
  background: #fff;
}

.shell__brand {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  min-width: 130px;
}

.shell__name {
  font-size: 16px;
  font-weight: 700;
}

.shell__tag {
  font-size: 11px;
  color: var(--aw-muted);
}

.shell__menu {
  flex: 1;
  border-bottom: none;
}

.shell__right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.shell__main {
  padding: 0;
  background: var(--aw-bg-soft);
  overflow: auto;
}
</style>
