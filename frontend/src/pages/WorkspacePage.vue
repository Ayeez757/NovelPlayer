<template>
  <main class="workspace-shell">
    <header class="workspace-topbar">
      <section class="brand-banner">
        <h1>NovelPlayer</h1>
        <p>从小说原文到剧本草稿的改编工作台。</p>
      </section>

      <p class="page-announcement">当前模式：{{ currentPageTypeLabel }}</p>

      <div class="topbar-actions">
        <RouterLink to="/" class="topbar-link">返回首页</RouterLink>
      </div>
    </header>

    <section ref="workspaceBodyRef" class="workspace-body" :style="workspaceBodyStyle">
      <aside class="workspace-sidebar" :class="{ 'is-collapsed': leftSidebarCollapsed }">
        <button
          v-if="!isCompactLayout"
          type="button"
          class="sidebar-edge-toggle sidebar-edge-toggle--left"
          :aria-label="leftSidebarCollapsed ? '展开左侧导航' : '收起左侧导航'"
          @click="toggleLeftSidebar"
        >
          <el-icon>
            <DArrowRight v-if="leftSidebarCollapsed" />
            <DArrowLeft v-else />
          </el-icon>
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
                <h2>入口选择</h2>
              </div>

              <div class="sidebar-actions">
                <button
                  type="button"
                  class="sidebar-action"
                  :class="{ 'is-active': currentPageType === 'upload-convert' }"
                  @click="selectPageType('upload-convert')"
                >
                  <el-icon><UploadFilled /></el-icon>
                  <span>上传文件转剧本</span>
                </button>

                <button
                  type="button"
                  class="sidebar-action"
                  :class="{ 'is-active': currentPageType === 'instant-write' }"
                  @click="selectPageType('instant-write')"
                >
                  <el-icon><EditPen /></el-icon>
                  <span>即时输入转剧本</span>
                </button>

                <div class="sidebar-action sidebar-action--static">
                  <div class="sidebar-action__content">
                    <el-icon><View /></el-icon>
                    <span>查看方式</span>
                  </div>

                  <el-select v-model="viewMode" class="sidebar-option-select">
                    <el-option
                      v-for="option in viewModeOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                    />
                  </el-select>
                </div>
              </div>
            </section>

            <section class="sidebar-panel">
              <div class="sidebar-panel__heading">
                <h2>当前目录</h2>
                <span>{{ currentSectionMeta?.label ?? '文本输入' }}</span>
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
                <h2>预留扩展</h2>
              </div>

              <ul class="sidebar-tag-list">
                <li>项目中心</li>
                <li>角色卡片</li>
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
          :class="[
            'content-card',
            'content-card--source',
            { 'content-card--upload': isUploadMode }
          ]"
          data-section-id="text-input"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">01</p>
              <h2>{{ sourceSectionTitle }}</h2>
            </div>
            <span class="content-card__meta">{{ currentPageTypeLabel }}</span>
          </div>

          <p class="content-card__description">
            {{ sourceSectionDescription }}
          </p>

          <div v-if="isUploadMode" class="source-mode-panel">
            <label class="upload-dropzone" :class="{ 'is-ready': hasUploadedFile }">
              <input
                class="upload-dropzone__input"
                type="file"
                :accept="sourceFileAccept"
                @change="handleSourceFileChange"
              />

              <div class="upload-dropzone__icon-wrap">
                <el-icon class="upload-dropzone__icon"><UploadFilled /></el-icon>
              </div>

              <div class="upload-dropzone__body">
                <h3>
                  {{
                    hasUploadedFile
                      ? 'TXT 文件已导入，可继续检查并微调内容'
                      : '拖拽 .txt 文件到这里，或点击选择文件'
                  }}
                </h3>
                <p>
                  建议上传至少 3 章纯文本内容。系统会读取 TXT 文件，并继续沿用当前的章节识别与 YAML 生成流程。
                </p>
              </div>

              <div class="upload-dropzone__chips">
                <span>仅支持 .txt</span>
                <span>自动识别 UTF-8 / GB18030</span>
                <span>继续沿用当前工作流</span>
              </div>
            </label>

            <article v-if="hasUploadedFile" class="upload-file-card">
              <div class="upload-file-card__meta">
                <p class="upload-file-card__eyebrow">已导入文件</p>
                <h3>{{ uploadedFileName }}</h3>
                <p>
                  {{ uploadedFileSizeLabel }} · {{ uploadedFileEncodingLabel }} ·
                  {{ sourceCharacterCount.toLocaleString('zh-CN') }} 字
                </p>
              </div>

              <div class="upload-file-card__actions">
                <button type="button" class="text-link" @click="clearSourceText">清空文件</button>
              </div>
            </article>

            <div v-else class="empty-state source-mode-empty">
              文件上传完成后，这里会显示文件信息、编码识别结果和正文长度。
            </div>
          </div>

          <el-form label-position="top" class="editor-form">
            <el-form-item label="作品标题">
              <el-input v-model="form.title" :placeholder="titlePlaceholder" />
            </el-form-item>

            <el-form-item :label="sourceFieldLabel">
              <el-input
                v-model="form.sourceText"
                type="textarea"
                resize="vertical"
                :rows="isUploadMode ? 16 : 20"
                :placeholder="sourceFieldPlaceholder"
                :class="['novel-textarea', { 'novel-textarea--upload': isUploadMode }]"
              />
            </el-form-item>
          </el-form>

          <div class="source-toolbar">
            <span>当前正文长度：{{ sourceCharacterCount.toLocaleString('zh-CN') }} 字</span>
            <div class="source-toolbar__actions">
              <button
                v-if="!isUploadMode"
                type="button"
                class="text-link"
                @click="restoreSampleText"
              >
                恢复样例
              </button>
              <button type="button" class="text-link" @click="clearSourceText">
                {{ clearSourceActionLabel }}
              </button>
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
            <span class="content-card__meta">
              {{ project ? selectedChapterSummary : '尚未识别' }}
            </span>
          </div>

          <p class="content-card__description">
            点击后会根据章节标题规则先切分原文。识别完成后，这里会展示当前参与生成的章节范围。
          </p>

          <div class="action-row">
            <el-button type="primary" :loading="creating" @click="create">识别章节</el-button>
            <p>{{ project ? `项目状态：${project.status}` : '等待建立项目上下文' }}</p>
          </div>

          <article class="result-panel result-panel--chapter-inline">
            <div class="result-panel__heading">
              <h3>章节列表</h3>
              <span>{{ project ? selectedChapterSummary : '暂无结果' }}</span>
            </div>

            <template v-if="project">
              <div class="chapter-selection-toolbar">
                <div class="chapter-selection-summary">
                  <strong>{{ selectedChapterCount }}</strong>
                  <span>/ {{ project.chapters.length }} 章已选</span>
                </div>

                <div v-if="supportsChapterSelectionSubmission" class="chapter-selection-actions">
                  <button type="button" class="text-link" @click="selectAllChapters">全选</button>
                  <button type="button" class="text-link" @click="invertChapterSelection">反选</button>
                  <button type="button" class="text-link" @click="clearChapterSelection">清空</button>
                </div>
              </div>

              <ChapterList
                :chapters="project.chapters"
                :selected-indexes="selectedChapterIndexes"
                :selectable="supportsChapterSelectionSubmission"
                @toggle="toggleChapterSelection"
              />

              <p class="chapter-selection-note">
                {{ chapterSelectionHint }}
              </p>
            </template>

            <div v-else class="empty-state">章节识别完成后，这里会显示每章摘要与参与生成范围。</div>
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
            <span class="content-card__meta">{{ submitStatusLabel }}</span>
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

              <el-form-item label="对白密度">
                <el-slider v-model="options.dialogueDensity" :min="0" :max="100" />
              </el-form-item>

              <el-form-item label="旁白保留">
                <el-slider v-model="options.narrationRetention" :min="0" :max="100" />
              </el-form-item>
            </el-form>

            <div class="submit-summary">
              <h3>生成准备</h3>
              <ul>
                <li>当前项目：{{ project?.title ?? form.title }}</li>
                <li>参与生成章节：{{ project ? selectedChapterSummary : '等待识别章节' }}</li>
                <li>剧本类型：{{ formatLabel }}</li>
                <li>风格倾向：{{ toneLabel }}</li>
              </ul>

              <div class="submit-summary__actions">
                <div
                  class="submit-summary__action-group"
                  :class="{ 'is-single': !canPauseGeneration && !canResumeGeneration }"
                >
                  <el-button
                    type="success"
                    size="large"
                    class="generate-script-button"
                    :disabled="!project || !hasSelectedChapters || generating"
                    :loading="generateButtonLoading"
                    @click="generate"
                  >
                    生成剧本
                  </el-button>

                  <el-button
                    v-if="canPauseGeneration"
                    type="success"
                    size="large"
                    class="generate-script-button"
                    @click="pauseGenerationPolling"
                  >
                    暂停生成
                  </el-button>

                  <el-button
                    v-else-if="canResumeGeneration"
                    type="success"
                    size="large"
                    class="generate-script-button"
                    @click="resumeGenerationPolling"
                  >
                    继续生成
                  </el-button>
                </div>
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
            <span class="content-card__meta">
              {{ script ? script.validationStatus : '等待结果' }}
            </span>
          </div>

          <article class="result-panel result-panel--yaml">
            <div class="result-panel__heading">
              <h3>YAML 内容</h3>
              <span>{{ script ? '已生成' : '待生成' }}</span>
            </div>

            <ScriptYamlEditor v-model="yamlDraft" />

            <el-button
              type="success"
              size="large"
              class="generate-script-button yaml-download-button"
              :disabled="!project || !script"
              @click="downloadScript"
            >
              下载 YAML
            </el-button>
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
              <div v-else class="empty-state">生成开始后，这里会按阶段展示实时日志。</div>
            </article>
          </div>
        </section>

        <section
          :ref="setSectionRef('structure-map')"
          class="content-card content-card--yaml-map"
          data-section-id="structure-map"
        >
          <div class="content-card__heading">
            <div>
              <p class="content-card__eyebrow">06</p>
              <h2>结构树</h2>
            </div>
            <span class="content-card__meta">
              {{ yamlDraft.trim() ? '随 YAML 实时同步' : '等待 YAML 初稿' }}
            </span>
          </div>

          <p class="content-card__description">
            把当前 YAML 草稿整理成树状结构，方便快速查看 metadata、人物、地点、场景和正文块层级。
          </p>

          <article class="result-panel result-panel--yaml-map">
            <div class="result-panel__heading">
              <h3>剧本结构导航</h3>
              <span>{{ script ? '已绑定当前生成结果' : '也支持手动编辑后的 YAML' }}</span>
            </div>

            <YamlStructureTree :yaml-text="yamlDraft" />
          </article>
        </section>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DArrowLeft,
  DArrowRight,
  EditPen,
  UploadFilled,
  View
} from '@element-plus/icons-vue'
import ChapterList from '../features/workspace/components/ChapterList.vue'
import GenerationProgress from '../features/workspace/components/GenerationProgress.vue'
import ScriptYamlEditor from '../features/workspace/components/ScriptYamlEditor.vue'
import YamlStructureTree from '../features/workspace/components/YamlStructureTree.vue'
import { useWorkspacePage } from '../features/workspace/composables/useWorkspacePage'

