import type { GenerationRequest } from '../../../api/types'

export type SectionId =
  | 'text-input'
  | 'identify-chapters'
  | 'confirm-submit'
  | 'identify-results'
  | 'stream-log'

export interface SectionMeta {
  id: SectionId
  label: string
  hint: string
}

export interface GenerationLogItem {
  id: number
  time: string
  stage: string
  message: string
  level: 'info' | 'success' | 'error'
}

export interface AiMessage {
  id: number
  role: 'assistant' | 'user'
  content: string
  time: string
}

export interface WorkspaceForm {
  title: string
  sourceText: string
}

export type PageType = 'upload-convert' | 'instant-write'
export type ViewMode = 'vertical-flow' | 'horizontal-compare'

export const contentSections: SectionMeta[] = [
  { id: 'text-input', label: '文本输入', hint: '录入小说原文' },
  { id: 'identify-chapters', label: '识别章节', hint: '执行章节切分' },
  { id: 'confirm-submit', label: '确认提交', hint: '确认改编设置' },
  { id: 'identify-results', label: 'YAML 结果', hint: '查看生成草稿' },
  { id: 'stream-log', label: '生成日志', hint: '跟踪执行过程' }
]

export const pageTypeLabels: Record<PageType, string> = {
  'upload-convert': '上传文件转剧本',
  'instant-write': '即时输入转剧本'
}

export const viewModeOptions: Array<{ label: string; value: ViewMode }> = [
  { label: '纵向流程', value: 'vertical-flow' },
  { label: '横向对比', value: 'horizontal-compare' }
]

export function createDefaultForm(): WorkspaceForm {
  return {
    title: '未命名作品',
    sourceText: sampleText()
  }
}

export function createDefaultGenerationOptions(): GenerationRequest {
  return {
    format: 'web_drama',
    tone: 'suspense',
    dialogueDensity: 60,
    narrationRetention: 30
  }
}

export function sampleText() {
  return `第一章 雨夜来信
雨从傍晚一直下到深夜。林安推开旧书店的门，听见风铃轻轻一响。柜台后没有人，只有一本摊开的旧书压着一封没有署名的信。她认出信封上的字迹，那是父亲失踪前常用的钢笔笔迹。

第二章 缺页
信里只有一句话：不要相信第七页。林安翻开旧书，却发现第七页被人整齐撕掉。书页夹缝里残留着一点烧焦的纸灰。门外传来脚步声，她把信塞进口袋，抬头看见一个陌生男人站在雨里。

第三章 交易
男人说自己知道林安父亲的下落，但要她拿旧书来换。林安没有回答，只问他为什么害怕第七页。男人沉默片刻，告诉她：那一页写着所有人的名字，包括她自己的。`
}
