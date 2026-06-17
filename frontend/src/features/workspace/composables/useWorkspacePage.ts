/**
 * 工作区页面组合式函数
 *
 * 负责管理工作区页面的核心状态和交互逻辑，包括：
 * - 文本输入/文件上传
 * - 章节识别与选择
 * - 剧本生成流程控制
 * - 进度轮询与状态管理
 * - 侧边栏布局控制
 */
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

// ==================== 常量定义 ====================

// 生成状态轮询间隔（毫秒）
const GENERATION_POLL_INTERVAL_MS = 1600
// 最大连续轮询失败次数
const MAX_GENERATION_POLL_FAILURES = 3
// 支持的文件扩展名
const SUPPORTED_TEXT_FILE_EXTENSIONS = ['txt']
// 文件选择器接受的文件类型
const SOURCE_FILE_ACCEPT = '.txt,text/plain'
// 默认作品标题
const DEFAULT_TITLE = '未命名作品'

// ==================== 组合式函数主体 ====================

export function useWorkspacePage() {
  // ---------- 响应式状态 ----------

  // 表单数据（作品标题、正文等）
  const form = reactive(createDefaultForm())
  // 生成选项配置
  const options = reactive<GenerationRequest>(createDefaultGenerationOptions())

  // 创建作品相关状态
  const creating = ref(false)
  // 生成中状态
  const generating = ref(false)
  // 当前项目数据
  const project = ref<ProjectResponse | null>(null)
  // 当前剧本数据
  const script = ref<ScriptDocumentResponse | null>(null)
  // YAML 草稿内容
  const yamlDraft = ref('')
  // 当前生成阶段名称
  const generationStage = ref('')
  // 当前生成状态消息
  const generationMessage = ref('')
  // 当前生成任务 ID
  const generationJobId = ref<number | null>(null)
  // 生成轮询是否暂停
  const generationPollPaused = ref(false)
  // 生成日志列表
  const generationLogs = ref<GenerationLogItem[]>([])
  // 已选章节索引列表
  const selectedChapterIndexes = ref<number[]>([])
  // 上传文件名
  const uploadedFileName = ref('')
  // 上传文件编码
  const uploadedFileEncoding = ref('')
  // 上传文件大小
  const uploadedFileSize = ref(0)

  // 当前激活的区块 ID
  const currentSection = ref<SectionId>('text-input')
  // 当前页面类型（即时写作 / 上传转换）
  const currentPageType = ref<PageType>('instant-write')
  // 当前视图模式（垂直流式 / 左右分栏）
  const viewMode = ref<ViewMode>('vertical-flow')

  // 工作区容器 DOM 引用
  const workspaceBodyRef = ref<HTMLElement | null>(null)
  // 左侧边栏是否折叠
  const leftSidebarCollapsed = ref(false)
  // 左侧边栏宽度
  const leftSidebarWidth = ref(300)
  // 是否正在拖拽调整左侧边栏
  const isResizingLeftSidebar = ref(false)
  // 视口宽度
  const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)

  // 已观察的区块元素映射
  const observedSections = new Map<SectionId, HTMLElement>()
  // 区块交叉观察器
  let sectionObserver: IntersectionObserver | null = null
  // 生成轮询定时器
  let generationPollTimer: ReturnType<typeof setTimeout> | null = null
  // 生成轮询失败计数
  let generationPollFailureCount = 0
  // 上次记录的作业进度键
  let lastLoggedJobProgressKey = ''

  // 后端是否支持章节选择提交（当前为 true）
  const supportsChapterSelectionSubmission = true

  // ---------- 计算属性 ----------

  // 是否为上传模式
  const isUploadMode = computed(() => currentPageType.value === 'upload-convert')
  // 是否已上传文件
  const hasUploadedFile = computed(() => uploadedFileName.value.length > 0)
  // 内容区块配置（根据模式动态调整）
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
  // 当前区块元信息
  const currentSectionMeta = computed(
      () => contentSections.value.find((item) => item.id === currentSection.value) ?? contentSections.value[0]
  )
  // 当前页面类型标签
  const currentPageTypeLabel = computed(() => pageTypeLabels[currentPageType.value])
  // 源文本字符数（不含空白）
  const sourceCharacterCount = computed(() => form.sourceText.replace(/\s+/g, '').length)
  // 总章节数
  const totalChapterCount = computed(() => project.value?.chapters.length ?? 0)
  // 已选章节数
  const selectedChapterCount = computed(() => {
    if (!project.value) {
      return selectedChapterIndexes.value.length
    }

    if (!supportsChapterSelectionSubmission) {
      return totalChapterCount.value
    }

    return selectedChapterIndexes.value.length
  })
  // 是否有已选章节
  const hasSelectedChapters = computed(() => selectedChapterCount.value > 0)
  // 是否为自定义章节选择
  const hasCustomChapterSelection = computed(() => {
    return (
        supportsChapterSelectionSubmission &&
        project.value !== null &&
        selectedChapterCount.value > 0 &&
        selectedChapterCount.value < totalChapterCount.value
    )
  })
  // 已选章节摘要信息
  const selectedChapterSummary = computed(() => {
    if (!project.value) {
      return '等待识别章节'
    }

    return `${selectedChapterCount.value}/${totalChapterCount.value} 章已选`
  })
  // 上传文件大小标签
  const uploadedFileSizeLabel = computed(() => formatFileSize(uploadedFileSize.value))
  // 上传文件编码标签
  const uploadedFileEncodingLabel = computed(() => {
    return uploadedFileEncoding.value ? uploadedFileEncoding.value.toUpperCase() : '未识别'
  })
  // 是否可以暂停生成
  const canPauseGeneration = computed(
      () => generating.value && generationJobId.value !== null && !generationPollPaused.value
  )
  // 是否可以恢复生成
  const canResumeGeneration = computed(
      () => generating.value && generationJobId.value !== null && generationPollPaused.value
  )
  // 生成按钮是否处于加载状态
  const generateButtonLoading = computed(
      () => generating.value && generationJobId.value !== null && !generationPollPaused.value
  )
  // 提交状态标签
  const submitStatusLabel = computed(() => {
    if (!generating.value) {
      return '等待生成'
    }

    return generationPollPaused.value ? '生成中（已暂停轮询）' : '生成中'
  })
  // 章节选择提示信息
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
  // 是否为紧凑布局
  const isCompactLayout = computed(() => viewportWidth.value < 1100)

  // 工作区容器样式（动态调整侧边栏宽度）
  const workspaceBodyStyle = computed<CSSProperties | undefined>(() => {
    if (isCompactLayout.value) {
      return undefined
    }

    const leftWidth = leftSidebarCollapsed.value ? 34 : leftSidebarWidth.value
    return {
      gridTemplateColumns: `${leftWidth}px minmax(0, 1fr)`
    }
  })

  // ---------- 生命周期钩子 ----------

  onMounted(() => {
    nextTick(() => {
      setupSectionObserver()
    })

    // 绑定全局事件
    window.addEventListener('pointermove', handleSidebarResize)
    window.addEventListener('pointerup', stopSidebarResize)
    window.addEventListener('resize', handleViewportResize)
  })

  onBeforeUnmount(() => {
    // 清理资源
    stopGenerationPolling()
    sectionObserver?.disconnect()
    window.removeEventListener('pointermove', handleSidebarResize)
    window.removeEventListener('pointerup', stopSidebarResize)
    window.removeEventListener('resize', handleViewportResize)
  })

  // ---------- 核心方法 ----------

  /**
   * 设置区块 DOM 引用
   * @param id 区块 ID
   * @returns 元素引用回调
   */
  function setSectionRef(id: SectionId) {
    return (element: Element | ComponentPublicInstance | null) => {
      if (element instanceof HTMLElement) {
        observedSections.set(id, element)
        return
      }

      observedSections.delete(id)
    }
  }

  /**
   * 初始化区块交叉观察器
   * 监听页面可视区块，自动更新当前激活区块 ID
   * 实现滚动时识别屏幕内占比最高的区块
   */
  function setupSectionObserver() {
    sectionObserver?.disconnect()

    sectionObserver = new IntersectionObserver(
        (entries) => {
          // 过滤出当前进入可视区域的区块，按可视占比从大到小排序
          const visibleEntries = entries
              .filter((entry) => entry.isIntersecting)
              .sort((first, second) => second.intersectionRatio - first.intersectionRatio)

          if (!visibleEntries.length) {
            return
          }

          // 取可视占比最高的区块
          const nextSection = visibleEntries[0].target.getAttribute('data-section-id') as SectionId | null

          if (nextSection) {
            currentSection.value = nextSection
          }
        },
        {
          root: null, // 根容器为浏览器视口
          // 裁剪可视判定区域：只识别屏幕中间核心区域
          rootMargin: '-18% 0px -52% 0px',
          threshold: [0.12, 0.3, 0.55]
        }
    )
  }

  /**
   * 滚动到指定区块
   * @param id 区块 ID
   */
  function scrollToSection(id: SectionId) {
    observedSections.get(id)?.scrollIntoView({
      behavior: 'smooth',
      block: 'start'
    })
  }

  /**
   * 格式化数字为两位数
   * @param value 数字
   * @returns 两位数字符串
   */
  function formatIndex(value: number) {
    return value.toString().padStart(2, '0')
  }

  /**
   * 恢复默认样例文本
   */
  function restoreSampleText() {
    clearUploadedFileMeta()
    resetWorkspaceFlow()
    form.sourceText = sampleText()
    ElMessage.success('已恢复默认样例文本')
  }

  /**
   * 清空源文本
   */
  function clearSourceText() {
    clearUploadedFileMeta()
    resetWorkspaceFlow()
    form.sourceText = ''
  }

  /**
   * 加载源文件
   * @param file 上传的文件
   */
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

  /**
   * 切换页面类型
   * @param type 页面类型
   */
  function selectPageType(type: PageType) {
    currentPageType.value = type
  }

  /**
   * 切换左侧边栏折叠状态
   */
  function toggleLeftSidebar() {
    if (isCompactLayout.value) {
      return
    }

    leftSidebarCollapsed.value = !leftSidebarCollapsed.value
  }

  /**
   * 创建作品
   */
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

  /**
   * 生成剧本
   */
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

  /**
   * 下载剧本
   */
  function downloadScript() {
    if (!project.value) {
      return
    }

    window.location.href = scriptDownloadUrl(project.value.id)
  }

  /**
   * 切换章节选择状态
   * @param index 章节索引
   */
  function toggleChapterSelection(index: number) {
    const nextSelection = new Set(selectedChapterIndexes.value)

    if (nextSelection.has(index)) {
      nextSelection.delete(index)
    } else {
      nextSelection.add(index)
    }

    syncChapterSelection(Array.from(nextSelection))
  }

  /**
   * 全选章节
   */
  function selectAllChapters() {
    if (!project.value) {
      return
    }

    syncChapterSelection(project.value.chapters.map((chapter) => chapter.index))
  }

  /**
   * 反选章节
   */
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

  /**
   * 清空章节选择
   */
  function clearChapterSelection() {
    syncChapterSelection([])
  }

  /**
   * 暂停生成轮询
   */
  function pauseGenerationPolling() {
    if (!canPauseGeneration.value) {
      return
    }

    stopGenerationPolling()
    generationPollPaused.value = true
    generationMessage.value = '生成仍在后台继续，当前已暂停进度轮询'
    appendLog('轮询已暂停', '已暂停前端进度轮询，后台任务仍在继续。')
  }

  /**
   * 恢复生成轮询
   */
  function resumeGenerationPolling() {
    if (!canResumeGeneration.value || generationJobId.value === null) {
      return
    }

    generationPollPaused.value = false
    generationMessage.value = '正在恢复进度轮询'
    appendLog('轮询已恢复', `重新连接任务 #${generationJobId.value} 的执行进度。`)
    void pollGenerationJob(generationJobId.value)
  }

  /**
   * 开始拖拽调整左侧边栏
   * @param event 指针事件
   */
  function startLeftSidebarResize(event: PointerEvent) {
    if (isCompactLayout.value || leftSidebarCollapsed.value) {
      return
    }

    isResizingLeftSidebar.value = true
    event.preventDefault()
  }

  /**
   * 处理侧边栏拖拽调整
   * @param event 指针事件
   */
  function handleSidebarResize(event: PointerEvent) {
    const rect = workspaceBodyRef.value?.getBoundingClientRect()

    if (!rect || !isResizingLeftSidebar.value) {
      return
    }

    leftSidebarWidth.value = clampLeftSidebarWidth(event.clientX - rect.left)
  }

  /**
   * 停止侧边栏拖拽调整
   */
  function stopSidebarResize() {
    isResizingLeftSidebar.value = false
  }

  /**
   * 处理视口大小变化
   */
  function handleViewportResize() {
    viewportWidth.value = window.innerWidth
    leftSidebarWidth.value = clampLeftSidebarWidth(leftSidebarWidth.value)
  }

  /**
   * 追加生成日志
   * @param stage 阶段名称
   * @param message 日志消息
   * @param level 日志级别
   */
  function appendLog(stage: string, message: string, level: GenerationLogItem['level'] = 'info') {
    generationLogs.value.unshift({
      id: Date.now() + generationLogs.value.length,
      time: formatTime(),
      stage,
      message,
      level
    })
  }

  /**
   * 停止生成轮询
   */
  function stopGenerationPolling() {
    if (generationPollTimer !== null) {
      clearTimeout(generationPollTimer)
      generationPollTimer = null
    }
  }

  /**
   * 调度生成轮询
   * @param jobId 任务 ID
   */
  function scheduleGenerationPoll(jobId: number) {
    stopGenerationPolling()
    generationPollTimer = setTimeout(() => {
      void pollGenerationJob(jobId)
    }, GENERATION_POLL_INTERVAL_MS)
  }

  /**
   * 轮询生成任务状态
   * @param jobId 任务 ID
   */
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

  /**
   * 应用生成任务状态
   * @param job 任务响应
   */
  function applyGenerationJob(job: GenerationJobResponse) {
    generationJobId.value = job.id
    generationStage.value = normalizeGenerationStage(job)
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

  /**
   * 完成生成任务
   * @param job 任务响应
   */
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

  /**
   * 处理生成任务失败
   * @param message 错误消息
   */
  function failGenerationJob(message: string) {
    stopGenerationPolling()
    generationStage.value = 'failed'
    generationPollPaused.value = false
    generationMessage.value = message
    generating.value = false
    appendLog('生成失败', message, 'error')
    ElMessage.error(message)
  }

  /**
   * 重置工作区流程状态
   */
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

  /**
   * 清空上传文件元信息
   */
  function clearUploadedFileMeta() {
    uploadedFileName.value = ''
    uploadedFileEncoding.value = ''
    uploadedFileSize.value = 0
  }

  /**
   * 同步章节选择状态
   * @param indexes 选中的章节索引列表
   */
  function syncChapterSelection(indexes: number[]) {
    if (!project.value) {
      selectedChapterIndexes.value = indexes
      return
    }

    const availableIndexes = project.value.chapters.map((chapter) => chapter.index)
    const selectedSet = new Set(indexes)
    selectedChapterIndexes.value = availableIndexes.filter((index) => selectedSet.has(index))
  }

  /**
   * 构建生成请求载荷
   * @returns 生成请求对象
   */
  function buildGenerationPayload(): GenerationRequest {
    return {
      ...options
    }
  }

  // ---------- 返回值 ----------

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

// ==================== 工具函数 ====================

/**
 * 限制左侧边栏宽度在合理范围内
 * @param value 当前宽度值
 * @returns 限制后的宽度值
 */
function clampLeftSidebarWidth(value: number) {
  const maxWidth = Math.max(280, Math.floor(window.innerWidth * 0.36))
  return Math.min(Math.max(value, 260), maxWidth)
}

/**
 * 格式化当前时间为字符串
 * @returns 格式化后的时间字符串
 */
function formatTime() {
  return new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
}

/**
 * 从错误对象中提取错误消息
 * @param error 错误对象
 * @returns 错误消息字符串
 */
function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : '发生了未知错误'
}

