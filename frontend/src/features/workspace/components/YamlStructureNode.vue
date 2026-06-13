<template>
  <li
    class="yaml-mind-node"
    :class="[
      `is-${direction}`,
      node.accent ? `accent-${node.accent}` : '',
      { 'is-root': depth === 0, 'has-children': hasChildren }
    ]"
  >
    <button
      v-if="hasChildren"
      type="button"
      class="yaml-mind-card yaml-mind-card--branch"
      :class="[`is-${node.kind}`, { 'is-expanded': expanded }]"
      @click="expanded = !expanded"
    >
      <span class="yaml-mind-card__main">
        <span class="yaml-mind-card__title">{{ node.label }}</span>
        <span class="yaml-mind-card__badge" :class="`is-${node.kind}`">
          {{ node.meta }}
        </span>
      </span>
      <span class="yaml-mind-card__toggle">{{ expanded ? '收起' : '展开' }}</span>
    </button>

    <div v-else class="yaml-mind-card yaml-mind-card--leaf">
      <span class="yaml-mind-card__title">{{ node.label }}</span>
      <span class="yaml-mind-card__separator">:</span>
      <span class="yaml-mind-card__value">{{ node.valueLabel }}</span>
    </div>

    <div v-if="hasChildren && expanded" class="yaml-mind-children">
      <div class="yaml-mind-children__stem" />
      <ul class="yaml-mind-children__list">
        <YamlStructureNode
          v-for="child in node.children"
          :key="`${child.label}-${child.meta ?? child.valueLabel ?? 'node'}`"
          :node="child"
          :depth="depth + 1"
          :direction="direction"
          :initial-expanded="initialExpanded"
          :global-expand-token="globalExpandToken"
          :global-expand-state="globalExpandState"
        />
      </ul>
    </div>
  </li>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { YamlTreeNodeModel } from '../model/yamlTree'

defineOptions({
  name: 'YamlStructureNode'
})

const props = defineProps<{
  node: YamlTreeNodeModel
  depth: number
  direction: 'left' | 'right'
  initialExpanded?: boolean
  globalExpandToken?: number
  globalExpandState?: boolean
}>()

const hasChildren = computed(() => (props.node.children?.length ?? 0) > 0)

/*
 * 旧行为是 `const expanded = ref(props.depth < 2)`，每个节点只按默认层级展开，
 * 没法响应根节点的“一键展开 / 一键收起”。
 */
const expanded = ref(props.initialExpanded ?? props.depth < 2)

watch(
  () => props.globalExpandToken,
  () => {
    if (!hasChildren.value) {
      return
    }

    // 响应根节点的全局展开控制，同时保留单个节点的手动切换能力。
    expanded.value = props.globalExpandState ?? expanded.value
  }
)
</script>

<style scoped>
.yaml-mind-node {
  position: relative;
  list-style: none;
}

.yaml-mind-node.has-children {
  display: grid;
  gap: 14px;
}

.yaml-mind-node.is-left {
  justify-items: end;
}

.yaml-mind-node.is-right {
  justify-items: start;
}

.yaml-mind-node.has-children.depth-1 {
  padding-top: 6px;
}

.yaml-mind-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 52px;
  min-width: min(240px, 100%);
  max-width: 320px;
  padding: 12px 16px;
  border: 1px solid #d8e4f2;
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(246, 250, 255, 0.98)),
    radial-gradient(circle at top right, rgba(125, 211, 252, 0.12), transparent 42%);
  box-shadow:
    0 18px 32px rgba(15, 23, 42, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.72);
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease,
    border-color 0.22s ease;
}

.yaml-mind-card--branch {
  width: 100%;
  border: 0;
  outline: 0;
  text-align: left;
  cursor: pointer;
}

.yaml-mind-card--branch:hover,
.yaml-mind-card--branch:focus-visible {
  transform: translateY(-2px);
  box-shadow:
    0 22px 38px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.78);
}

