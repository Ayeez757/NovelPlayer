<template>
  <el-input
    :model-value="modelValue"
    type="textarea"
    resize="none"
    spellcheck="false"
    :rows="34"
    placeholder="生成后的 YAML 会显示在这里，也可以继续手动编辑。"
    class="yaml-editor"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string
}>()

defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>
