<template>
  <main class="workspace-shell">
    <header class="workspace-topbar">
      <section class="brand-banner">
        <h1>NovelPlayer</h1>
        <p>Play Your Novel.</p>
      </section>

      <p class="page-announcement">当前页面：{{ currentPageTypeLabel }}</p>
    </header>

    <section ref="workspaceBodyRef" class="workspace-body" :style="workspaceBodyStyle">
      <aside class="workspace-sidebar" :class="{ 'is-collapsed': leftSidebarCollapsed }">
        <button
          v-if="!isCompactLayout"
          type="button"
          class="sidebar-edge-toggle sidebar-edge-toggle--left"
          :aria-label="leftSidebarCollapsed ? '展开左侧导航栏' : '隐藏左侧导航栏'"
          @click="toggleLeftSidebar"
        >
          <el-icon><DArrowRight v-if="leftSidebarCollapsed" /><DArrowLeft v-else /></el-icon>
        </button>

        <button
          v-if="!isCompactLayout && !leftSidebarCollapsed"
          type="button"
          class="workspace-sidebar__resize-handle"
          aria-label="拖动调整左侧栏宽度"
          @pointerdown="startLeftSidebarResize"
        />

        <el-scrollbar v-if="!leftSidebarCollapsed" class="workspace-sidebar__scroll">
          <div class="workspace-sidebar__scroll-content">
            <section class="sidebar-panel">
              <div class="sidebar-panel__heading">
                <h2>选项</h2>
              </div>

              <div class="sidebar-actions">


                <button
                  type="button"
                  class="sidebar-action"
                  :class="{ 'is-active': currentPageType === 'upload-convert' }"
                  @click="selectPageType('upload-convert')"
                >
                  <el-icon><UploadFilled /></el-icon>
                  <span>上传文件-小说转剧本</span>
                </button>

                <button
                  type="button"
                  class="sidebar-action"
                  :class="{ 'is-active': currentPageType === 'instant-write' }"
                  @click="selectPageType('instant-write')"
                >
                  <el-icon><EditPen /></el-icon>
                  <span>立即写作-小说转剧本</span>
                </button>

                <button type="button" class="sidebar-action">
                <el-icon><View /></el-icon>
                <span>视图</span>
                <el-select
                    v-model="viewMode"
                    class="sidebar-option-select"
                    @click.stop
                >
                  <el-option
                      v-for="option in viewModeOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                  />
                </el-select>
              </button>
              </div>
            </section>

            <section class="sidebar-panel">
              <div class="sidebar-panel__heading">
                <h2>当前目录概览</h2>
                <span>{{ currentSectionMeta?.label ?? '文字输入' }}</span>
              </div>

              <nav class="directory-nav">
                <button
                  v-for="(item, index) in contentSections"
                  :key="item.id"
                  type="button"
                  class="directory-nav__item"
                  :class="{ 'is-active': currentSection === item.id }"
                  @click="scrollToSection(item.id)"
                >
                  <span class="directory-nav__index">{{ formatIndex(index + 1) }}</span>
                  <span class="directory-nav__label">{{ item.label }}</span>
                  <small class="directory-nav__hint">{{ item.hint }}</small>
                </button>
              </nav>
            </section>

            <section class="sidebar-panel">
              <div class="sidebar-panel__heading">
                <h2>其他</h2>
              </div>
              <ul class="sidebar-tag-list">
                <li>项目库</li>
                <li>角色卡</li>
                <li>模板市场</li>
                <li>多人协作</li>
              </ul>
            </section>
          </div>
        </el-scrollbar>
      </aside>

      <div
        class="workspace-center"
        :class="{ 'is-horizontal-compare': viewMode === 'horizontal-compare' }"
      >
        <section
          :ref="setSectionRef('text-input')"
          class="content-card content-card--source"
          data-section-id="text-input"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">01</p>
              <h2>文字输入</h2>
            </div>
          </div>

          <p class="content-card__description">
            这里保留默认样例文本，也支持直接贴入你的小说原文。正文输入框会拉宽铺满中间区域，并允许你直接拖拽高度。
          </p>

          <el-form label-position="top" class="editor-form">
            <el-form-item label="作品标题">
              <el-input v-model="form.title" placeholder="输入作品标题" />
            </el-form-item>

            <el-form-item label="小说正文">
              <el-input
                v-model="form.sourceText"
                type="textarea"
                resize="vertical"
                :rows="20"
                placeholder="粘贴至少 3 个章节，支持 第一章 / 第1章 / Chapter 1 等标题"
                class="novel-textarea"
              />
            </el-form-item>
          </el-form>

          <div class="source-toolbar">
            <span>当前正文长度：{{ sourceCharacterCount.toLocaleString('zh-CN') }} 字</span>
            <div class="source-toolbar__actions">
              <button type="button" class="text-link" @click="restoreSampleText">恢复样例</button>
              <button type="button" class="text-link" @click="clearSourceText">清空正文</button>
            </div>
          </div>
        </section>

        <section
          :ref="setSectionRef('identify-chapters')"
          class="content-card"
          data-section-id="identify-chapters"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">02</p>
              <h2>识别章节</h2>
            </div>
            <span class="content-card__meta">{{ project ? `${project.chapters.length} 章` : '尚未识别' }}</span>
          </div>

          <p class="content-card__description">
            点击后会根据章节标题规则先切分原文。识别完成后，章节结果会直接显示在这个模块下面。
          </p>

          <div class="action-row">
            <el-button type="primary" :loading="creating" @click="create">识别章节</el-button>
            <p>{{ project ? `项目状态：${project.status}` : '等待建立项目上下文' }}</p>
          </div>

          <article class="result-panel result-panel--chapter-inline">
            <div class="result-panel__heading">
              <h3>章节列表</h3>
              <span>{{ project ? `${project.chapters.length} 章` : '无结果' }}</span>
            </div>

            <ChapterList v-if="project" :chapters="project.chapters" />

          </article>
        </section>

        <section
          :ref="setSectionRef('confirm-submit')"
          class="content-card"
          data-section-id="confirm-submit"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">03</p>
              <h2>确认提交</h2>
            </div>
            <span class="content-card__meta">{{ generating ? '生成中' : '等待生成' }}</span>
          </div>

          <div class="settings-grid">
            <el-form label-position="top" class="editor-form">
              <el-form-item label="剧本类型">
                <el-select v-model="options.format">
                  <el-option label="短剧" value="web_drama" />
                  <el-option label="影视剧" value="screenplay" />
                  <el-option label="舞台剧" value="stage_play" />
                </el-select>
              </el-form-item>

              <el-form-item label="风格">
                <el-select v-model="options.tone">
                  <el-option label="悬疑" value="suspense" />
                  <el-option label="写实" value="realistic" />
                  <el-option label="轻喜" value="comedy" />
                  <el-option label="古风" value="period" />
                </el-select>
              </el-form-item>

              <el-form-item label="对话密度">
                <el-slider v-model="options.dialogueDensity" :min="0" :max="100" />
              </el-form-item>

              <el-form-item label="旁白保留">
                <el-slider v-model="options.narrationRetention" :min="0" :max="100" />
              </el-form-item>
            </el-form>

            <div class="submit-summary">

              <div class="submit-summary__actions">
                <el-button
                  type="success"
                  :disabled="!project"
                  :loading="generating"
                  @click="generate"
                >
                  生成剧本
                </el-button>

                <el-button
                  :disabled="!project || !script"
                  plain
                  @click="downloadScript"
                >
                  下载 YAML
                </el-button>
              </div>
            </div>
          </div>
        </section>

        <section
          :ref="setSectionRef('identify-results')"
          class="content-card"
          data-section-id="identify-results"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">04</p>
              <h2>YAML 初稿</h2>
            </div>
            <span class="content-card__meta">{{ script ? script.validationStatus : '等待结果' }}</span>
          </div>

          <article class="result-panel result-panel--yaml">
            <div class="result-panel__heading">
              <h3>YAML 内容</h3>
              <span>{{ script ? '已生成' : '待生成' }}</span>
            </div>

            <ScriptYamlEditor v-model="yamlDraft" />
          </article>
        </section>

        <section
          :ref="setSectionRef('stream-log')"
          class="content-card"
          data-section-id="stream-log"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">05</p>
              <h2>生成日志</h2>
            </div>
            <span class="content-card__meta">{{ generationLogs.length }} 条</span>
          </div>

          <div class="log-grid">
            <article class="log-panel">
              <h3>任务进度</h3>
              <GenerationProgress
                :project="project"
                :script="script"
                :generating="generating"
                :current-stage="generationStage"
                :current-message="generationMessage"
                :job-id="generationJobId"
              />
            </article>

            <article class="log-panel">
              <div class="result-panel__heading">
                <h3>实时消息</h3>
                <span>{{ generationJobId ? `任务 #${generationJobId}` : '暂无任务' }}</span>
              </div>

              <ul v-if="generationLogs.length" class="log-feed">
                <li
                  v-for="item in generationLogs"
                  :key="item.id"
                  class="log-feed__item"
                  :class="`is-${item.level}`"
                >
                  <div class="log-feed__meta">
                    <span>{{ item.time }}</span>
                    <strong>{{ item.stage }}</strong>
                  </div>
                  <p>{{ item.message }}</p>
                </li>
              </ul>
              <div v-else class="empty-state">
                生成开始后，这里会显示流式阶段日志。
              </div>
            </article>
          </div>
        </section>
      </div>

      <aside
        v-if="!isCompactLayout || aiPanelOpen"
        class="ai-panel"
        :class="{ 'is-collapsed': !aiPanelOpen && !isCompactLayout }"
        :style="aiPanelStyle"
      >
        <button
          v-if="!isCompactLayout"
          type="button"
          class="sidebar-edge-toggle sidebar-edge-toggle--right"
          :aria-label="aiPanelOpen ? '隐藏 AI 侧边栏' : '展开 AI 侧边栏'"
          @click="toggleAiPanel"
        >
          <el-icon><DArrowRight v-if="aiPanelOpen" /><DArrowLeft v-else /></el-icon>
        </button>

        <template v-if="aiPanelOpen">
          <button
            v-if="!isCompactLayout"
            type="button"
            class="ai-panel__resize-handle"
            aria-label="拖动调整 AI 面板宽度"
            @pointerdown="startAiResize"
          />

          <div class="ai-panel__content">
            <section class="sidebar-panel ai-section ai-section--header">
              <header class="ai-panel__header">
                <div>
                  <p>AI 侧边栏</p>
                  <h2>对话助手</h2>
                </div>

                <button type="button" class="text-link" @click="toggleAiPanel">隐藏</button>
              </header>
            </section>

            <section class="sidebar-panel ai-section ai-section--messages">
              <div class="sidebar-panel__heading">
                <h2>对话记录</h2>
                <span>{{ aiMessages.length }} 条</span>
              </div>

              <!-- 设置 overflow -->
              <div class="ai-messages-container">
                <div class="ai-message-list">
                  <!-- 消息列表 -->
                </div>
              </div>
            </section>

            <section class="sidebar-panel ai-section ai-section--composer">
              <div class="sidebar-panel__heading">
                <h2>发送消息</h2>
                <span>前端演示助手</span>
              </div>

              <footer class="ai-panel__composer">
                <el-input
                  v-model="aiDraft"
                  type="textarea"
                  resize="none"
                  :rows="4"
                  placeholder="例如：帮我梳理这一步该做什么，或者解释当前目录高亮的作用。"
                  @keydown.enter.exact.prevent="sendAiMessage"
                />

                <div class="ai-panel__composer-actions">
                  <span>Enter 发送，Shift+Enter 换行</span>
                  <el-button type="primary" :disabled="!aiDraft.trim()" @click="sendAiMessage">
                    发送
                  </el-button>
                </div>
              </footer>
            </section>
          </div>
        </template>
      </aside>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import ChapterList from './components/ChapterList.vue'
