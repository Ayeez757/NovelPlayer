<template>
  <div class="progress-box">
    <el-steps :active="activeStep" direction="vertical" finish-status="success">
      <el-step title="章节识别" :description="project ? '已完成章节拆分' : '等待录入原文'" />
      <el-step title="结构生成" :description="structureDescription" />
      <el-step title="YAML 导出" :description="yamlDescription" />
    </el-steps>

    <div v-if="generating || currentMessage || jobId" class="progress-meta">
      <p v-if="jobId">任务 ID：{{ jobId }}</p>
      <p v-if="currentMessage">{{ currentMessage }}</p>
      <p v-if="currentStageLabel">当前阶段：{{ currentStageLabel }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ProjectResponse, ScriptDocumentResponse } from '../../../api/types'

const props = defineProps<{
  project: ProjectResponse | null
  script: ScriptDocumentResponse | null
  generating: boolean
  currentStage: string
  currentMessage: string
  jobId: number | null
}>()

const structureStages = new Set([
  'preparing_project',
  'calling_model',
  'validating_schema',
  'script_generation'
])

const yamlStages = new Set([
  'serializing_json',
  'exporting_yaml',
  'saving_snapshot',
  'completed'
])

const stageLabels: Record<string, string> = {
  preparing_project: '准备章节与改编参数',
  script_generation: '整体生成',
  calling_model: '调用模型',
  validating_schema: '校验剧本结构',
  serializing_json: '序列化 JSON',
  exporting_yaml: '导出 YAML',
  saving_snapshot: '保存结果',
  completed: '已完成',
  failed: '失败'
}

const activeStep = computed(() => {
  if (props.script) return 3
  if (!props.project) return 0
  if (yamlStages.has(props.currentStage)) return 2
  if (props.generating || structureStages.has(props.currentStage)) return 1
  return 1
})

const currentStageLabel = computed(() => stageLabels[props.currentStage] || '')

const structureDescription = computed(() => {
  if (props.script) return '结构化剧本已生成'
  if (props.generating && structureStages.has(props.currentStage)) {
    return props.currentMessage || '正在生成结构化剧本'
  }
  if (yamlStages.has(props.currentStage)) {
    return '结构化剧本已生成，正在进入导出阶段'
  }
  if (props.project) return '等待开始剧本生成'
  return '请先识别章节'
})

const yamlDescription = computed(() => {
  if (props.script) return 'YAML 草稿已生成'
  if (props.generating && yamlStages.has(props.currentStage)) {
    return props.currentMessage || '正在导出 YAML'
  }
  return '等待结构化剧本生成完成'
})
</script>
