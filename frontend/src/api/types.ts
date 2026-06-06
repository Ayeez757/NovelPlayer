export interface ChapterResponse {
  index: number
  title: string
  contentLength: number
}

export interface ProjectResponse {
  id: number
  title: string
  status: string
  chapters: ChapterResponse[]
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  title: string
  sourceText: string
}

export interface GenerationRequest {
  format: string
  tone: string
  dialogueDensity: number
  narrationRetention: number
  additionalInstructions: string
}

export interface ScriptDocumentResponse {
  id: number
  projectId: number
  schemaVersion: string
  validationStatus: string
  yamlContent: string
  createdAt: string
}

export interface GenerationJobResponse {
  id: number
  projectId: number
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  currentStage: string
  errorMessage: string | null
  createdAt: string
  finishedAt: string | null
}