import GenerationProgress from './components/GenerationProgress.vue'
import ScriptYamlEditor from './components/ScriptYamlEditor.vue'
import { createProject, generateScriptStream, scriptDownloadUrl } from './api/projectApi'
import { DArrowLeft, DArrowRight, EditPen, Grid, UploadFilled, View } from '@element-plus/icons-vue'
import type {
  GenerationRequest,
  GenerationStreamEvent,
  ProjectResponse,
  ScriptDocumentResponse
} from './api/types'

type SectionId =
  | 'text-input'
  | 'identify-chapters'
  | 'confirm-submit'
  | 'identify-results'
  | 'stream-log'

interface SectionMeta {
  id: SectionId
  label: string
  hint: string
}

interface GenerationLogItem {
  id: number
  time: string
  stage: string
  message: string
  level: 'info' | 'success' | 'error'
}

interface AiMessage {
  id: number
  role: 'assistant' | 'user'
  content: string
  time: string
}

type PageType = 'upload-convert' | 'instant-write'
type ViewMode = 'vertical-flow' | 'horizontal-compare'

const contentSections: SectionMeta[] = [
  { id: 'text-input', label: '文字输入', hint: '录入小说原文' },
  { id: 'identify-chapters', label: '识别章节', hint: '执行章节切分' },
  { id: 'confirm-submit', label: '确认提交', hint: '确认改编设置' },
  { id: 'identify-results', label: '识别结果', hint: '查看章节与 YAML' },
  { id: 'stream-log', label: '生成日志', hint: '跟踪生成过程' }
]

