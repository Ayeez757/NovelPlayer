<template>
  <el-input
    :model-value="modelValue"
    type="textarea"
    resize="none"
    spellcheck="false"
    :rows="34"
    placeholder="生成后会在这里显示 YAML，可直接编辑。"
    class="yaml-editor"
    @input="$emit('update:modelValue', $event)"
  />
</template>

<script setup lang="ts">
// 通过双向绑定暴露 YAML 文本，后续可替换为专业代码编辑器。
defineProps<{
  modelValue: string
}>()

defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>
