import { request } from './http'
import type {
  CreateProjectRequest,
  GenerationRequest,
  GenerationStreamEvent,
  ProjectResponse,
  ScriptDocumentResponse
} from './types'

export function createProject(payload: CreateProjectRequest) {
  return request<ProjectResponse>('/api/projects', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function generateScript(projectId: number, payload: GenerationRequest) {
  return request<ScriptDocumentResponse>(`/api/projects/${projectId}/generate`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export async function generateScriptStream(
  projectId: number,
  payload: GenerationRequest,
  onEvent: (event: GenerationStreamEvent) => void
): Promise<ScriptDocumentResponse> {
  const response = await fetch(`/api/projects/${projectId}/generate-stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify(payload)
  })

  if (!response.ok) {
    const errorPayload = await response.json().catch(() => null) as { messages?: string[] } | null
    throw new Error(errorPayload?.messages?.join('\n') || response.statusText)
  }

  if (!response.body) {
    throw new Error('浏览器不支持流式响应')
  }

  const decoder = new TextDecoder()
  const reader = response.body.getReader()
  let buffer = ''
  let completedScript: ScriptDocumentResponse | null = null

  const flushEvent = (rawEvent: string) => {
    const lines = rawEvent
      .split('\n')
      .map(line => line.trimEnd())
      .filter(Boolean)

    if (lines.length === 0) return

    let eventName = 'message'
    const dataParts: string[] = []

    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataParts.push(line.slice(5).trim())
      }
    }

    if (dataParts.length === 0) return

    const payloadText = dataParts.join('\n')
    const payload = JSON.parse(payloadText) as GenerationStreamEvent
    const normalized = {
      ...payload,
      type: (payload.type || eventName) as GenerationStreamEvent['type']
    }
    onEvent(normalized)
    if (normalized.type === 'completed' && normalized.script) {
      completedScript = normalized.script
    }
    if (normalized.type === 'error') {
      throw new Error(normalized.error || '剧本生成失败')
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let delimiterIndex = buffer.indexOf('\n\n')
    while (delimiterIndex >= 0) {
      const rawEvent = buffer.slice(0, delimiterIndex)
      buffer = buffer.slice(delimiterIndex + 2)
      flushEvent(rawEvent)
      delimiterIndex = buffer.indexOf('\n\n')
    }
  }

  const trailing = buffer.trim()
  if (trailing) {
    flushEvent(trailing)
  }

  if (!completedScript) {
    throw new Error('生成流已结束，但未收到完成结果')
  }

  return completedScript
}

export function getLatestScript(projectId: number) {
  return request<ScriptDocumentResponse>(`/api/projects/${projectId}/scripts/latest`)
}

export function scriptDownloadUrl(projectId: number) {
  return `/api/projects/${projectId}/scripts/latest/download`
}