const pageTypeLabels: Record<PageType, string> = {
  'upload-convert': '上传文件 - 小说转剧本',
  'instant-write': '立即写作 - 小说转剧本'
}

const viewModeOptions: Array<{ label: string; value: ViewMode }> = [
  { label: '纵向流水式', value: 'vertical-flow' },
  { label: '横向对比式', value: 'horizontal-compare' }
]

const form = reactive({
  title: '未命名作品',
  sourceText: sampleText()
})

const options = reactive<GenerationRequest>({
  format: 'web_drama',
  tone: 'suspense',
  dialogueDensity: 60,
  narrationRetention: 30
})

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
const observedSections: Partial<Record<SectionId, HTMLElement | null>> = {}
let sectionObserver: IntersectionObserver | null = null

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
    content: '这里是纯前端 AI 侧边栏示意。你可以问我当前步骤要做什么，或者让我帮你梳理改编思路。',
    time: formatTime()
  }
])

const currentSectionMeta = computed(() =>
  contentSections.find((item) => item.id === currentSection.value) ?? contentSections[0]
)
const currentPageTypeLabel = computed(() => pageTypeLabels[currentPageType.value])

const sourceCharacterCount = computed(() => form.sourceText.replace(/\s+/g, '').length)
const isCompactLayout = computed(() => viewportWidth.value < 1100)
const workspaceBodyStyle = computed(() => {
  if (isCompactLayout.value) {
    return undefined
  }

  const leftWidth = leftSidebarCollapsed.value ? 34 : leftSidebarWidth.value
  const rightWidth = aiPanelOpen.value ? aiPanelWidth.value : 0

  return {
    gridTemplateColumns: `${leftWidth}px minmax(0, 1fr) ${rightWidth}px`
  }
})
const aiPanelStyle = computed(() => {
  if (isCompactLayout.value) {
    return { width: 'min(92vw, 420px)' }
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
    observedSections[id] = element instanceof HTMLElement ? element : null
  }
}

function setupSectionObserver() {
  sectionObserver?.disconnect()

  sectionObserver = new IntersectionObserver(
    (entries) => {
      const visibleEntries = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)

      if (!visibleEntries.length) return

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
    const element = observedSections[item.id]

    if (element) {
      sectionObserver?.observe(element)
    }
  })
}

