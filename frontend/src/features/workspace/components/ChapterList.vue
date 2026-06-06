<template>
  <div class="chapter-list">
    <el-scrollbar max-height="220px">
      <div v-for="chapter in chapters" :key="chapter.index" class="chapter-item">
        <span>{{ chapter.index }}. {{ chapter.title }}</span>
        <small>{{ chapter.contentLength }} 字</small>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import type { ChapterResponse } from '../../../api/types'

defineProps<{
  chapters: ChapterResponse[]
}>()
</script>
