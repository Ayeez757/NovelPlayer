<template>
  <div class="chapter-list">
    <el-scrollbar max-height="260px">
      <button
        v-for="chapter in chapters"
        :key="chapter.index"
        type="button"
        class="chapter-item"
        :class="{
          'is-selectable': selectable,
          'is-selected': isSelected(chapter.index)
        }"
        :aria-pressed="selectable ? isSelected(chapter.index) : undefined"
        @click="handleToggle(chapter.index)"
      >
        <div class="chapter-item__main">
          <span v-if="selectable" class="chapter-item__state">
            {{ isSelected(chapter.index) ? '已选' : '可选' }}
          </span>
          <span class="chapter-item__title">{{ chapter.index }}. {{ chapter.title }}</span>
        </div>
        <small>{{ chapter.contentLength }} 字</small>
      </button>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import type { ChapterResponse } from '../../../api/types'

const props = withDefaults(
  defineProps<{
    chapters: ChapterResponse[]
    selectedIndexes?: number[]
    selectable?: boolean
  }>(),
  {
    selectedIndexes: () => [],
    selectable: false
  }
)

const emit = defineEmits<{
  toggle: [index: number]
}>()

function isSelected(index: number) {
  return props.selectedIndexes.includes(index)
}

function handleToggle(index: number) {
  if (!props.selectable) {
    return
  }

  emit('toggle', index)
}
</script>