function scrollToSection(id: SectionId) {
  observedSections[id]?.scrollIntoView({
    behavior: 'smooth',
    block: 'start'
  })
}

function formatIndex(value: number) {
  return value.toString().padStart(2, '0')
}

function formatTime() {
  return new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
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

function restoreSampleText() {
  form.sourceText = sampleText()
  ElMessage.success('已恢复默认样例文本')
}

function clearSourceText() {
  form.sourceText = ''
}

function showComingSoon(label: string) {
  ElMessage.info(`${label} 入口先预留在这里，后续可以继续扩展。`)
}

function selectPageType(type: PageType) {
  currentPageType.value = type

  if (type === 'upload-convert') {
    showComingSoon(pageTypeLabels[type])
  }
}

function toggleLeftSidebar() {
  if (isCompactLayout.value) return

  leftSidebarCollapsed.value = !leftSidebarCollapsed.value
}

async function create() {
  creating.value = true

  try {
    project.value = await createProject({ title: form.title, sourceText: form.sourceText })
    script.value = null
    yamlDraft.value = ''
    generationStage.value = ''
    generationMessage.value = ''
    generationJobId.value = null
    generationLogs.value = []
    appendLog('章节识别', '章节识别完成，已建立项目上下文。', 'success')
    ElMessage.success('章节识别完成')
  } catch (error) {
    appendLog('章节识别', (error as Error).message, 'error')
    ElMessage.error((error as Error).message)
  } finally {
    creating.value = false
  }
}

async function generate() {
  if (!project.value) return

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
    appendLog('生成完成', 'YAML 草稿已写入页面。', 'success')
    ElMessage.success('剧本已生成')
  } catch (error) {
    generationStage.value = 'failed'
    generationMessage.value = (error as Error).message
    appendLog('生成失败', (error as Error).message, 'error')
    ElMessage.error((error as Error).message)
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
    const level = event.type === 'error' ? 'error' : event.type === 'completed' ? 'success' : 'info'
    appendLog(stageLabel, event.message, level)
  }
}

