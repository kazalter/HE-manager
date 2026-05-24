<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import axios from 'axios'
import {
  BarChart3,
  Clock,
  Film,
  Image as ImageIcon,
  Layers,
  Music,
  RefreshCw,
  Sparkles,
  Star,
} from 'lucide-vue-next'
import { API_BASE_URL, thumbnailUrl } from '../config'
import type {
  StatAttentionItem,
  StatsActivity,
  StatsActivityBucket,
  StatsAttention,
  StatsDistribution,
  StatsHighlights,
  StatsOverview,
} from '../types'

const loading = ref(true)
const errorMessage = ref('')
const refreshSpinning = ref(false)

const overview = ref<StatsOverview | null>(null)
const distribution = ref<StatsDistribution | null>(null)
const activity = ref<StatsActivity | null>(null)
const attention = ref<StatsAttention | null>(null)
const highlights = ref<StatsHighlights | null>(null)

const fetchAll = async () => {
  loading.value = overview.value === null
  errorMessage.value = ''
  try {
    const [o, d, a, at, hi] = await Promise.all([
      axios.get<StatsOverview>(`${API_BASE_URL}/stats/overview`),
      axios.get<StatsDistribution>(`${API_BASE_URL}/stats/distribution`),
      axios.get<StatsActivity>(`${API_BASE_URL}/stats/activity`, { params: { days: 365 } }),
      axios.get<StatsAttention>(`${API_BASE_URL}/stats/attention`),
      axios.get<StatsHighlights>(`${API_BASE_URL}/stats/highlights`, { params: { limit: 10 } }),
    ])
    overview.value = o.data
    distribution.value = d.data
    activity.value = a.data
    attention.value = at.data
    highlights.value = hi.data
  } catch (err: any) {
    errorMessage.value = err?.response?.data?.detail || '加载统计数据失败，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
}

const refresh = async () => {
  if (refreshSpinning.value) return
  refreshSpinning.value = true
  await fetchAll()
  setTimeout(() => (refreshSpinning.value = false), 400)
}

onMounted(fetchAll)

// ---- formatters ----
const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes < 1024 ** 4) return `${(bytes / (1024 ** 3)).toFixed(2)} GB`
  return `${(bytes / (1024 ** 4)).toFixed(2)} TB`
}