.is-root > .yaml-mind-card {
  min-width: 280px;
  max-width: 360px;
  padding: 16px 18px;
  border-color: #a6d6f7;
  background:
    linear-gradient(180deg, rgba(242, 251, 255, 1), rgba(255, 255, 255, 0.98)),
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.16), transparent 42%);
  box-shadow:
    0 26px 44px rgba(14, 116, 144, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

.yaml-mind-node.depth-1 {
  padding-top: 8px;
}

.yaml-mind-card.is-object {
  border-color: #cfe1ff;
}

.yaml-mind-card.is-array {
  border-color: #ccefd8;
}

.yaml-mind-card__main {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.yaml-mind-card__title {
  font-size: 15px;
  font-weight: 800;
  color: #132238;
  word-break: break-word;
}

.yaml-mind-card__badge {
  display: flex;
  align-items: center;
  width: fit-content;
  min-height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.yaml-mind-card__badge.is-object {
  background: #e8f1ff;
  color: #1d4ed8;
}

.yaml-mind-card__badge.is-array {
  background: #ecfdf3;
  color: #15803d;
}

.yaml-mind-card__toggle {
  flex: 0 0 auto;
  color: #6b8198;
  font-size: 12px;
  font-weight: 700;
}

.yaml-mind-card--leaf {
  align-items: flex-start;
  justify-content: flex-start;
  gap: 6px;
  min-width: 210px;
  max-width: 280px;
}

.yaml-mind-card__separator {
  color: #8a9aae;
  font-weight: 600;
}

.yaml-mind-card__value {
  color: #4a5c72;
  line-height: 1.65;
  word-break: break-word;
}

.yaml-mind-children {
  display: grid;
  gap: 12px;
  width: 100%;
}

.yaml-mind-node.is-left .yaml-mind-children {
  justify-items: end;
}

.yaml-mind-node.is-right .yaml-mind-children {
  justify-items: start;
}

.yaml-mind-children__stem {
  width: 1px;
  height: 16px;
  border-radius: 999px;
  background: #d7e3f0;
}

.yaml-mind-children__list {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  flex-wrap: wrap;
  gap: 18px 20px;
  width: 100%;
  margin: 0;
  padding: 18px 0 0;
  list-style: none;
}

.yaml-mind-children__list::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  width: min(100%, calc(100% - 64px));
  height: 0;
  border-top: 1px solid #d7e3f0;
  transform: translateX(-50%);
}

.yaml-mind-node.is-left .yaml-mind-children__list {
  justify-content: flex-end;
}

.yaml-mind-children__list > :deep(.yaml-mind-node) {
  position: relative;
}

.yaml-mind-children__list > :deep(.yaml-mind-node)::before {
  content: '';
  position: absolute;
  top: -18px;
  left: 50%;
  width: 1px;
  height: 18px;
  background: #d7e3f0;
  transform: translateX(-50%);
}

.yaml-mind-node.accent-coral .yaml-mind-children__stem,
.yaml-mind-node.accent-coral .yaml-mind-children__list::before,
.yaml-mind-node.accent-coral :deep(.yaml-mind-node)::before {
  background: #f0b8af;
}

.yaml-mind-node.accent-coral > .yaml-mind-card {
  border-color: #f2c5bf;
  background:
    radial-gradient(circle at top right, rgba(248, 113, 113, 0.14), transparent 42%),
    linear-gradient(180deg, rgba(255, 252, 252, 0.98), rgba(255, 245, 244, 0.94));
}

.yaml-mind-node.accent-coral .yaml-mind-children__list::before {
  border-top-color: #f0b8af;
}

.yaml-mind-node.accent-amber .yaml-mind-children__stem,
.yaml-mind-node.accent-amber .yaml-mind-children__list::before,
.yaml-mind-node.accent-amber :deep(.yaml-mind-node)::before {
  background: #e8cc88;
}

.yaml-mind-node.accent-amber > .yaml-mind-card {
  border-color: #ecd59c;
  background:
    radial-gradient(circle at top right, rgba(250, 204, 21, 0.14), transparent 42%),
    linear-gradient(180deg, rgba(255, 254, 248, 0.98), rgba(255, 249, 234, 0.94));
}

.yaml-mind-node.accent-amber .yaml-mind-children__list::before {
  border-top-color: #e8cc88;
}

.yaml-mind-node.accent-cyan .yaml-mind-children__stem,
.yaml-mind-node.accent-cyan .yaml-mind-children__list::before,
.yaml-mind-node.accent-cyan :deep(.yaml-mind-node)::before {
  background: #9ac8f1;
}

.yaml-mind-node.accent-cyan > .yaml-mind-card {
  border-color: #b8d8f6;
  background:
    radial-gradient(circle at top right, rgba(96, 165, 250, 0.14), transparent 42%),
    linear-gradient(180deg, rgba(250, 253, 255, 0.98), rgba(242, 249, 255, 0.94));
}

.yaml-mind-node.accent-cyan .yaml-mind-children__list::before {
  border-top-color: #9ac8f1;
}

.yaml-mind-node.accent-teal .yaml-mind-children__stem,
.yaml-mind-node.accent-teal .yaml-mind-children__list::before,
.yaml-mind-node.accent-teal :deep(.yaml-mind-node)::before {
  background: #9fdccf;
}

.yaml-mind-node.accent-teal > .yaml-mind-card {
  border-color: #ade1d7;
  background:
    radial-gradient(circle at top right, rgba(45, 212, 191, 0.14), transparent 42%),
    linear-gradient(180deg, rgba(249, 255, 253, 0.98), rgba(239, 253, 250, 0.94));
}

.yaml-mind-node.accent-teal .yaml-mind-children__list::before {
  border-top-color: #9fdccf;
}

.yaml-mind-node.accent-violet .yaml-mind-children__stem,
.yaml-mind-node.accent-violet .yaml-mind-children__list::before,
.yaml-mind-node.accent-violet :deep(.yaml-mind-node)::before {
  background: #c8b7ef;
}

.yaml-mind-node.accent-violet > .yaml-mind-card {
  border-color: #d8cafb;
  background:
    radial-gradient(circle at top right, rgba(167, 139, 250, 0.14), transparent 42%),
    linear-gradient(180deg, rgba(252, 250, 255, 0.98), rgba(246, 241, 255, 0.94));
}

.yaml-mind-node.accent-violet .yaml-mind-children__list::before {
  border-top-color: #c8b7ef;
}

.yaml-mind-node.accent-rose .yaml-mind-children__stem,
.yaml-mind-node.accent-rose .yaml-mind-children__list::before,
.yaml-mind-node.accent-rose :deep(.yaml-mind-node)::before {
  background: #ebbed1;
}

.yaml-mind-node.accent-rose > .yaml-mind-card {
  border-color: #f3c5da;
  background:
    radial-gradient(circle at top right, rgba(244, 114, 182, 0.14), transparent 42%),
    linear-gradient(180deg, rgba(255, 251, 253, 0.98), rgba(255, 242, 248, 0.94));
}

.yaml-mind-node.accent-rose .yaml-mind-children__list::before {
  border-top-color: #ebbed1;
}

@media (max-width: 720px) {
  .yaml-mind-card,
  .is-root > .yaml-mind-card,
  .yaml-mind-card--leaf {
    min-width: 0;
    max-width: none;
    width: 100%;
    padding: 12px 14px;
    border-radius: 16px;
  }

  .yaml-mind-card__main {
    gap: 6px;
  }

  .yaml-mind-children__list {
    display: grid;
    justify-items: stretch;
    gap: 14px;
    padding-top: 14px;
  }

  .yaml-mind-children__list::before {
    left: 24px;
    width: calc(100% - 48px);
    transform: none;
  }

  .yaml-mind-children__list > :deep(.yaml-mind-node)::before {
    left: 24px;
    transform: none;
  }
}
</style>
