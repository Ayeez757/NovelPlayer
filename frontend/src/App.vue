<template>
  <main class="workspace">
    <header class="topbar">
      <div>
        <h1>NovelPlayer</h1>
        <p>AI 小说转剧本 YAML 工具</p>
      </div>
      <el-button
        :disabled="!project || !script"
        type="primary"
        @click="downloadScript"
      >
        下载 YAML
      </el-button>
    </header>

    <section class="layout">
      <section class="panel source-panel">
        <div class="panel-heading">
          <h2>小说输入</h2>
          <el-tag v-if="project" type="success">{{ project.chapters.length }} 章</el-tag>
        </div>

        <el-form label-position="top">
          <el-form-item label="作品标题">
            <el-input v-model="form.title" placeholder="输入作品标题" />
          </el-form-item>
          <el-form-item label="小说正文">
            <el-input
              v-model="form.sourceText"
              type="textarea"
              resize="none"
              :rows="18"
              placeholder="粘贴至少 3 个章节，支持 第一章 / 第1章 / Chapter 1 等标题"
            />
          </el-form-item>
          <el-button type="primary" :loading="creating" @click="create">
            识别章节
          </el-button>
        </el-form>

        <ChapterList v-if="project" :chapters="project.chapters" />
      </section>

      <section class="panel control-panel">
        <div class="panel-heading">
          <h2>改编设置</h2>
        </div>

        <el-form label-position="top">
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
          <el-form-item label="补充改编要求">
            <el-input
              v-model="options.additionalInstructions"
              type="textarea"
              resize="none"
              :rows="6"
              :maxlength="4000"
              show-word-limit
              placeholder="例如：强化主角主动性，减少旁白，保留结尾反转"
            />
          </el-form-item>
          <el-button
            type="success"
            :disabled="!project"
            :loading="generating"
            @click="generate"
          >
            生成剧本
          </el-button>
        </el-form>

        <GenerationProgress :project="project" :script="script" :generating="generating" />
      </section>

      <section class="panel yaml-panel">
        <div class="panel-heading">
          <h2>YAML 初稿</h2>
          <el-tag v-if="script" type="success">{{ script.validationStatus }}</el-tag>
        </div>
        <ScriptYamlEditor v-model="yamlDraft" />
      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChapterList from './components/ChapterList.vue'
import GenerationProgress from './components/GenerationProgress.vue'
import ScriptYamlEditor from './components/ScriptYamlEditor.vue'
import { createProject, generateScript, scriptDownloadUrl } from './api/projectApi'
import type { GenerationRequest, ProjectResponse, ScriptDocumentResponse } from './api/types'

const form = reactive({
  title: '未命名作品',
  sourceText: sampleText()
})

// 这些字段对应后端生成请求，并映射到模型提示词中的改编约束；补充要求只影响本次生成。
const options = reactive<GenerationRequest>({
  format: 'web_drama',
  tone: 'suspense',
  dialogueDensity: 60,
  narrationRetention: 30,
  additionalInstructions: ''
})

const creating = ref(false)
const generating = ref(false)
const project = ref<ProjectResponse | null>(null)
const script = ref<ScriptDocumentResponse | null>(null)
const yamlDraft = ref('')

// 第一步：提交原文并让后端拆章，成功后页面展示章节结果。
async function create() {
  creating.value = true
  try {
    project.value = await createProject({ title: form.title, sourceText: form.sourceText })
    script.value = null
    yamlDraft.value = ''
    ElMessage.success('章节识别完成')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    creating.value = false
  }
}

// 第二步：基于已创建项目触发剧本生成，并把 YAML 放入右侧编辑区。
async function generate() {
  if (!project.value) return
  generating.value = true
  try {
    script.value = await generateScript(project.value.id, options)
    yamlDraft.value = script.value.yamlContent
    ElMessage.success('剧本已生成')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    generating.value = false
  }
}

// 下载接口返回的是附件响应，因此直接修改浏览器地址即可。
function downloadScript() {
  if (!project.value) return
  window.location.href = scriptDownloadUrl(project.value.id)
}

// 内置样例让评审或开发者打开页面后能立即体验完整流程。
function sampleText() {
  return `第一章 雨夜的信
雨从傍晚一直下到深夜。林安推开旧书店的门，听见风铃轻轻一响。柜台后没有人，只有一本摊开的旧书压着一封没有署名的信。
她认出信封上的字，那是父亲失踪前常用的钢笔笔迹。

第二章 缺页
信里只有一句话：不要相信第七页。林安翻开旧书，却发现第七页被人整齐撕掉。书页夹缝里残留着一点烧焦的纸灰。
门外传来脚步声，她把信塞进口袋，抬头看见一个陌生男人站在雨里。

第三章 交易
男人说自己知道林安父亲的下落，但要她拿旧书来换。林安没有回答，只问他为什么害怕第七页。
男人沉默片刻，告诉她：那一页写着所有人的名字，包括她自己的。`
}
</script>
