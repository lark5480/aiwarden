/**
 * 知识库与文档管理接口（M4 / FR-ADM-02）。
 *
 * 后端契约（字段名逐字一致）：
 * - GET/POST /api/v1/knowledge-bases            KnowledgeBaseController
 * - GET/POST /api/v1/knowledge-bases/{kbId}/documents   DocumentController（列表为 M4 新增）
 * - DELETE /api/v1/documents/{docId}
 */
import { getJson, postJson, request } from './client'

export interface KnowledgeBase {
  id: number
  name: string
}

export interface DocumentSummary {
  id: number
  name: string
  version: number
  status: string
  updatedAt: string
}

export interface DocumentUpload {
  docId: number
  version: number
  status: string
}

export interface DocumentDelete {
  deleteTaskId: string
  docId: number
}

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return getJson<KnowledgeBase[]>('/api/v1/knowledge-bases')
}

export function createKnowledgeBase(name: string): Promise<KnowledgeBase> {
  return postJson<KnowledgeBase>('/api/v1/knowledge-bases', { name })
}

export function listDocuments(kbId: number): Promise<DocumentSummary[]> {
  return getJson<DocumentSummary[]>(`/api/v1/knowledge-bases/${kbId}/documents`)
}

export function uploadDocument(kbId: number, name: string, content: string): Promise<DocumentUpload> {
  return postJson<DocumentUpload>(`/api/v1/knowledge-bases/${kbId}/documents`, { name, content })
}

export function deleteDocument(docId: number): Promise<DocumentDelete> {
  return request<DocumentDelete>(`/api/v1/documents/${docId}`, { method: 'DELETE' })
}
