<template>
  <div class="yaml-map">
    <div v-if="parseError" class="yaml-map__state is-error">
      <h4>结构树暂时无法解析</h4>
      <p>{{ parseError }}</p>
    </div>

    <div v-else-if="!rootNode" class="yaml-map__state">
      <h4>等待 YAML 草稿</h4>
      <p>生成结果出现后，这里会自动整理出人物、地点、场景和正文块的树状结构。</p>
    </div>

    <div v-else class="yaml-map__canvas">
      <div class="yaml-map__summary">
        <div>
          <p class="yaml-map__eyebrow">STRUCTURE MAP</p>
          <h4>YAML 脑图</h4>
        </div>
        <span>{{ rootNode.meta ?? '值节点' }}</span>
      </div>

      <div class="yaml-map__viewport">
        <div class="yaml-mindmap" :class="{ 'is-compact': isCompactMindmap }">
          <section class="yaml-mindmap__side is-left">
            <YamlStructureNode
              v-for="node in leftBranches"
              :key="`${node.label}-${node.meta ?? 'branch-left'}`"
              :node="node"
              :depth="1"
              direction="left"
            />
          </section>

          <section class="yaml-mindmap__center">
            <div class="yaml-mindmap__hub">
              <div class="yaml-mindmap__hub-line is-left" />
              <div class="yaml-mindmap__hub-card">
                <p class="yaml-mindmap__hub-eyebrow">核心节点</p>
                <h5>{{ rootNode.label }}</h5>
                <span>{{ rootNode.meta }}</span>
              </div>
              <div class="yaml-mindmap__hub-line is-right" />
            </div>
          </section>

          <section class="yaml-mindmap__side is-right">
            <YamlStructureNode
              v-for="node in rightBranches"
              :key="`${node.label}-${node.meta ?? 'branch-right'}`"
              :node="node"
              :depth="1"
              direction="right"
            />
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { parseDocument } from 'yaml'
import YamlStructureNode from './YamlStructureNode.vue'
import type { YamlTreeNodeModel } from '../model/yamlTree'

const props = defineProps<{
  yamlText: string
}>()

const parsedState = computed(() => {
  const nextText = props.yamlText.trim()

  if (!nextText) {
    return {
      rootNode: null as YamlTreeNodeModel | null,
      parseError: ''
    }
  }

  try {
    const document = parseDocument(nextText)
    if (document.errors.length > 0) {
      throw document.errors[0]
    }

    return {
      rootNode: buildNode('剧本文档', document.toJS()),
      parseError: ''
    }
  } catch (error) {
    return {
      rootNode: null,
      parseError: error instanceof Error ? error.message : '当前 YAML 语法不完整，请先检查缩进或冒号。'
    }
  }
})

const rootNode = computed(() => parsedState.value.rootNode)
const parseError = computed(() => parsedState.value.parseError)
const leftBranches = computed(() => splitBranches().left)
const rightBranches = computed(() => splitBranches().right)
const isCompactMindmap = computed(() => {
  const totalBranches = leftBranches.value.length + rightBranches.value.length
  return totalBranches <= 2
})

function buildNode(label: string, value: unknown): YamlTreeNodeModel {
  const displayLabel = localizeLabel(label)

  if (Array.isArray(value)) {
    return {
      label: displayLabel,
      kind: 'array',
      meta: `${value.length} 项`,
      children: value.map((item, index) => buildNode(`[${index}]`, item))
    }
  }

  if (isPlainObject(value)) {
    const entries = Object.entries(value)
    return {
      label: displayLabel,
      kind: 'object',
      meta: `${entries.length} 个字段`,
      children: entries.map(([key, childValue]) => buildNode(key, childValue))
    }
  }

  return {
    label: displayLabel,
    kind: 'value',
    valueLabel: formatValue(value)
  }
}

function localizeLabel(label: string) {
  if (label.startsWith('[') && label.endsWith(']')) {
    return `第 ${label.slice(1, -1)} 项`
  }

  return yamlLabelMap[label] ?? label
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Object.prototype.toString.call(value) === '[object Object]'
}

function formatValue(value: unknown) {
  if (typeof value === 'string') {
    return value.length > 96 ? `${value.slice(0, 96)}...` : value
  }

  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }

  if (value === null) {
    return 'null'
  }

  if (typeof value === 'undefined') {
    return 'undefined'
  }

  return JSON.stringify(value)
}

const yamlLabelMap: Record<string, string> = {
  schemaVersion: '结构版本',
  metadata: '元信息',
  title: '标题',
  language: '语言',
  sourceChapterCount: '源章节数',
  generatedAt: '生成时间',
  adaptation: '改编信息',
  format: '剧本类型',
  tone: '风格',
  logline: '一句话梗概',
  themes: '主题',
  characters: '人物表',
  id: '编号',
  name: '名称',
  aliases: '别名',
  role: '角色定位',
  goal: '目标',
  traits: '特征',
  voice: '语言风格',
  locations: '地点表',
  type: '类型',
  description: '描述',
  scenes: '场景列表',
  sourceChapters: '来源章节',
  locationId: '地点编号',
  timeOfDay: '时间段',
  dramaticPurpose: '戏剧目的',
  summary: '摘要',
  blocks: '正文块',
  speakerId: '说话人编号',
  text: '内容',
  revisionNotes: '修订备注'
}