function downloadScript() {
  if (!project.value) return
  window.location.href = scriptDownloadUrl(project.value.id)
}

function toggleAiPanel() {
  aiPanelOpen.value = !aiPanelOpen.value

  if (aiPanelOpen.value && !isCompactLayout.value) {
    aiPanelWidth.value = clampAiPanelWidth(Math.round(viewportWidth.value * 0.32))
  }
}

function clampAiPanelWidth(value: number) {
  const maxWidth = Math.max(400, Math.floor(viewportWidth.value * 0.45))
  return Math.min(Math.max(value, 360), maxWidth)
}

function clampLeftSidebarWidth(value: number) {
  const maxWidth = Math.max(280, Math.floor(viewportWidth.value * 0.36))
  return Math.min(Math.max(value, 260), maxWidth)
}

function startLeftSidebarResize(event: PointerEvent) {
  if (isCompactLayout.value || leftSidebarCollapsed.value) return

  isResizingLeftSidebar.value = true
  event.preventDefault()
}

function startAiResize(event: PointerEvent) {
  if (isCompactLayout.value) return

  isResizingAi.value = true
  event.preventDefault()
}

function handleSidebarResize(event: PointerEvent) {
  const rect = workspaceBodyRef.value?.getBoundingClientRect()

  if (!rect) return

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

function sendAiMessage() {
  const content = aiDraft.value.trim()

  if (!content) return

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
  const normalized = prompt.toLowerCase()

  if (normalized.includes('章节')) {
    return '建议先完成“识别章节”，这样中间工作区的章节列表和后续生成上下文都会被建立起来。'
  }

  if (normalized.includes('yaml')) {
    return 'YAML 初稿会在“识别结果”区域右侧出现，生成后你可以直接在页面里继续改。'
  }

  if (normalized.includes('当前') || normalized.includes('在哪')) {
    return `你当前所在的是“${currentSectionMeta.value?.label ?? '文字输入'}”模块，左侧目录概览会随滚动自动高亮。`
  }

  if (normalized.includes('ai')) {
    return '右侧这个 AI 面板现在是纯前端示意版，负责展示布局与交互方式，不依赖后端接口。'
  }

  return '这块 AI 侧边栏目前先服务于前端交互示意。你可以继续问我当前步骤、页面结构，或者让我帮你梳理改编流程。'
}

function sampleText() {
  return `第一章 雨夜的信
雨从傍晚一直下到深夜。林安推开旧书店的门，听见风铃轻轻一响。柜台后没有人，只有一本摊开的旧书压着一封没有署名的信。她认出信封上的字，那是父亲失踪前常用的钢笔笔迹。
第二章 缺页
信里只有一句话：不要相信第七页。林安翻开旧书，却发现第七页被人整齐撕掉。书页夹缝里残留着一点烧焦的纸灰。门外传来脚步声，她把信塞进口袋，抬头看见一个陌生男人站在雨里。
第三章 交易
男人说自己知道林安父亲的下落，但要她拿旧书来换。林安没有回答，只问他为什么害怕第七页。男人沉默片刻，告诉她：那一页写着所有人的名字，包括她自己的。`
}
</script>
