import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { CSSProperties, ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createGenerationJob,
  createProject,
  getGenerationJob,
  getLatestScript,
  scriptDownloadUrl
} from '../../../api/projectApi'
import type {
  GenerationJobResponse,
  GenerationRequest,
  ProjectResponse,
  ScriptDocumentResponse
} from '../../../api/types'
import {
  contentSections as baseContentSections,
  createDefaultForm,
  createDefaultGenerationOptions,
  pageTypeLabels,
  sampleText,
  type GenerationLogItem,
  type PageType,
  type SectionId,
  type ViewMode,
  viewModeOptions
} from '../model/workspaceConfig'

const GENERATION_POLL_INTERVAL_MS = 1600
const MAX_GENERATION_POLL_FAILURES = 3
const SUPPORTED_TEXT_FILE_EXTENSIONS = ['txt']
const SOURCE_FILE_ACCEPT = '.txt,text/plain'
const DEFAULT_TITLE = '未命名作品'

export function useWorkspacePage() {
  const form = reactive(createDefaultForm())
  const options = reactive<GenerationRequest>(createDefaultGenerationOptions())

  const creating = ref(false)
  const generating = ref(false)
  const project = ref<ProjectResponse | null>(null)
  const script = ref<ScriptDocumentResponse | null>(null)
  const yamlDraft = ref('')
  const generationStage = ref('')
  const generationMessage = ref('')
  const generationJobId = ref<number | null>(null)
  const generationPollPaused = ref(false)
  const generationLogs = ref<GenerationLogItem[]>([])
  const selectedChapterIndexes = ref<number[]>([])
  const uploadedFileName = ref('')
  const uploadedFileEncoding = ref('')
  const uploadedFileSize = ref(0)

  const currentSection = ref<SectionId>('text-input')
  const currentPageType = ref<PageType>('instant-write')
  const viewMode = ref<ViewMode>('vertical-flow')

  const workspaceBodyRef = ref<HTMLElement | null>(null)
  const leftSidebarCollapsed = ref(false)
  const leftSidebarWidth = ref(300)
  const isResizingLeftSidebar = ref(false)
  const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)

  const observedSections = new Map<SectionId, HTMLElement>()
  let sectionObserver: IntersectionObserver | null = null
  let generationPollTimer: ReturnType<typeof setTimeout> | null = null
  let generationPollFailureCount = 0
  let lastLoggedJobProgressKey = ''

  // 先把章节选择交互放开，等后端补入参后再把选择同步到请求体。
  const supportsChapterSelectionSubmission = true

  const isUploadMode = computed(() => currentPageType.value === 'upload-convert')
  const hasUploadedFile = computed(() => uploadedFileName.value.length > 0)
  const contentSections = computed(() => {
    return baseContentSections.map((item) => {
      if (item.id !== 'text-input') {
        return item
      }

      return isUploadMode.value
        ? {
            ...item,
            label: '上传文件',
            hint: '导入 txt 原文'
          }
        : item
    })
  })
  const currentSectionMeta = computed(
    () => contentSections.value.find((item) => item.id === currentSection.value) ?? contentSections.value[0]
  )
  const currentPageTypeLabel = computed(() => pageTypeLabels[currentPageType.value])
  const sourceCharacterCount = computed(() => form.sourceText.replace(/\s+/g, '').length)
  const totalChapterCount = computed(() => project.value?.chapters.length ?? 0)
  const selectedChapterCount = computed(() => {
    if (!project.value) {
      return selectedChapterIndexes.value.length
    }

    if (!supportsChapterSelectionSubmission) {
      return totalChapterCount.value
    }

    return selectedChapterIndexes.value.length
  })
  const hasSelectedChapters = computed(() => selectedChapterCount.value > 0)
  const hasCustomChapterSelection = computed(() => {
    return (
      supportsChapterSelectionSubmission &&
      project.value !== null &&
      selectedChapterCount.value > 0 &&
      selectedChapterCount.value < totalChapterCount.value
    )
  })
  const selectedChapterSummary = computed(() => {
    if (!project.value) {
      return '等待识别章节'
    }

    return `${selectedChapterCount.value}/${totalChapterCount.value} 章已选`
  })
  const uploadedFileSizeLabel = computed(() => formatFileSize(uploadedFileSize.value))
  const uploadedFileEncodingLabel = computed(() => {
    return uploadedFileEncoding.value ? uploadedFileEncoding.value.toUpperCase() : '未识别'
  })
  const canPauseGeneration = computed(
    () => generating.value && generationJobId.value !== null && !generationPollPaused.value
  )
  const canResumeGeneration = computed(
    () => generating.value && generationJobId.value !== null && generationPollPaused.value
  )
  const generateButtonLoading = computed(
    () => generating.value && generationJobId.value !== null && !generationPollPaused.value
  )
  const submitStatusLabel = computed(() => {
    if (!generating.value) {
      return '等待生成'
    }

    return generationPollPaused.value ? '生成中（已暂停轮询）' : '生成中'
  })
  const chapterSelectionHint = computed(() => {
    if (!project.value) {
      return '章节识别完成后，这里会展示每章摘要与参与生成范围。'
    }

    if (!supportsChapterSelectionSubmission) {
      return '当前会默认按全部章节生成。'
    }

    if (!selectedChapterCount.value) {
      return '请至少选择 1 章后再生成。'
    }

    if (hasCustomChapterSelection.value) {
      return '当前将按已勾选章节参与生成。'
    }

    return '当前将按全部已识别章节参与生成。'
  })
  const isCompactLayout = computed(() => viewportWidth.value < 1100)

  const workspaceBodyStyle = computed<CSSProperties | undefined>(() => {
    if (isCompactLayout.value) {
      return undefined
    }

    const leftWidth = leftSidebarCollapsed.value ? 34 : leftSidebarWidth.value
    return {
      gridTemplateColumns: `${leftWidth}px minmax(0, 1fr)`
    }
  })

  onMounted(() => {
    nextTick(() => {
      setupSectionObserver()
    })

    window.addEventListener('pointermove', handleSidebarResize)
    window.addEventListener('pointerup', stopSidebarResize)
    window.addEventListener('resize', handleViewportResize)
  })

  onBeforeUnmount(() => {
    stopGenerationPolling()
    sectionObserver?.disconnect()
    window.removeEventListener('pointermove', handleSidebarResize)
    window.removeEventListener('pointerup', stopSidebarResize)
    window.removeEventListener('resize', handleViewportResize)
  })

  function setSectionRef(id: SectionId) {
    return (element: Element | ComponentPublicInstance | null) => {
      if (element instanceof HTMLElement) {
        observedSections.set(id, element)
        return
      }

      observedSections.delete(id)
    }
  }

  function setupSectionObserver() {
    sectionObserver?.disconnect()

    sectionObserver = new IntersectionObserver(
      (entries) => {
        const visibleEntries = entries
          .filter((entry) => entry.isIntersecting)
          .sort((first, second) => second.intersectionRatio - first.intersectionRatio)

        if (!visibleEntries.length) {
          return
        }

        const nextSection = visibleEntries[0].target.getAttribute('data-section-id') as SectionId | null

        if (nextSection) {
          currentSection.value = nextSection
        }
      },
      {
        root: null,
        rootMargin: '-18% 0px -52% 0px',
        threshold: [0.12, 0.3, 0.55]
      }
    )

    contentSections.value.forEach((item) => {
      const element = observedSections.get(item.id)
      if (element) {
        sectionObserver?.observe(element)
      }
    })
  }

  function scrollToSection(id: SectionId) {
    observedSections.get(id)?.scrollIntoView({
      behavior: 'smooth',
      block: 'start'
    })
  }

  function formatIndex(value: number) {
    return value.toString().padStart(2, '0')
  }

  function restoreSampleText() {
    clearUploadedFileMeta()
    resetWorkspaceFlow()
    form.sourceText = sampleText()
    ElMessage.success('已恢复默认样例文本')
  }

  function clearSourceText() {
    clearUploadedFileMeta()
    resetWorkspaceFlow()
    form.sourceText = ''
  }

  async function loadSourceFile(file: File) {
    validateSourceFile(file)

    const nextTitle = stripFileExtension(file.name)
    const previousImportedTitle = stripFileExtension(uploadedFileName.value)
    const buffer = await file.arrayBuffer()
    const { text, encoding } = decodeTextBuffer(buffer)
    const normalizedText = normalizeSourceText(text)

    if (!normalizedText.trim()) {
      throw new Error('读取到的文本内容为空，请检查文件内容后重试。')
    }

    resetWorkspaceFlow()
    form.sourceText = normalizedText

    if (
      !form.title.trim() ||
      form.title === DEFAULT_TITLE ||
      (previousImportedTitle && form.title === previousImportedTitle)
    ) {
      form.title = nextTitle || DEFAULT_TITLE
    }

    uploadedFileName.value = file.name
    uploadedFileEncoding.value = encoding
    uploadedFileSize.value = file.size

    appendLog(
      '文件导入',
      `已读取《${file.name}》，共 ${sourceCharacterCount.value.toLocaleString('zh-CN')} 字。`,
      'success'
    )
    ElMessage.success(`已导入 ${file.name}，后续流程将按文本模式继续。`)
  }

  function selectPageType(type: PageType) {
    currentPageType.value = type
  }

  function toggleLeftSidebar() {
    if (isCompactLayout.value) {
      return
    }

    leftSidebarCollapsed.value = !leftSidebarCollapsed.value
  }

  async function create() {
    if (!form.title.trim()) {
      ElMessage.warning('请输入作品标题')
      return
    }

    if (!form.sourceText.trim()) {
      ElMessage.warning(isUploadMode.value ? '请先上传 txt 文件或补充文本内容' : '请先输入小说正文')
      return
    }

    stopGenerationPolling()
    creating.value = true
    generating.value = false
    generationPollPaused.value = false
    generationJobId.value = null
    generationStage.value = ''
    generationMessage.value = ''

    try {
      project.value = await createProject({
        title: form.title,
        sourceText: form.sourceText
      })
      syncChapterSelection(project.value.chapters.map((chapter) => chapter.index))
      script.value = null
      yamlDraft.value = ''
      generationLogs.value = []
      appendLog(
        '章节识别',
        `章节识别完成，已载入 ${project.value.chapters.length} 章并默认全选。`,
        'success'
      )
      ElMessage.success('章节识别完成')
    } catch (error) {
      const message = getErrorMessage(error)
      appendLog('章节识别', message, 'error')
      ElMessage.error(message)
    } finally {
      creating.value = false
    }
  }

  async function generate() {
    if (!project.value) {
      return
    }

    if (!hasSelectedChapters.value) {
      ElMessage.warning('请至少选择 1 章再生成')
      return
    }

    if (hasCustomChapterSelection.value && !supportsChapterSelectionSubmission) {
      ElMessage.warning('当前后端还没有章节选择入参，暂时只能按全部章节生成。')
      appendLog('章节选择', '检测到部分章节被选中，但当前后端接口尚未提供章节选择入参。', 'error')
      return
    }

    generating.value = true
    script.value = null
    yamlDraft.value = ''
    generationStage.value = 'created'
    generationMessage.value = '正在创建后台生成任务'
    generationJobId.value = null
    generationPollPaused.value = false
    generationLogs.value = []
    generationPollFailureCount = 0
    lastLoggedJobProgressKey = ''
    stopGenerationPolling()
    appendLog('任务启动', '正在提交后台生成任务。')

    try {
      const job = await createGenerationJob(project.value.id, buildGenerationPayload())
      applyGenerationJob(job)
      appendLog('任务创建', `生成任务 #${job.id} 已创建，开始后台处理。`)
      void pollGenerationJob(job.id)
    } catch (error) {
      const message = getErrorMessage(error)
      generationStage.value = 'failed'
      generationMessage.value = message
      appendLog('生成失败', message, 'error')
      ElMessage.error(message)
      generating.value = false
    }
  }

  function downloadScript() {
    if (!project.value) {
      return
    }

    window.location.href = scriptDownloadUrl(project.value.id)
  }

  function toggleChapterSelection(index: number) {
    const nextSelection = new Set(selectedChapterIndexes.value)

    if (nextSelection.has(index)) {
      nextSelection.delete(index)
    } else {
      nextSelection.add(index)
    }

    syncChapterSelection(Array.from(nextSelection))
  }

  function selectAllChapters() {
    if (!project.value) {
      return
    }

    syncChapterSelection(project.value.chapters.map((chapter) => chapter.index))
  }

  function invertChapterSelection() {
    if (!project.value) {
      return
    }

    const selectedSet = new Set(selectedChapterIndexes.value)
    syncChapterSelection(
      project.value.chapters
        .map((chapter) => chapter.index)
        .filter((index) => !selectedSet.has(index))
    )
  }

  function clearChapterSelection() {
    syncChapterSelection([])
  }

  function pauseGenerationPolling() {
    if (!canPauseGeneration.value) {
      return
    }

    stopGenerationPolling()
    generationPollPaused.value = true
    generationMessage.value = '生成仍在后台继续，当前已暂停进度轮询'
    appendLog('轮询已暂停', '已暂停前端进度轮询，后台任务仍在继续。')
  }

  function resumeGenerationPolling() {
    if (!canResumeGeneration.value || generationJobId.value === null) {
      return
    }

    generationPollPaused.value = false
    generationMessage.value = '正在恢复进度轮询'
    appendLog('轮询已恢复', `重新连接任务 #${generationJobId.value} 的执行进度。`)
    void pollGenerationJob(generationJobId.value)
  }

  function startLeftSidebarResize(event: PointerEvent) {
    if (isCompactLayout.value || leftSidebarCollapsed.value) {
      return
    }

    isResizingLeftSidebar.value = true
    event.preventDefault()
  }

  function handleSidebarResize(event: PointerEvent) {
    const rect = workspaceBodyRef.value?.getBoundingClientRect()

    if (!rect || !isResizingLeftSidebar.value) {
      return
    }

    leftSidebarWidth.value = clampLeftSidebarWidth(event.clientX - rect.left)
  }

  function stopSidebarResize() {
    isResizingLeftSidebar.value = false
  }

  function handleViewportResize() {
    viewportWidth.value = window.innerWidth
    leftSidebarWidth.value = clampLeftSidebarWidth(leftSidebarWidth.value)
  }

  function appendLog(stage: string, message: string, level: GenerationLogItem['level'] = 'info') {
    generationLogs.value.unshift({
      id: Date.now() + generationLogs.value.length,
      time: formatTime(),
      stage,
      message,
      level
    })
  }

  function stopGenerationPolling() {
    if (generationPollTimer !== null) {
      clearTimeout(generationPollTimer)
      generationPollTimer = null
    }
  }

  function scheduleGenerationPoll(jobId: number) {
    stopGenerationPolling()
    generationPollTimer = setTimeout(() => {
      void pollGenerationJob(jobId)
    }, GENERATION_POLL_INTERVAL_MS)
  }

  async function pollGenerationJob(jobId: number) {
    try {
      const job = await getGenerationJob(jobId)

      if (generationJobId.value !== jobId) {
        return
      }

      generationPollFailureCount = 0
      applyGenerationJob(job)

      if (job.status === 'SUCCEEDED') {
        await completeGenerationJob(job)
        return
      }

      if (job.status === 'FAILED') {
        failGenerationJob(job.errorMessage || '剧本生成失败')
        return
      }

      if (generationPollPaused.value) {
        return
      }

      scheduleGenerationPoll(jobId)
    } catch (error) {
      if (generationJobId.value !== jobId) {
        return
      }

      generationPollFailureCount += 1
      const message = getErrorMessage(error)
      appendLog('进度轮询', `第 ${generationPollFailureCount} 次查询失败：${message}`, 'error')

      if (generationPollFailureCount < MAX_GENERATION_POLL_FAILURES) {
        generationMessage.value = '进度查询暂时失败，正在重试'
        scheduleGenerationPoll(jobId)
        return
      }

      stopGenerationPolling()
      generationStage.value = 'failed'
      generationMessage.value = message
      generating.value = false
      ElMessage.error(message)
    }
  }

  function applyGenerationJob(job: GenerationJobResponse) {
    generationJobId.value = job.id
    generationStage.value = normalizeGenerationStage(job)
    /*
     * 旧调用保留原函数名：
     * generationMessage.value = describeGenerationJob(job)
     */
    generationMessage.value = describeGenerationJobStable(job)

    const progressKey = `${job.status}:${generationStage.value}:${job.errorMessage || ''}`
    if (progressKey === lastLoggedJobProgressKey) {
      return
    }

    lastLoggedJobProgressKey = progressKey
    appendLog(
      getGenerationStageLabel(generationStage.value),
      generationMessage.value,
      job.status === 'FAILED' ? 'error' : job.status === 'SUCCEEDED' ? 'success' : 'info'
    )
  }

  async function completeGenerationJob(job: GenerationJobResponse) {
    stopGenerationPolling()
    generationStage.value = 'completed'
    generationPollPaused.value = false
    generationMessage.value = '剧本生成完成，正在读取最新 YAML'

    try {
      const latestScript = await getLatestScript(job.projectId)

      if (generationJobId.value !== job.id) {
        return
      }

      script.value = latestScript
      yamlDraft.value = latestScript.yamlContent
      generationStage.value = 'completed'
      generationMessage.value = '剧本生成完成'
      generating.value = false
      appendLog('生成完成', 'YAML 草稿已经写入编辑区。', 'success')
      ElMessage.success('剧本已生成')
    } catch (error) {
      const message = getErrorMessage(error)
      generationMessage.value = `生成已完成，但读取最新剧本失败：${message}`
      generating.value = false
      appendLog('结果读取', generationMessage.value, 'error')
      ElMessage.error(message)
    }
  }

  function failGenerationJob(message: string) {
    stopGenerationPolling()
    generationStage.value = 'failed'
    generationPollPaused.value = false
    generationMessage.value = message
    generating.value = false
    appendLog('生成失败', message, 'error')
    ElMessage.error(message)
  }

  function resetWorkspaceFlow() {
    stopGenerationPolling()
    creating.value = false
    generating.value = false
    project.value = null
    script.value = null
    yamlDraft.value = ''
    generationStage.value = ''
    generationMessage.value = ''
    generationJobId.value = null
    generationPollPaused.value = false
    generationLogs.value = []
    selectedChapterIndexes.value = []
    generationPollFailureCount = 0
    lastLoggedJobProgressKey = ''
  }

  function clearUploadedFileMeta() {
    uploadedFileName.value = ''
    uploadedFileEncoding.value = ''
    uploadedFileSize.value = 0
  }

  function syncChapterSelection(indexes: number[]) {
    if (!project.value) {
      selectedChapterIndexes.value = indexes
      return
    }

    const availableIndexes = project.value.chapters.map((chapter) => chapter.index)
    const selectedSet = new Set(indexes)
    selectedChapterIndexes.value = availableIndexes.filter((index) => selectedSet.has(index))
  }

  function buildGenerationPayload(): GenerationRequest {
    // 后端暂未接收章节选择入参，这里先沿用现有请求结构。
    return {
      ...options
    }
  }

  return {
    canPauseGeneration,
    canResumeGeneration,
    chapterSelectionHint,
    clearChapterSelection,
    clearSourceText,
    contentSections,
    create,
    creating,
    currentPageType,
    currentPageTypeLabel,
    currentSection,
    currentSectionMeta,
    downloadScript,
    form,
    formatIndex,
    generate,
    generateButtonLoading,
    generating,
    generationJobId,
    generationLogs,
    generationMessage,
    generationStage,
    hasCustomChapterSelection,
    hasSelectedChapters,
    hasUploadedFile,
    isCompactLayout,
    isUploadMode,
    invertChapterSelection,
    leftSidebarCollapsed,
    loadSourceFile,
    options,
    pauseGenerationPolling,
    project,
    restoreSampleText,
    resumeGenerationPolling,
    script,
    scrollToSection,
    selectAllChapters,
    selectPageType,
    selectedChapterCount,
    selectedChapterIndexes,
    selectedChapterSummary,
    setSectionRef,
    sourceCharacterCount,
    sourceFileAccept: SOURCE_FILE_ACCEPT,
    startLeftSidebarResize,
    submitStatusLabel,
    supportsChapterSelectionSubmission,
    toggleChapterSelection,
    toggleLeftSidebar,
    uploadedFileEncodingLabel,
    uploadedFileName,
    uploadedFileSizeLabel,
    viewMode,
    viewModeOptions,
    workspaceBodyRef,
    workspaceBodyStyle,
    yamlDraft
  }
}

