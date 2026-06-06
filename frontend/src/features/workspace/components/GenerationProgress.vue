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
  'created',
  'generation_input',
  'script_generation',
  'staged_script_generation',
  'legacy_script_generation',
  'chapter_digest',
  'story_bible',
  'scene_plan',
  'scene_draft',
  'script_assembly'
])

const yamlStages = new Set([
  'serializing_json',
  'exporting_yaml',
  'saving_snapshot',
  'completed'
])

const stageLabels: Record<string, string> = {
  created: '任务已创建',
  generation_input: '记录输入快照',
  script_generation: '整体生成',
  staged_script_generation: '多阶段生成',
  legacy_script_generation: '旧链路生成',
  chapter_digest: '章节摘要',
  story_bible: '故事圣经',
  scene_plan: '场景规划',
  scene_draft: '分场草稿',
  script_assembly: '最终组装',
  serializing_json: '序列化 JSON',
  exporting_yaml: '导出 YAML',
  saving_snapshot: '保存结果',
  completed: '已完成',
  failed: '失败'
}

const activeStep = computed(() => {
  if (props.script) return 3
  if (!props.project) return 0
  if (isYamlStage(props.currentStage)) return 2
  if (props.generating || isStructureStage(props.currentStage)) return 1
  return 1
})

const currentStageLabel = computed(() => resolveStageLabel(props.currentStage))

const structureDescription = computed(() => {
  if (props.script) return '结构化剧本已生成'
  if (props.generating && isStructureStage(props.currentStage)) {
    return props.currentMessage || '正在生成结构化剧本'
  }
  if (isYamlStage(props.currentStage)) {
    return '结构化剧本已生成，正在进入导出阶段'
  }
  if (props.project) return '等待开始剧本生成'
  return '请先识别章节'
})

const yamlDescription = computed(() => {
  if (props.script) return 'YAML 草稿已生成'
  if (props.generating && isYamlStage(props.currentStage)) {
    return props.currentMessage || '正在导出 YAML'
  }
  return '等待结构化剧本生成完成'
})

function isStructureStage(stage: string) {
  return (
    structureStages.has(stage) ||
    stage.startsWith('chapter_digest:') ||
    stage.startsWith('scene_draft:')
  )
}

function isYamlStage(stage: string) {
  return yamlStages.has(stage)
}

function resolveStageLabel(stage: string) {
  if (stage.startsWith('chapter_digest:')) {
    return `章节摘要 ${stage.slice('chapter_digest:'.length)}`
  }

  if (stage.startsWith('scene_draft:')) {
    return `分场草稿 ${stage.slice('scene_draft:'.length)}`
  }

  return stageLabels[stage] || ''
}
</script>
