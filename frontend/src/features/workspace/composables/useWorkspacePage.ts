import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { CSSProperties, ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import { createProject, generateScriptStream, scriptDownloadUrl } from '../../../api/projectApi'
import type {
  GenerationRequest,
  GenerationStreamEvent,
  ProjectResponse,
  ScriptDocumentResponse
} from '../../../api/types'
import {
  contentSections,
  createDefaultForm,
  createDefaultGenerationOptions,
  pageTypeLabels,
  sampleText,
  type AiMessage,
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

  const currentSection = ref<SectionId>('text-input')
  const currentPageType = ref<PageType>('instant-write')
  const viewMode = ref<ViewMode>('vertical-flow')

  const workspaceBodyRef = ref<HTMLElement | null>(null)
  const leftSidebarCollapsed = ref(false)
  const leftSidebarWidth = ref(300)
  const aiPanelOpen = ref(false)
  const aiDraft = ref('')
  const aiPanelWidth = ref(420)
  const isResizingLeftSidebar = ref(false)
  const isResizingAi = ref(false)
  const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
  const aiMessages = ref<AiMessage[]>([
    {
      id: 1,
      role: 'assistant',
      content: '这里先作为前端演示用的 AI 面板。你可以问我当前流程该怎么走，或者让我们一起梳理页面结构。',
      time: formatTime()
    }
  ])

  const observedSections = new Map<SectionId, HTMLElement>()
  let sectionObserver: IntersectionObserver | null = null

  const currentSectionMeta = computed(
    () => contentSections.find((item) => item.id === currentSection.value) ?? contentSections[0]
  )
  const currentPageTypeLabel = computed(() => pageTypeLabels[currentPageType.value])
  const sourceCharacterCount = computed(() => form.sourceText.replace(/\s+/g, '').length)
  const isCompactLayout = computed(() => viewportWidth.value < 1100)

  const workspaceBodyStyle = computed<CSSProperties | undefined>(() => {
    if (isCompactLayout.value) {
      return undefined
    }

    const leftWidth = leftSidebarCollapsed.value ? 34 : leftSidebarWidth.value
    const rightWidth = aiPanelOpen.value ? aiPanelWidth.value : 0

    return {
      gridTemplateColumns: `${leftWidth}px minmax(0, 1fr) ${rightWidth}px`
    }
  })

  const aiPanelStyle = computed<CSSProperties>(() => {
    if (isCompactLayout.value) {
      return {
        width: 'min(92vw, 420px)'
      }
    }

    return {
      width: aiPanelOpen.value ? `${aiPanelWidth.value}px` : '0px'
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
      script.value = null
      yamlDraft.value = ''
      generationStage.value = ''
      generationMessage.value = ''
      generationJobId.value = null
      generationLogs.value = []
      appendLog('章节识别', '章节识别完成，项目上下文已建立。', 'success')
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

    generating.value = true
    script.value = null
    yamlDraft.value = ''
    generationStage.value = 'preparing_project'
    generationMessage.value = '正在创建生成任务'
    generationJobId.value = null
    generationLogs.value = []
    appendLog('任务启动', '已开始流式生成剧本。')

    try {
      const generatedScript = await generateScriptStream(project.value.id, options, handleGenerationEvent)
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

  function handleGenerationEvent(event: GenerationStreamEvent) {
    if (event.jobId) {
      generationJobId.value = event.jobId
    }

    if (event.stage) {
      generationStage.value = event.stage
    }

    if (event.message) {
      generationMessage.value = event.message
    }

    if (event.type === 'completed' && event.script) {
      script.value = event.script
      yamlDraft.value = event.script.yamlContent
    }

    if (event.message) {
      const stageLabel = event.stage || event.type
      const level =
        event.type === 'error' ? 'error' : event.type === 'completed' ? 'success' : 'info'
      appendLog(stageLabel, event.message, level)
    }
  }

  function downloadScript() {
    if (!project.value) {
      return
    }

    window.location.href = scriptDownloadUrl(project.value.id)
  }

  function toggleAiPanel() {
    aiPanelOpen.value = !aiPanelOpen.value

    if (aiPanelOpen.value && !isCompactLayout.value) {
      aiPanelWidth.value = clampAiPanelWidth(Math.round(viewportWidth.value * 0.32))
    }
  }

  function startLeftSidebarResize(event: PointerEvent) {
    if (isCompactLayout.value || leftSidebarCollapsed.value) {
      return
    }

    isResizingLeftSidebar.value = true
    event.preventDefault()
  }

  function startAiResize(event: PointerEvent) {
    if (isCompactLayout.value) {
      return
    }

    isResizingAi.value = true
    event.preventDefault()
  }

  function sendAiMessage() {
    const content = aiDraft.value.trim()

    if (!content) {
      return
    }

    aiMessages.value.push({
      id: Date.now(),
      role: 'user',
      content,
      time: formatTime()
    })

    aiDraft.value = ''

    window.setTimeout(() => {
      aiMessages.value.push({
        id: Date.now() + 1,
        role: 'assistant',
        content: buildAiReply(content),
        time: formatTime()
      })
    }, 280)
  }

  function buildAiReply(prompt: string) {
    if (prompt.includes('章节')) {
      return '建议先完成“识别章节”，这样章节列表和后续的生成上下文都会先建立起来。'
    }

    if (prompt.toLowerCase().includes('yaml')) {
      return 'YAML 初稿会在“YAML 初稿”模块出现，生成完成后你可以直接继续编辑。'
    }

    if (prompt.includes('当前') || prompt.includes('在哪')) {
      return `你当前聚焦的是“${currentSectionMeta.value.label}”模块，左侧目录会跟着滚动位置自动高亮。`
    }

    if (prompt.toLowerCase().includes('ai')) {
      return '这个 AI 面板目前先作为前端演示交互，主要帮助我们验证布局、消息流和后续接接口的位置。'
    }

    return '这个 AI 面板现在先服务于工作台演示。你可以继续问我当前步骤、布局职责，或者让我们一起梳理改编流程。'
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

  function handleSidebarResize(event: PointerEvent) {
    const rect = workspaceBodyRef.value?.getBoundingClientRect()

    if (!rect) {
      return
    }

    if (isResizingLeftSidebar.value) {
      leftSidebarWidth.value = clampLeftSidebarWidth(event.clientX - rect.left)
    }

    if (isResizingAi.value) {
      aiPanelWidth.value = clampAiPanelWidth(rect.right - event.clientX)
    }
  }

  function stopSidebarResize() {
    isResizingLeftSidebar.value = false
    isResizingAi.value = false
  }

  function handleViewportResize() {
    viewportWidth.value = window.innerWidth
    leftSidebarWidth.value = clampLeftSidebarWidth(leftSidebarWidth.value)
    aiPanelWidth.value = clampAiPanelWidth(aiPanelWidth.value)
  }

  return {
    aiDraft,
    aiMessages,
    aiPanelOpen,
    aiPanelStyle,
    contentSections,
    create,
    creating,
    clearSourceText,
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
    isCompactLayout,
    leftSidebarCollapsed,
    options,
    pageTypeLabels,
    project,
    restoreSampleText,
    script,
    scrollToSection,
    selectPageType,
    sendAiMessage,
    setSectionRef,
    sourceCharacterCount,
    startAiResize,
    startLeftSidebarResize,
    toggleAiPanel,
    toggleLeftSidebar,
    viewMode,
    viewModeOptions,
    workspaceBodyRef,
    workspaceBodyStyle,
    yamlDraft
  }
}

function clampAiPanelWidth(value: number) {
  const maxWidth = Math.max(400, Math.floor(window.innerWidth * 0.45))
  return Math.min(Math.max(value, 360), maxWidth)
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