function splitBranches() {
  const root = rootNode.value
  if (!root?.children?.length) {
    return {
      left: [] as YamlTreeNodeModel[],
      right: [] as YamlTreeNodeModel[]
    }
  }

  const left: YamlTreeNodeModel[] = []
  const right: YamlTreeNodeModel[] = []
  const accents: YamlTreeNodeModel['accent'][] = ['coral', 'amber', 'cyan', 'teal', 'violet', 'rose']
  let leftWeight = 0
  let rightWeight = 0

  root.children.forEach((node, index) => {
    const decoratedNode = decorateBranch(node, accents[index % accents.length])
    const nodeWeight = countNodeWeight(node)
    if (leftWeight <= rightWeight) {
      left.push(decoratedNode)
      leftWeight += nodeWeight
      return
    }

    right.push(decoratedNode)
    rightWeight += nodeWeight
  })

  return { left, right }
}

function countNodeWeight(node: YamlTreeNodeModel): number {
  if (!node.children?.length) {
    return 1
  }

  return 1 + node.children.reduce((sum, child) => sum + countNodeWeight(child), 0)
}

function decorateBranch(
  node: YamlTreeNodeModel,
  accent: YamlTreeNodeModel['accent']
): YamlTreeNodeModel {
  return {
    ...node,
    accent,
    children: node.children?.map((child) => decorateBranch(child, accent))
  }
}
</script>

<style scoped>
.yaml-map {
  min-height: 240px;
}

.yaml-map__state {
  display: grid;
  gap: 8px;
  min-height: 220px;
  place-content: center;
  padding: 18px;
  border: 1px dashed #d7e3f0;
  border-radius: 16px;
  background:
    radial-gradient(circle at top right, rgba(96, 165, 250, 0.08), transparent 28%),
    linear-gradient(180deg, #ffffff, #f7fbff);
  text-align: center;
}

.yaml-map__state.is-error {
  border-color: #f1c0c0;
  background:
    radial-gradient(circle at top right, rgba(248, 113, 113, 0.08), transparent 26%),
    linear-gradient(180deg, #fffefe, #fff7f7);
}

.yaml-map__state h4,
.yaml-map__summary h4 {
  margin: 0;
  color: #132238;
}

.yaml-map__state p {
  margin: 0;
  color: #5f7084;
  line-height: 1.7;
}

.yaml-map__canvas {
  display: grid;
  gap: 16px;
}

.yaml-map__summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.yaml-map__summary span {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  background: #eef6ff;
  color: #285ea8;
  font-size: 12px;
  font-weight: 700;
}

.yaml-map__eyebrow {
  margin: 0 0 6px;
  color: #6f8092;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.18em;
}

.yaml-map__viewport {
  overflow: auto;
  padding: 10px 4px 10px 2px;
}

.yaml-mindmap {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(240px, 300px) minmax(280px, 1fr);
  gap: 28px;
  min-width: 1180px;
  align-items: start;
  padding: 12px 12px 20px;
}

.yaml-mindmap.is-compact {
  grid-template-columns: minmax(280px, 1fr) minmax(220px, 280px) minmax(280px, 1fr);
}

.yaml-mindmap__side {
  display: grid;
  gap: 24px;
}

.yaml-mindmap__side.is-left {
  justify-items: end;
}

.yaml-mindmap__side.is-right {
  justify-items: start;
}

.yaml-mindmap__center {
  display: grid;
  align-content: start;
  padding-top: 52px;
}

.yaml-mindmap__hub {
  display: grid;
  grid-template-columns: minmax(18px, 1fr) auto minmax(18px, 1fr);
  align-items: center;
  gap: 10px;
}

.yaml-mindmap__hub-line {
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(90deg, #d7e5f4, #a7d3ef);
}

.yaml-mindmap__hub-line.is-right {
  background: linear-gradient(90deg, #a7d3ef, #d7e5f4);
}

.yaml-mindmap__hub-card {
  display: grid;
  justify-items: center;
  gap: 8px;
  min-height: 112px;
  padding: 20px 22px;
  border: 1px solid #9ccde8;
  border-radius: 24px;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.16), transparent 42%),
    linear-gradient(180deg, rgba(245, 252, 255, 1), rgba(255, 255, 255, 0.98));
  box-shadow:
    0 24px 44px rgba(14, 116, 144, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  text-align: center;
}

.yaml-mindmap__hub-eyebrow {
  margin: 0;
  color: #5b748a;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.16em;
}

.yaml-mindmap__hub-card h5 {
  margin: 0;
  color: #132238;
  font-size: 22px;
  line-height: 1.15;
}

.yaml-mindmap__hub-card span {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  background: #eaf6ff;
  color: #24578f;
  font-size: 12px;
  font-weight: 700;
}

@media (max-width: 720px) {
  .yaml-map__summary {
    flex-direction: column;
  }

  .yaml-map__state {
    min-height: 200px;
    padding: 16px;
  }
}

@media (max-width: 1100px) {
  .yaml-mindmap {
    min-width: 0;
    grid-template-columns: 1fr;
    gap: 22px;
  }

  .yaml-mindmap__side.is-left,
  .yaml-mindmap__side.is-right {
    justify-items: stretch;
  }

  .yaml-mindmap__center {
    order: -1;
    padding-top: 0;
  }

  .yaml-mindmap__hub {
    grid-template-columns: 1fr;
  }

  .yaml-mindmap__hub-line {
    display: none;
  }
}
</style>
