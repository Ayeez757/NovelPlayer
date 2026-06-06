import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { CSSProperties, ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import { createProject, generateScript, scriptDownloadUrl } from '../../../api/projectApi'
import type { GenerationRequest, ProjectResponse, ScriptDocumentResponse } from '../../../api/types'
import {
  contentSections,
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
  const generationLogs = ref<GenerationLogItem[]>([])
  const selectedChapterIndexes = ref<number[]>([])

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

  const supportsChapterSelectionSubmission = false

  const currentSectionMeta = computed(
    () => contentSections.find((item) => item.id === currentSection.value) ?? contentSections[0]
  )
  const currentPageTypeLabel = computed(() => pageTypeLabels[currentPageType.value])
  const sourceCharacterCount = computed(() => form.sourceText.replace(/\s+/g, '').length)
  const totalChapterCount = computed(() => project.value?.chapters.length ?? 0)
  const selectedChapterCount = computed(() => selectedChapterIndexes.value.length)
  const hasSelectedChapters = computed(() => selectedChapterCount.value > 0)
  const hasCustomChapterSelection = computed(() => {
    return (
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

    contentSections.forEach((item) => {
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
    form.sourceText = sampleText()
    ElMessage.success('已恢复默认样例文本')
  }

  function clearSourceText() {
    form.sourceText = ''
  }

  function selectPageType(type: PageType) {
    currentPageType.value = type

    if (type === 'upload-convert') {
      ElMessage.info(`${pageTypeLabels[type]} 入口已经预留，下一步可以继续接上传流程。`)
    }
  }

  function toggleLeftSidebar() {
    if (isCompactLayout.value) {
      return
    }

    leftSidebarCollapsed.value = !leftSidebarCollapsed.value
  }

  async function create() {
    creating.value = true

    try {
      project.value = await createProject({
        title: form.title,
        sourceText: form.sourceText
      })
      syncChapterSelection(project.value.chapters.map((chapter) => chapter.index))
      script.value = null
      yamlDraft.value = ''
      generationStage.value = ''
      generationMessage.value = ''
      generationJobId.value = null
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
    generationStage.value = 'generating'
    generationMessage.value = '正在生成剧本'
    generationJobId.value = null
    generationLogs.value = []
    appendLog('任务启动', '已开始同步生成剧本。')

    try {
      const generatedScript = await generateScript(project.value.id, buildGenerationPayload())
      script.value = generatedScript
      yamlDraft.value = generatedScript.yamlContent
      generationStage.value = 'completed'
      generationMessage.value = '剧本生成完成'
      appendLog('生成完成', 'YAML 草稿已经写入编辑区。', 'success')
      ElMessage.success('剧本已生成')
    } catch (error) {
      const message = getErrorMessage(error)
      generationStage.value = 'failed'
      generationMessage.value = message
      appendLog('生成失败', message, 'error')
      ElMessage.error(message)
    } finally {
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

  function clearChapterSelection() {
    selectedChapterIndexes.value = []
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
    return {
      ...options
    }
  }

  return {
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
    generating,
    generationJobId,
    generationLogs,
    generationMessage,
    generationStage,
    hasCustomChapterSelection,
    hasSelectedChapters,
    isCompactLayout,
    leftSidebarCollapsed,
    options,
    project,
    restoreSampleText,
    script,
    scrollToSection,
    selectAllChapters,
    selectPageType,
    selectedChapterCount,
    selectedChapterIndexes,
    selectedChapterSummary,
    setSectionRef,
    sourceCharacterCount,
    startLeftSidebarResize,
    supportsChapterSelectionSubmission,
    toggleChapterSelection,
    toggleLeftSidebar,
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
