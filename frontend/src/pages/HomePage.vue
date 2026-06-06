<template>
  <main class="home-page">
    <div v-if="showOpeningGrid" class="home-opening-grid" aria-hidden="true">
      <span
        v-for="tile in openingTiles"
        :key="tile.id"
        class="home-opening-grid__tile"
        :style="{
          '--tile-delay': tile.delay,
          '--tile-shift-x': tile.shiftX,
          '--tile-shift-y': tile.shiftY
        }"
        @animationend="handleOpeningTileEnd"
      />
    </div>

    <header class="home-nav home-scene home-scene--nav">
      <RouterLink to="/" class="home-nav__brand">NovelPlayer</RouterLink>

      <nav class="home-nav__links" aria-label="首页导航">
        <RouterLink to="/workspace">工作台</RouterLink>
        <a href="#product">产品能力</a>
        <a href="#contact">联系我们</a>
      </nav>
    </header>

    <section class="home-stage home-scene home-scene--hero">
      <div class="home-stage__body">
        <p class="home-stage__eyebrow">Novel adaptation workspace</p>
        <h1 class="home-wordmark">novelplayer</h1>
        <p class="home-slogan">把小说原文，推进成可以继续打磨的剧本工作流</p>
        <p class="home-summary">
          从章节识别、改编生成到 YAML 草稿编辑，在一个工作台里完成小说转剧本的完整流程。
        </p>

        <div class="home-cta-row">
          <RouterLink to="/workspace" class="home-link-button home-link-button--primary">
            开始改编
          </RouterLink>
          <a href="#product" class="home-link-button home-link-button--secondary">查看能力</a>
        </div>
      </div>
    </section>

    <section class="home-entry-grid home-scene home-scene--cards" id="product">
      <component
        :is="resolveFeatureCardComponent(card)"
        v-for="card in featureCards"
        :key="card.title"
        :to="resolveFeatureCardTo(card)"
        :class="[
          'home-entry-card',
          card.tone === 'primary' ? 'home-entry-card--primary' : 'home-entry-card--muted'
        ]"
      >
        <span class="home-entry-card__eyebrow">{{ card.eyebrow }}</span>
        <strong>{{ card.title }}</strong>
        <span>{{ card.description }}</span>
      </component>
    </section>

    <footer class="home-footer home-scene home-scene--footer" id="contact">
      <section v-for="column in footerColumns" :key="column.title">
        <h2>{{ column.title }}</h2>
        <a v-for="item in column.items" :key="item.label" :href="item.href">{{ item.label }}</a>
      </section>
    </footer>
  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

type OpeningTile = {
  id: string
  delay: string
  shiftX: string
  shiftY: string
}

type FeatureCard = {
  eyebrow: string
  title: string
  description: string
  tone: 'primary' | 'muted'
  to?: string
}

type FooterColumn = {
  title: string
  items: Array<{
    label: string
    href: string
  }>
}

let hasPlayedOpening = false

const showOpeningGrid = ref(false)
const completedOpeningTiles = ref(0)

const openingTiles: OpeningTile[] = [
  { id: 'top-left', delay: '0.68s', shiftX: '-24px', shiftY: '-18px' },
  { id: 'top-center', delay: '0.34s', shiftX: '0px', shiftY: '-22px' },
  { id: 'top-right', delay: '0.82s', shiftX: '22px', shiftY: '-16px' },
  { id: 'middle-left', delay: '0.46s', shiftX: '-28px', shiftY: '0px' },
  { id: 'center', delay: '0s', shiftX: '0px', shiftY: '0px' },
  { id: 'middle-right', delay: '0.54s', shiftX: '28px', shiftY: '0px' },
  { id: 'bottom-left', delay: '1.04s', shiftX: '-18px', shiftY: '22px' },
  { id: 'bottom-center', delay: '0.62s', shiftX: '0px', shiftY: '26px' },
  { id: 'bottom-right', delay: '1.18s', shiftX: '20px', shiftY: '18px' }
]

const featureCards: FeatureCard[] = [
  {
    eyebrow: '01 / Entry',
    title: '开始改编',
    description: '从首页直接进入工作台，页面切换时保留轻一点的浅入浅出。',
    tone: 'primary',
    to: '/workspace'
  },
  {
    eyebrow: '02 / Structure',
    title: '章节识别',
    description: '先把原文拆清楚，再决定哪些章节进入后续的改编生成流程。',
    tone: 'muted'
  },
  {
    eyebrow: '03 / Draft',
    title: '剧本草稿',
    description: '把输出收束到可继续编辑的 YAML 和工作流面板里，方便后续细修。',
    tone: 'muted'
  }
]

const footerColumns: FooterColumn[] = [
  {
    title: '产品',
    items: [
      { label: '小说转剧本', href: '#product' },
      { label: '工作台入口', href: '#product' },
      { label: 'YAML 草稿编辑', href: '#product' }
    ]
  },
  {
    title: '流程',
    items: [
      { label: '输入原文', href: '#product' },
      { label: '识别章节', href: '#product' },
      { label: '推进成稿', href: '#product' }
    ]
  },
  {
    title: '项目',
    items: [
      { label: 'NovelPlayer', href: '#contact' },
      { label: '前端体验升级', href: '#contact' },
      { label: '工作台继续扩展', href: '#contact' }
    ]
  }
]

onMounted(() => {
  if (hasPlayedOpening || prefersReducedMotion()) {
    return
  }

  hasPlayedOpening = true
  showOpeningGrid.value = true
  completedOpeningTiles.value = 0
})

onBeforeUnmount(() => {
  completedOpeningTiles.value = 0
})

function resolveFeatureCardComponent(card: FeatureCard) {
  return card.to ? RouterLink : 'article'
}

function resolveFeatureCardTo(card: FeatureCard) {
  return card.to
}

function handleOpeningTileEnd() {
  if (!showOpeningGrid.value) {
    return
  }

  completedOpeningTiles.value += 1

  if (completedOpeningTiles.value >= openingTiles.length) {
    showOpeningGrid.value = false
  }
}

function prefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
</script>