function clampLeftSidebarWidth(value: number) {
  const maxWidth = Math.max(280, Math.floor(window.innerWidth * 0.36))
  return Math.min(Math.max(value, 260), maxWidth)
}

function formatTime() {
  return new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : '发生了未知错误'
}

function validateSourceFile(file: File) {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''

  if (!SUPPORTED_TEXT_FILE_EXTENSIONS.includes(extension)) {
    throw new Error('当前仅支持上传 .txt 文件。')
  }

  if (file.size <= 0) {
    throw new Error('上传文件为空，请重新选择有效的 txt 文件。')
  }
}

function decodeTextBuffer(buffer: ArrayBuffer) {
  const encodings = ['utf-8', 'gb18030']

  for (const encoding of encodings) {
    try {
      const text = new TextDecoder(encoding, { fatal: true }).decode(buffer)
      return { text, encoding }
    } catch {
      continue
    }
  }

  return {
    text: new TextDecoder('utf-8').decode(buffer),
    encoding: 'utf-8'
  }
}

function normalizeSourceText(value: string) {
  return value.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

function stripFileExtension(fileName: string) {
  return fileName.replace(/\.[^.]+$/, '')
}

function formatFileSize(value: number) {
  if (value <= 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB']
  let size = value
  let unitIndex = 0

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex += 1
  }

  const digits = unitIndex === 0 ? 0 : size >= 10 ? 1 : 2
  return `${size.toFixed(digits)} ${units[unitIndex]}`
}

const generationStageLabels: Record<string, string> = {
  created: '任务已创建',
  generation_input: '记录输入快照',
  script_generation: '调度生成管线',
  staged_script_generation: '多阶段生成',
  legacy_script_generation: '旧链路生成',
  chapter_digest: '章节摘要',
  // 只改前端展示文案，内部阶段名 story_bible 保持不变，避免扩大后端改动面。
  story_bible: '故事设定集',
  scene_plan: '场景规划',
  scene_draft: '分场草稿',
  script_assembly: '最终组装',
  serializing_json: '序列化 JSON',
  exporting_yaml: '导出 YAML',
  saving_snapshot: '保存结果',
  completed: '已完成',
  failed: '失败'
}

function normalizeGenerationStage(job: GenerationJobResponse) {
  if (job.status === 'SUCCEEDED') {
    return 'completed'
  }

  if (job.status === 'FAILED') {
    return 'failed'
  }

  return job.currentStage?.trim() || 'created'
}

function describeGenerationJob(job: GenerationJobResponse) {
  const stage = normalizeGenerationStage(job)
  const progressText = job.progress
    ? `（${job.progress.completed}/${job.progress.total}，失败 ${job.progress.failed}）`
    : ''

  if (job.status === 'PENDING') {
    return '任务已创建，等待后台执行'
  }

  if (job.status === 'RUNNING') {
    /*
     * 新逻辑把阶段内进度拼进现有文案里。
     * 旧 return 保留在后面作参考，但不会再执行。
     */
    return `姝ｅ湪${getGenerationStageLabel(stage)}${progressText}`
    return `正在${getGenerationStageLabel(stage)}`
  }

  if (job.status === 'SUCCEEDED') {
    return '生成完成，正在读取最新剧本'
  }

  return job.errorMessage || '剧本生成失败'
}

function describeGenerationJobStable(job: GenerationJobResponse) {
  const stage = normalizeGenerationStage(job)
  const progressText = job.progress
    ? ` (${job.progress.completed}/${job.progress.total}, failed ${job.progress.failed})`
    : ''

  if (job.status === 'PENDING') {
    return 'Task created, waiting for backend execution'
  }

  if (job.status === 'RUNNING') {
    return `Running ${getGenerationStageLabel(stage)}${progressText}`
  }

  if (job.status === 'SUCCEEDED') {
    return 'Generation completed, loading latest script'
  }

  return job.errorMessage || 'Script generation failed'
}

function getGenerationStageLabel(stage: string) {
  if (stage.startsWith('chapter_digest:')) {
    return `章节摘要 ${stage.slice('chapter_digest:'.length)}`
  }

  if (stage.startsWith('scene_draft:')) {
    return `分场草稿 ${stage.slice('scene_draft:'.length)}`
  }

  return generationStageLabels[stage] || stage || '未知阶段'
}
