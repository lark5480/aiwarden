/**
 * 全局开发态身份配置。
 *
 * 后端所有接口都要求身份头（缺失即 400，不回落默认值）：
 *   X-Aiwarden-Tenant-Id  必填（数值字符串）
 *   X-Aiwarden-User-Id    必填（数值字符串）
 *   X-Aiwarden-Org-Id     可选（空 = 无组织归属）
 * 依据：aiwarden-common/.../tenant/TenantContext.java:28、
 *       aiwarden-common/.../principal/PrincipalContext.java:21,24、
 *       aiwarden-start/.../tenant/TenantContextFilter.java:35-44
 *
 * 本文件只做「开发态模拟认证」：值存 localStorage，缺省 tenant=1 / user=1 / org=空。
 */
import { reactive } from 'vue'

const STORAGE_KEY = 'aiwarden.identity'

export interface Identity {
  tenantId: string
  userId: string
  orgId: string
}

const DEFAULTS: Identity = { tenantId: '1', userId: '1', orgId: '' }

function readStored(): Identity {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return { ...DEFAULTS }
    }
    const parsed = JSON.parse(raw) as Partial<Identity>
    return {
      tenantId: normalize(parsed.tenantId, DEFAULTS.tenantId),
      userId: normalize(parsed.userId, DEFAULTS.userId),
      orgId: typeof parsed.orgId === 'string' ? parsed.orgId.trim() : DEFAULTS.orgId,
    }
  } catch {
    return { ...DEFAULTS }
  }
}

function normalize(value: unknown, fallback: string): string {
  const text = typeof value === 'string' ? value.trim() : ''
  return text === '' ? fallback : text
}

export const identity = reactive<Identity>(readStored())

export function saveIdentity(next: Identity): void {
  identity.tenantId = next.tenantId.trim()
  identity.userId = next.userId.trim()
  identity.orgId = next.orgId.trim()
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...identity }))
}

export function resetIdentity(): Identity {
  const next = { ...DEFAULTS }
  saveIdentity(next)
  return next
}

/**
 * 组装身份请求头。orgId 为空时**不发送**该头（后端语义：无组织归属），
 * 而不是发送空串——避免把「空组织」与「未提供」混为一谈。
 */
export function identityHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Aiwarden-Tenant-Id': identity.tenantId,
    'X-Aiwarden-User-Id': identity.userId,
  }
  if (identity.orgId !== '') {
    headers['X-Aiwarden-Org-Id'] = identity.orgId
  }
  return headers
}

/** 身份是否可用于发请求（数值字符串）。 */
export function identityValid(): boolean {
  return /^\d+$/.test(identity.tenantId) && /^\d+$/.test(identity.userId)
}
