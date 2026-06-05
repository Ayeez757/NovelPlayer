import { request } from './http'
import type { CreateProjectRequest, GenerationRequest, ProjectResponse, ScriptDocumentResponse } from './types'

// 项目创建会同步完成后端章节识别。
export function createProject(payload: CreateProjectRequest) {
  return request<ProjectResponse>('/api/projects', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

// 当前最小可用版本采用同步生成；后续如果改异步，函数签名可以保持相近。
export function generateScript(projectId: number, payload: GenerationRequest) {
  return request<ScriptDocumentResponse>(`/api/projects/${projectId}/generate`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

// 读取项目最新一次生成的剧本。
export function getLatestScript(projectId: number) {
  return request<ScriptDocumentResponse>(`/api/projects/${projectId}/scripts/latest`)
}

// 下载由浏览器直接处理，不经过统一请求解析。
export function scriptDownloadUrl(projectId: number) {
  return `/api/projects/${projectId}/scripts/latest/download`
}