const {
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
  hasUploadedFile,
  hasSelectedChapters,
  invertChapterSelection,
  isCompactLayout,
  isUploadMode,
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
  sourceFileAccept,
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
} = useWorkspacePage()

const formatLabels: Record<string, string> = {
  web_drama: '短剧',
  screenplay: '影视剧',
  stage_play: '舞台剧'
}

const toneLabels: Record<string, string> = {
  suspense: '悬疑',
  realistic: '写实',
  comedy: '轻喜',
  period: '古风'
}

const formatLabel = computed(() => formatLabels[options.format] ?? options.format)
const toneLabel = computed(() => toneLabels[options.tone] ?? options.tone)
const sourceSectionTitle = computed(() => (isUploadMode.value ? '上传文件' : '文本输入'))
const sourceSectionDescription = computed(() => {
  return isUploadMode.value
    ? '导入 TXT 小说原文，并沿当前工作台流程继续完成章节识别、剧本生成和 YAML 编辑。'
    : '这里保留默认样例文本，也支持你直接粘贴自己的小说原文。识别章节后，就能继续沿当前工作台流程生成剧本草稿。'
})
const titlePlaceholder = computed(() => {
  return isUploadMode.value ? '导入文件后可自动带入，也支持手动修改' : '输入作品标题'
})
const sourceFieldLabel = computed(() => (isUploadMode.value ? '导入预览' : '小说正文'))
const sourceFieldPlaceholder = computed(() => {
  return isUploadMode.value
    ? '上传后的 txt 内容会显示在这里，你也可以继续补充或微调。'
    : '至少粘贴 3 章内容，支持 第1章 / 第一章 / Chapter 1 等标题格式'
})
const clearSourceActionLabel = computed(() => (isUploadMode.value ? '清空文件内容' : '清空正文'))

async function handleSourceFileChange(event: Event) {
  const input = event.target as HTMLInputElement | null
  const file = input?.files?.[0]

  if (!file) {
    return
  }

  try {
    await loadSourceFile(file)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '文件读取失败')
  } finally {
    if (input) {
      input.value = ''
    }
  }
}
</script>
