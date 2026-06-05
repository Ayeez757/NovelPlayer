<template>
  <div class="progress-box">
    <el-steps :active="activeStep" direction="vertical" finish-status="success">
      <el-step title="章节识别" />
      <el-step title="结构生成" />
      <el-step title="YAML 导出" />
    </el-steps>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ProjectResponse, ScriptDocumentResponse } from '../api/types'

const props = defineProps<{
  project: ProjectResponse | null
  script: ScriptDocumentResponse | null
  generating: boolean
}>()

// 进度条由当前页面状态推导，避免维护额外的步骤状态。
const activeStep = computed(() => {
  if (props.script) return 3
  if (props.generating) return 2
  if (props.project) return 1
  return 0
})
</script>
