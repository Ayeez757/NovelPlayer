import { request } from './http'
import type {
  CreateProjectRequest,
  GenerationJobResponse,
  GenerationRequest,
  ProjectResponse,
  ScriptDocumentResponse
} from './types'

export function createProject(payload: CreateProjectRequest) {
  return request<ProjectResponse>('/api/projects', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function createGenerationJob(projectId: number, payload: GenerationRequest) {
  return request<GenerationJobResponse>(`/api/projects/${projectId}/generation-jobs`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function getGenerationJob(jobId: number) {
  return request<GenerationJobResponse>(`/api/generation-jobs/${jobId}`)
}

export function getLatestScript(projectId: number) {
  return request<ScriptDocumentResponse>(`/api/projects/${projectId}/scripts/latest`)
}

export function scriptDownloadUrl(projectId: number) {
  return `/api/projects/${projectId}/scripts/latest/download`
}