const formatDurationHours = (seconds: number) => {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h} 小时 ${m} 分`
  return `${m} 分`
}

const typeMeta: Record<string, { label: string; icon: any }> = {
  video: { label: '视频', icon: Film },
  manga: { label: '漫画', icon: Layers },
  image: { label: '杂图', icon: ImageIcon },
  audio: { label: '音频', icon: Music },
}

const sourceLabel = (key: string) => {
  if (key === 'local') return '本地'
  if (key === 'x') return 'X (推特)'
  if (key === 'wnacg') return 'WNACG'
  if (key === 'asmr') return 'ASMR'
  return key
}

// Only sources that HomeView actually filters on get a click-through link.
// Others (e.g. 'asmr') are shown but not linkable to avoid dead routes.
const sourceLinkable = (key: string) => key === 'x' || key === 'wnacg' || key === 'local'

const statusLabel: Record<string, string> = {
  unviewed: '未看',
  viewing: '在看',
  viewed: '已看',
}

// ---- overview derived ----
const overviewCards = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    { label: '媒体总数', value: o.total.toLocaleString(), icon: BarChart3, accent: true, to: '/' },
    { label: '收藏', value: o.favorites.toLocaleString(), icon: Star, to: '/?favorite=true' },
    { label: '已评分', value: o.rated.toLocaleString(), icon: Sparkles },
    { label: '平均评分', value: o.average_rating ? o.average_rating.toFixed(2) : '—', icon: Star },
    { label: '库总体积', value: formatSize(o.total_size_bytes), icon: Layers },
    { label: '视频总时长', value: formatDurationHours(o.total_duration_seconds), icon: Clock, to: '/type/video' },
  ]
})

const typeBars = computed(() => {
  const o = overview.value
  if (!o) return []
  const max = Math.max(1, ...Object.values(o.by_type))
  return Object.entries(o.by_type).map(([key, count]) => ({
    key,
    label: typeMeta[key]?.label ?? key,
    icon: typeMeta[key]?.icon ?? ImageIcon,
    count,
    pct: Math.round((count / max) * 100),
    to: `/type/${key}`,
  }))
})

// "Where is my space going" — sorted by size desc, with each type's GB and share.
const typeSizeBars = computed(() => {
  const o = overview.value
  if (!o) return []
  const totalSize = Math.max(1, o.total_size_bytes)
  return Object.entries(o.by_type_size)
    .filter(([, bytes]) => bytes > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([key, bytes]) => ({
      key,
      label: typeMeta[key]?.label ?? key,
      icon: typeMeta[key]?.icon ?? ImageIcon,
      bytes,
      size: formatSize(bytes),
      pct: Math.round((bytes / totalSize) * 100),
      to: `/type/${key}`,
    }))
})

const statusBars = computed(() => {
  const o = overview.value
  if (!o) return []
  const total = Math.max(1, o.total)
  return Object.entries(o.view_status).map(([key, count]) => ({
    key,
    label: statusLabel[key] ?? key,
    count,
    pct: Math.round((count / total) * 100),
  }))
})

const ratingBars = computed(() => {
  const d = distribution.value
  if (!d) return []
  const entries = Object.entries(d.rating_histogram).sort((a, b) => Number(b[0]) - Number(a[0]))
  const max = Math.max(1, ...entries.map(([, c]) => c))
  return entries.map(([stars, count]) => ({
    stars: Number(stars),
    count,
    pct: Math.round((count / max) * 100),
  }))
})

const sourceBars = computed(() => {
  const d = distribution.value
  if (!d) return []
  const entries = Object.entries(d.by_source)
  const max = Math.max(1, ...entries.map(([, c]) => c))
  return entries.map(([key, count]) => ({
    key,
    label: sourceLabel(key),
    count,
    pct: Math.round((count / max) * 100),
    to: sourceLinkable(key) ? `/?source=${key}` : null,
  }))
})

// ---- growth: monthly bars + cumulative line ----
// We always draw on a 100×100 viewBox and let SVG stretch. Bars use the `added`
// field, the line + area use `cumulative`. If there's only one month the line
// would be a single point — fall back to bar-only.
const growthChart = computed(() => {
  const g = distribution.value?.growth ?? []
  if (g.length === 0) return null
  const w = 100
  const h = 100
  const maxAdded = Math.max(1, ...g.map(p => p.added))
  const maxCum = Math.max(1, ...g.map(p => p.cumulative))

  // Slot widths: each month occupies a column; bar is ~70% of that, centered.
  const slot = w / g.length
  const barW = slot * 0.7
  const bars = g.map((p, i) => ({
    x: i * slot + (slot - barW) / 2,
    y: h - (p.added / maxAdded) * h,
    w: barW,
    height: (p.added / maxAdded) * h,
    month: p.month,
    added: p.added,
    cumulative: p.cumulative,
  }))

  // Cumulative line is anchored to the *center* of each bar's slot.
  const pts = g.map((p, i) => {
    const x = i * slot + slot / 2
    const y = h - (p.cumulative / maxCum) * h
    return `${x.toFixed(2)},${y.toFixed(2)}`
  })

  return {
    bars,
    line: pts.length > 1 ? pts.join(' ') : null,
    area: pts.length > 1 ? `${(slot / 2).toFixed(2)},${h} ${pts.join(' ')} ${(w - slot / 2).toFixed(2)},${h}` : null,
    first: g[0],
    last: g[g.length - 1],
    months: g.length,
    maxCum,
  }
})

// ---- activity heatmap (with per-type filter) ----
const heatmapType = ref<'all' | string>('all')

const heatmapTypeTabs = computed(() => {
  const a = activity.value
  const tabs: { key: string; label: string; total: number }[] = [
    { key: 'all', label: '全部', total: a?.total ?? 0 },
  ]
  if (!a) return tabs
  const order = ['video', 'manga', 'image', 'audio']
  for (const key of order) {
    const buckets = a.by_type?.[key]
    if (!buckets || buckets.length === 0) continue
    const total = buckets.reduce((s, b) => s + b.count, 0)
    tabs.push({ key, label: typeMeta[key]?.label ?? key, total })
  }
  return tabs
})

const heatmap = computed(() => {
  const a = activity.value
  if (!a) return null

  const source: StatsActivityBucket[] =
    heatmapType.value === 'all' ? a.buckets : (a.by_type?.[heatmapType.value] ?? [])
  const counts = new Map<string, number>()
  for (const b of source) counts.set(b.date, b.count)

  const to = new Date(a.to_date + 'T00:00:00')
  const from = new Date(to)
  from.setDate(from.getDate() - (a.days - 1))
  // Pad the start back to the previous Sunday so weeks line up in columns.
  const gridStart = new Date(from)
  gridStart.setDate(gridStart.getDate() - gridStart.getDay())

  const subsetMax = source.length ? Math.max(...source.map(b => b.count)) : 0
  const subsetTotal = source.reduce((s, b) => s + b.count, 0)
  const max = Math.max(1, subsetMax)
  const level = (c: number) => {
    if (c <= 0) return 0
    const r = c / max
    if (r <= 0.25) return 1
    if (r <= 0.5) return 2
    if (r <= 0.75) return 3
    return 4
  }

  const weeks: { date: string; count: number; level: number; inRange: boolean }[][] = []
  const cursor = new Date(gridStart)
  while (cursor <= to) {
    const week: { date: string; count: number; level: number; inRange: boolean }[] = []
    for (let d = 0; d < 7; d++) {
      const iso = cursor.toISOString().slice(0, 10)
      const c = counts.get(iso) ?? 0
      const inRange = cursor >= from && cursor <= to
      week.push({ date: iso, count: c, level: inRange ? level(c) : 0, inRange })
      cursor.setDate(cursor.getDate() + 1)
    }
    weeks.push(week)
  }
  return { weeks, total: subsetTotal, max: subsetMax }
})

const levelClass = (lvl: number, inRange: boolean) => {
  if (!inRange) return 'bg-transparent'
  return [
    'bg-white/[0.04]',
    'bg-accent/25',
    'bg-accent/45',
    'bg-accent/70',
    'bg-accent',
  ][lvl]
}

const attentionEmpty = computed(
  () => attention.value && attention.value.dusty.length === 0 && attention.value.unrated.length === 0,
)

// ---- highlights helpers ----
const creatorLink = (c: { kind: string; screen_name: string | null }) =>
  c.kind === 'x' && c.screen_name ? `/creators/${c.screen_name}` : null

const formatDurationShort = (seconds: number) => {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  return `${m}:${s.toString().padStart(2, '0')}`
}

const lastOpenedText = (item: StatAttentionItem) => {
  if (!item.last_opened_at) return '从未打开'
  const d = new Date(item.last_opened_at)
  const days = Math.floor((Date.now() - d.getTime()) / 86400000)
  if (days <= 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 30) return `${days} 天前`
  if (days < 365) return `${Math.floor(days / 30)} 个月前`
  return `${Math.floor(days / 365)} 年前`
}
</script>

<template>
  <div class="p-6 md:p-8 max-w-7xl mx-auto">
    <div class="flex items-center justify-between mb-8">
      <div>
        <h1 class="text-2xl font-black text-white flex items-center gap-3">
          <BarChart3 :size="26" class="text-accent" />
          数据看板
        </h1>
        <p class="text-white/45 text-sm mt-1">你的媒体库全貌与近期活跃度</p>
      </div>
      <button
        @click="refresh"
        class="p-2.5 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white hover:bg-white/10 transition-all"
        title="刷新"
      >
        <RefreshCw :size="18" :class="{ 'animate-spin': refreshSpinning }" />
      </button>
    </div>

    <div v-if="loading" class="text-white/40 py-24 text-center">正在汇总统计数据…</div>
    <div
      v-else-if="errorMessage"
      class="py-16 text-center text-red-200 bg-red-400/10 border border-red-400/20 rounded-2xl"
    >
      {{ errorMessage }}
    </div>

    <div v-else class="space-y-6">
      <!-- overview cards -->
      <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        <component
          :is="card.to ? RouterLink : 'div'"
          v-for="card in overviewCards"
          :key="card.label"
          :to="card.to"
          class="rounded-2xl p-4 border backdrop-blur-xl transition-all block"
          :class="[
            card.accent ? 'bg-accent/10 border-accent/25' : 'bg-white/[0.03] border-white/10',
            card.to ? 'hover:bg-white/[0.06] hover:border-white/20 cursor-pointer' : '',
          ]"
        >
          <component :is="card.icon" :size="18" class="text-accent mb-3" />
          <div class="text-2xl font-black text-white truncate" :title="String(card.value)">
            {{ card.value }}
          </div>
          <div class="text-xs text-white/45 mt-1">{{ card.label }}</div>
        </component>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- by type (count + size) -->
        <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
          <h2 class="text-sm font-bold text-white/80 mb-4">按类型（数量）</h2>
          <div class="space-y-3">
            <RouterLink
              v-for="b in typeBars"
              :key="b.key"
              :to="b.to"
              class="block group"
            >
              <div class="flex items-center justify-between text-sm mb-1.5">
                <span class="flex items-center gap-2 text-white/70 group-hover:text-white transition-colors">
                  <component :is="b.icon" :size="15" class="text-accent" />{{ b.label }}
                </span>
                <span class="text-white/50 tabular-nums">{{ b.count.toLocaleString() }}</span>
              </div>
              <div class="h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-accent transition-all duration-500" :style="{ width: b.pct + '%' }" />
              </div>
            </RouterLink>
          </div>

          <h2 class="text-sm font-bold text-white/80 mt-6 mb-4">按类型（占用空间）</h2>
          <div v-if="typeSizeBars.length" class="space-y-3">
            <RouterLink
              v-for="b in typeSizeBars"
              :key="b.key"
              :to="b.to"
              class="block group"
            >
              <div class="flex items-center justify-between text-sm mb-1.5">
                <span class="flex items-center gap-2 text-white/70 group-hover:text-white transition-colors">
                  <component :is="b.icon" :size="15" class="text-accent" />{{ b.label }}
                </span>
                <span class="text-white/50 tabular-nums">{{ b.size }} · {{ b.pct }}%</span>
              </div>
              <div class="h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-accent/80 transition-all duration-500" :style="{ width: b.pct + '%' }" />
              </div>
            </RouterLink>
          </div>
          <div v-else class="text-white/40 text-xs py-2">暂无可统计的体积信息。</div>
        </section>

        <!-- rating + status + source -->
        <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
          <h2 class="text-sm font-bold text-white/80 mb-4">评分分布</h2>
          <div class="space-y-2.5">
            <div v-for="r in ratingBars" :key="r.stars" class="flex items-center gap-3">
              <span class="flex items-center gap-0.5 w-20 shrink-0 text-amber-300/90">
                <template v-if="r.stars > 0">
                  <Star v-for="n in r.stars" :key="n" :size="11" fill="currentColor" />
                </template>
                <span v-else class="text-white/40 text-xs">未评分</span>
              </span>
              <div class="flex-1 h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-amber-400/70 transition-all duration-500" :style="{ width: r.pct + '%' }" />
              </div>
              <span class="w-14 text-right text-white/50 text-sm tabular-nums">{{ r.count.toLocaleString() }}</span>
            </div>
          </div>

          <h2 class="text-sm font-bold text-white/80 mt-6 mb-4">观看状态</h2>
          <div class="space-y-3">
            <div v-for="b in statusBars" :key="b.key">
              <div class="flex items-center justify-between text-sm mb-1.5">
                <span class="text-white/70">{{ b.label }}</span>
                <span class="text-white/50 tabular-nums">{{ b.count.toLocaleString() }} · {{ b.pct }}%</span>
              </div>
              <div class="h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-accent/70 transition-all duration-500" :style="{ width: b.pct + '%' }" />
              </div>
            </div>
          </div>

          <h2 class="text-sm font-bold text-white/80 mt-6 mb-4">来源占比</h2>
          <div class="space-y-3">
            <component
              :is="s.to ? RouterLink : 'div'"
              v-for="s in sourceBars"
              :key="s.key"
              :to="s.to ?? undefined"
              class="block group"
            >
              <div class="flex items-center justify-between text-sm mb-1.5">
                <span class="text-white/70 group-hover:text-white transition-colors">{{ s.label }}</span>
                <span class="text-white/50 tabular-nums">{{ s.count.toLocaleString() }}</span>
              </div>
              <div class="h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-accent/60 transition-all duration-500" :style="{ width: s.pct + '%' }" />
              </div>
            </component>
          </div>
        </section>
      </div>

      <!-- library growth: monthly bars + cumulative line -->
      <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-sm font-bold text-white/80">库增长</h2>
          <span v-if="growthChart" class="text-xs text-white/40">
            柱：当月新增 · 线：累计
          </span>
        </div>
        <div v-if="growthChart" class="relative">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" class="w-full h-40">
            <defs>
              <linearGradient id="growthGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="rgb(var(--color-accent))" stop-opacity="0.32" />
                <stop offset="100%" stop-color="rgb(var(--color-accent))" stop-opacity="0" />
              </linearGradient>
            </defs>
            <!-- monthly added bars -->
            <rect
              v-for="bar in growthChart.bars"
              :key="bar.month"
              :x="bar.x"
              :y="bar.y"
              :width="bar.w"
              :height="bar.height"
              fill="rgb(var(--color-accent))"
              fill-opacity="0.55"
              rx="0.5"
            >
              <title>{{ bar.month }}：新增 {{ bar.added.toLocaleString() }}，累计 {{ bar.cumulative.toLocaleString() }}</title>
            </rect>
            <!-- cumulative line + area -->
            <template v-if="growthChart.line">
              <polygon :points="growthChart.area" fill="url(#growthGrad)" />
              <polyline
                :points="growthChart.line"
                fill="none"
                stroke="rgb(var(--color-accent))"
                stroke-width="1.5"
                vector-effect="non-scaling-stroke"
                stroke-linejoin="round"
              />
            </template>
          </svg>
          <div class="flex justify-between text-xs text-white/40 mt-2">
            <span>{{ growthChart.first.month }}</span>
            <span>共 {{ growthChart.last.cumulative.toLocaleString() }} 项 · {{ growthChart.months }} 个月</span>
            <span>{{ growthChart.last.month }}</span>
          </div>
        </div>
        <div v-else class="text-white/40 text-sm py-6 text-center">
          暂无入库数据。
        </div>
      </section>

      <!-- activity heatmap with type filter -->
      <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
        <div class="flex items-center justify-between mb-4 gap-3 flex-wrap">
          <h2 class="text-sm font-bold text-white/80">近一年活跃度</h2>
          <div class="flex items-center gap-1 bg-white/[0.04] border border-white/10 rounded-xl p-1">
            <button
              v-for="tab in heatmapTypeTabs"
              :key="tab.key"
              @click="heatmapType = tab.key"
              class="px-2.5 py-1 rounded-lg text-xs transition-all tabular-nums"
              :class="heatmapType === tab.key
                ? 'bg-accent/25 text-white'
                : 'text-white/55 hover:text-white/80'"
            >
              {{ tab.label }} · {{ tab.total.toLocaleString() }}
            </button>
          </div>
        </div>
        <div v-if="heatmap" class="overflow-x-auto custom-scrollbar pb-2">
          <div class="flex gap-[3px] min-w-max">
            <div v-for="(week, wi) in heatmap.weeks" :key="wi" class="flex flex-col gap-[3px]">
              <div
                v-for="day in week"
                :key="day.date"
                class="w-[11px] h-[11px] rounded-[2px]"
                :class="levelClass(day.level, day.inRange)"
                :title="day.inRange ? `${day.date}：打开 ${day.count} 次` : ''"
              />
            </div>
          </div>
        </div>
        <div class="flex items-center justify-between mt-3 text-xs text-white/40">
          <span v-if="heatmap">
            共打开 {{ heatmap.total.toLocaleString() }} 次 · 单日峰值 {{ heatmap.max }}
          </span>
          <div class="flex items-center gap-1.5 ml-auto">
            <span>少</span>
            <div class="w-[11px] h-[11px] rounded-[2px] bg-white/[0.04]" />
            <div class="w-[11px] h-[11px] rounded-[2px] bg-accent/25" />
            <div class="w-[11px] h-[11px] rounded-[2px] bg-accent/45" />
            <div class="w-[11px] h-[11px] rounded-[2px] bg-accent/70" />
            <div class="w-[11px] h-[11px] rounded-[2px] bg-accent" />
            <span>多</span>
          </div>
        </div>
      </section>

      <!-- highlights: top creators / longest videos / top tags -->
      <div
        v-if="highlights && (highlights.top_creators.length || highlights.top_videos.length || highlights.top_tags.length)"
        class="grid grid-cols-1 lg:grid-cols-3 gap-6"
      >
        <section
          v-if="highlights.top_creators.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">Top 创作者</h2>
          <p class="text-xs text-white/40 mb-4">按入库作品数</p>
          <ol class="space-y-2">
            <li
              v-for="(c, idx) in highlights.top_creators"
              :key="c.key"
              class="flex items-center gap-3"
            >
              <span class="w-5 text-center text-xs text-white/35 tabular-nums">{{ idx + 1 }}</span>
              <component
                :is="creatorLink(c) ? RouterLink : 'div'"
                :to="creatorLink(c) ?? undefined"
                class="flex items-center gap-3 flex-1 min-w-0 group"
              >
                <div class="w-9 h-9 rounded-lg overflow-hidden bg-black/30 shrink-0">
                  <img
                    v-if="c.cover_path"
                    :src="thumbnailUrl(c.cover_path)"
                    class="w-full h-full object-cover"
                    loading="lazy"
                  />
                </div>
                <div class="flex-1 min-w-0">
                  <div
                    class="text-sm text-white/80 truncate"
                    :class="creatorLink(c) ? 'group-hover:text-white' : ''"
                    :title="c.display_name"
                  >
                    {{ c.display_name }}
                  </div>
                  <div class="text-[10px] text-white/35">
                    {{ c.kind === 'x' ? 'X 作者' : '漫画作者' }}
                  </div>
                </div>
                <span class="text-xs text-white/50 tabular-nums shrink-0">
                  {{ c.media_count.toLocaleString() }}
                </span>
              </component>
            </li>
          </ol>
        </section>

        <section
          v-if="highlights.top_videos.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">最长视频</h2>
          <p class="text-xs text-white/40 mb-4">按时长，点开跳转详情</p>
          <ol class="space-y-2">
            <li
              v-for="(v, idx) in highlights.top_videos"
              :key="v.id"
              class="flex items-center gap-3"
            >
              <span class="w-5 text-center text-xs text-white/35 tabular-nums">{{ idx + 1 }}</span>
              <RouterLink
                :to="`/?media=${v.id}`"
                class="flex items-center gap-3 flex-1 min-w-0 group"
              >
                <div class="w-12 h-9 rounded-lg overflow-hidden bg-black/30 shrink-0">
                  <img
                    v-if="v.cover_path"
                    :src="thumbnailUrl(v.cover_path)"
                    class="w-full h-full object-cover"
                    loading="lazy"
                  />
                </div>
                <div class="flex-1 min-w-0">
                  <div class="text-sm text-white/80 group-hover:text-white truncate" :title="v.title">
                    {{ v.title }}
                  </div>
                  <div class="text-[10px] text-white/35">{{ formatSize(v.file_size) }}</div>
                </div>
                <span class="text-xs text-white/50 tabular-nums shrink-0">
                  {{ formatDurationShort(v.duration) }}
                </span>
              </RouterLink>
            </li>
          </ol>
        </section>

        <section
          v-if="highlights.top_tags.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">热门标签</h2>
          <p class="text-xs text-white/40 mb-4">用得最多的标签（不含作者）</p>
          <div class="flex flex-wrap gap-2">
            <span
              v-for="t in highlights.top_tags"
              :key="`${t.namespace}:${t.name}`"
              class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-white/[0.05] border border-white/10 text-xs text-white/75"
            >
              {{ t.name }}
              <span class="text-white/40 tabular-nums">{{ t.count }}</span>
            </span>
          </div>
        </section>
      </div>

      <!-- attention -->
      <div v-if="attention && !attentionEmpty" class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section
          v-if="attention.dusty.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">尘封的高分作品</h2>
          <p class="text-xs text-white/40 mb-4">评分 ≥ 4，但已 {{ attention.stale_days }} 天没打开</p>
          <div class="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <RouterLink
              v-for="item in attention.dusty"
              :key="item.id"
              :to="`/?media=${item.id}`"
              class="rounded-xl overflow-hidden bg-white/[0.03] border border-white/10 group block hover:border-white/25 transition-colors"
            >
              <div class="aspect-[3/4] bg-black/30 overflow-hidden">
                <img
                  v-if="item.cover_path"
                  :src="thumbnailUrl(item.cover_path)"
                  class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                  loading="lazy"
                />
                <div v-else class="w-full h-full flex items-center justify-center text-white/20 text-xs">无封面</div>
              </div>
              <div class="p-2">
                <div class="text-xs text-white/75 truncate" :title="item.title">{{ item.title }}</div>
                <div class="flex items-center justify-between mt-1">
                  <span class="flex items-center gap-0.5 text-amber-300/90">
                    <Star v-for="n in item.rating" :key="n" :size="9" fill="currentColor" />
                  </span>
                  <span class="text-[10px] text-white/35">{{ lastOpenedText(item) }}</span>
                </div>
              </div>
            </RouterLink>
          </div>
        </section>

        <section
          v-if="attention.unrated.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">看过但还没评分</h2>
          <p class="text-xs text-white/40 mb-4">补个评分，让推荐更准</p>
          <div class="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <RouterLink
              v-for="item in attention.unrated"
              :key="item.id"
              :to="`/?media=${item.id}`"
              class="rounded-xl overflow-hidden bg-white/[0.03] border border-white/10 group block hover:border-white/25 transition-colors"
            >
              <div class="aspect-[3/4] bg-black/30 overflow-hidden">
                <img
                  v-if="item.cover_path"
                  :src="thumbnailUrl(item.cover_path)"
                  class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                  loading="lazy"
                />
                <div v-else class="w-full h-full flex items-center justify-center text-white/20 text-xs">无封面</div>
              </div>
              <div class="p-2">
                <div class="text-xs text-white/75 truncate" :title="item.title">{{ item.title }}</div>
                <div class="text-[10px] text-white/35 mt-1">{{ lastOpenedText(item) }}</div>
              </div>
            </RouterLink>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