/**
 * 校验源文件是否有效
 * @param file 待校验的文件
 * @throws 当文件格式不支持或为空时抛出
 */
function validateSourceFile(file: File) {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''

  if (!SUPPORTED_TEXT_FILE_EXTENSIONS.includes(extension)) {
    throw new Error('当前仅支持上传 .txt 文件。')
  }

  if (file.size <= 0) {
    throw new Error('上传文件为空，请重新选择有效的 txt 文件。')
  }
}

/**
 * 解码文本缓冲区
 * @param buffer 文件缓冲区
 * @returns 解码后的文本和编码格式
 */
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

/**
 * 规范化源文本（去除 BOM、统一换行符）
 * @param value 原始文本
 * @returns 规范化后的文本
 */
function normalizeSourceText(value: string) {
  return value.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

/**
 * 去除文件名的扩展名
 * @param fileName 文件名
 * @returns 去除扩展名后的文件名
 */
function stripFileExtension(fileName: string) {
  return fileName.replace(/\.[^.]+$/, '')
}

/**
 * 格式化文件大小
 * @param value 文件大小（字节）
 * @returns 格式化后的文件大小字符串
 */
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

// ==================== 阶段标签映射 ====================

/**
 * 生成阶段名称到显示标签的映射
 */
const generationStageLabels: Record<string, string> = {
  created: '任务已创建',
  generation_input: '记录输入快照',
  script_generation: '调度生成管线',
  staged_script_generation: '多阶段生成',
  legacy_script_generation: '旧链路生成',
  chapter_digest: '章节摘要',
  story_bible: '故事设定集', // 只改前端展示文案，内部阶段名保持不变
  scene_plan: '场景规划',
  scene_draft: '分场草稿',
  script_assembly: '最终组装',
  serializing_json: '序列化 JSON',
  exporting_yaml: '导出 YAML',
  saving_snapshot: '保存结果',
  completed: '已完成',
  failed: '失败'
}

/**
 * 规范化生成阶段名称
 * @param job 任务响应
 * @returns 规范化后的阶段名称
 */
function normalizeGenerationStage(job: GenerationJobResponse) {
  if (job.status === 'SUCCEEDED') {
    return 'completed'
  }

  if (job.status === 'FAILED') {
    return 'failed'
  }

  return job.currentStage?.trim() || 'created'
}

/**
 * 获取生成阶段显示标签
 * @param stage 阶段名称
 * @returns 显示标签
 */
function getGenerationStageLabel(stage: string) {
  if (stage.startsWith('chapter_digest:')) {
    return `章节摘要 ${stage.slice('chapter_digest:'.length)}`
  }

  if (stage.startsWith('scene_draft:')) {
    return `分场草稿 ${stage.slice('scene_draft:'.length)}`
  }

  return generationStageLabels[stage] || stage || '未知阶段'
}

/**
 * 描述生成任务状态（中文版本）
 * @param job 任务响应
 * @returns 状态描述
 */
function describeGenerationJobStable(job: GenerationJobResponse) {
  const stage = normalizeGenerationStage(job)
  const progressText = job.progress
      ? ` (${job.progress.completed}/${job.progress.total}，失败 ${job.progress.failed})`
      : ''

  if (job.status === 'PENDING') {
    return '任务已创建，等待后台执行'
  }

  if (job.status === 'RUNNING') {
    return `正在${getGenerationStageLabel(stage)}${progressText}`
  }

  if (job.status === 'SUCCEEDED') {
    return '生成完成，正在加载最新剧本'
  }

  return job.errorMessage || '剧本生成失败'
}