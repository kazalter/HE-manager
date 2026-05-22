<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import axios from 'axios'
import {
  BarChart3,
  Clock,
  Film,
  Image as ImageIcon,
  Layers,
  RefreshCw,
  Sparkles,
  Star,
} from 'lucide-vue-next'
import { API_BASE_URL, thumbnailUrl } from '../config'
import type {
  StatAttentionItem,
  StatsActivity,
  StatsAttention,
  StatsDistribution,
  StatsOverview,
} from '../types'

const loading = ref(true)
const errorMessage = ref('')
const refreshSpinning = ref(false)

const overview = ref<StatsOverview | null>(null)
const distribution = ref<StatsDistribution | null>(null)
const activity = ref<StatsActivity | null>(null)
const attention = ref<StatsAttention | null>(null)

const fetchAll = async () => {
  loading.value = overview.value === null
  errorMessage.value = ''
  try {
    const [o, d, a, at] = await Promise.all([
      axios.get<StatsOverview>(`${API_BASE_URL}/stats/overview`),
      axios.get<StatsDistribution>(`${API_BASE_URL}/stats/distribution`),
      axios.get<StatsActivity>(`${API_BASE_URL}/stats/activity`, { params: { days: 365 } }),
      axios.get<StatsAttention>(`${API_BASE_URL}/stats/attention`),
    ])
    overview.value = o.data
    distribution.value = d.data
    activity.value = a.data
    attention.value = at.data
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
}

const sourceLabel = (key: string) => {
  if (key === 'local') return '本地'
  if (key === 'x') return 'X (推特)'
  if (key === 'wnacg') return 'WNACG'
  return key
}

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
    { label: '媒体总数', value: o.total.toLocaleString(), icon: BarChart3, accent: true },
    { label: '收藏', value: o.favorites.toLocaleString(), icon: Star },
    { label: '已评分', value: o.rated.toLocaleString(), icon: Sparkles },
    { label: '平均评分', value: o.average_rating ? o.average_rating.toFixed(2) : '—', icon: Star },
    { label: '库总体积', value: formatSize(o.total_size_bytes), icon: Layers },
    { label: '视频总时长', value: formatDurationHours(o.total_duration_seconds), icon: Clock },
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
  }))
})

// ---- growth line (SVG) ----
const growthPath = computed(() => {
  const g = distribution.value?.growth ?? []
  if (g.length < 2) return null
  const w = 100
  const h = 100
  const maxCum = Math.max(1, ...g.map(p => p.cumulative))
  const step = w / (g.length - 1)
  const pts = g.map((p, i) => `${(i * step).toFixed(2)},${(h - (p.cumulative / maxCum) * h).toFixed(2)}`)
  return {
    line: pts.join(' '),
    area: `0,${h} ${pts.join(' ')} ${w},${h}`,
    first: g[0],
    last: g[g.length - 1],
    points: g.length,
  }
})

// ---- activity heatmap ----
const heatmap = computed(() => {
  const a = activity.value
  if (!a) return null
  const counts = new Map<string, number>()
  for (const b of a.buckets) counts.set(b.date, b.count)

  const to = new Date(a.to_date + 'T00:00:00')
  const from = new Date(to)
  from.setDate(from.getDate() - (a.days - 1))
  // Pad the start back to the previous Sunday so weeks line up in columns.
  const gridStart = new Date(from)
  gridStart.setDate(gridStart.getDate() - gridStart.getDay())

  const max = Math.max(1, a.max)
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
  return { weeks, total: a.total, max: a.max }
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
        <div
          v-for="card in overviewCards"
          :key="card.label"
          class="rounded-2xl p-4 border backdrop-blur-xl transition-all"
          :class="card.accent
            ? 'bg-accent/10 border-accent/25'
            : 'bg-white/[0.03] border-white/10'"
        >
          <component :is="card.icon" :size="18" class="text-accent mb-3" />
          <div class="text-2xl font-black text-white truncate" :title="String(card.value)">
            {{ card.value }}
          </div>
          <div class="text-xs text-white/45 mt-1">{{ card.label }}</div>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- by type -->
        <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
          <h2 class="text-sm font-bold text-white/80 mb-4">按类型</h2>
          <div class="space-y-3">
            <div v-for="b in typeBars" :key="b.key">
              <div class="flex items-center justify-between text-sm mb-1.5">
                <span class="flex items-center gap-2 text-white/70">
                  <component :is="b.icon" :size="15" class="text-accent" />{{ b.label }}
                </span>
                <span class="text-white/50 tabular-nums">{{ b.count.toLocaleString() }}</span>
              </div>
              <div class="h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-accent transition-all duration-500" :style="{ width: b.pct + '%' }" />
              </div>
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
        </section>

        <!-- rating + source -->
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

          <h2 class="text-sm font-bold text-white/80 mt-6 mb-4">来源占比</h2>
          <div class="space-y-3">
            <div v-for="s in sourceBars" :key="s.key">
              <div class="flex items-center justify-between text-sm mb-1.5">
                <span class="text-white/70">{{ s.label }}</span>
                <span class="text-white/50 tabular-nums">{{ s.count.toLocaleString() }}</span>
              </div>
              <div class="h-2.5 rounded-full bg-white/[0.05] overflow-hidden">
                <div class="h-full rounded-full bg-accent/60 transition-all duration-500" :style="{ width: s.pct + '%' }" />
              </div>
            </div>
          </div>
        </section>
      </div>

      <!-- library growth -->
      <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
        <h2 class="text-sm font-bold text-white/80 mb-4">库增长（按月累计）</h2>
        <div v-if="growthPath" class="relative">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" class="w-full h-40">
            <defs>
              <linearGradient id="growthGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="rgb(var(--color-accent))" stop-opacity="0.35" />
                <stop offset="100%" stop-color="rgb(var(--color-accent))" stop-opacity="0" />
              </linearGradient>
            </defs>
            <polygon :points="growthPath.area" fill="url(#growthGrad)" />
            <polyline
              :points="growthPath.line"
              fill="none"
              stroke="rgb(var(--color-accent))"
              stroke-width="1.5"
              vector-effect="non-scaling-stroke"
              stroke-linejoin="round"
            />
          </svg>
          <div class="flex justify-between text-xs text-white/40 mt-2">
            <span>{{ growthPath.first.month }}</span>
            <span>共 {{ growthPath.last.cumulative.toLocaleString() }} 项 · {{ growthPath.points }} 个月</span>
            <span>{{ growthPath.last.month }}</span>
          </div>
        </div>
        <div v-else class="text-white/40 text-sm py-6 text-center">
          数据不足以绘制曲线（库内媒体集中在同一个月入库）。
        </div>
      </section>

      <!-- activity heatmap -->
      <section class="rounded-2xl p-5 bg-white/[0.03] border border-white/10">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-sm font-bold text-white/80">近一年活跃度</h2>
          <span class="text-xs text-white/40" v-if="heatmap">
            共打开 {{ heatmap.total.toLocaleString() }} 次 · 单日峰值 {{ heatmap.max }}
          </span>
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
        <div class="flex items-center justify-end gap-1.5 mt-3 text-xs text-white/40">
          <span>少</span>
          <div class="w-[11px] h-[11px] rounded-[2px] bg-white/[0.04]" />
          <div class="w-[11px] h-[11px] rounded-[2px] bg-accent/25" />
          <div class="w-[11px] h-[11px] rounded-[2px] bg-accent/45" />
          <div class="w-[11px] h-[11px] rounded-[2px] bg-accent/70" />
          <div class="w-[11px] h-[11px] rounded-[2px] bg-accent" />
          <span>多</span>
        </div>
      </section>

      <!-- attention -->
      <div v-if="attention && !attentionEmpty" class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section
          v-if="attention.dusty.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">尘封的高分作品</h2>
          <p class="text-xs text-white/40 mb-4">评分 ≥ 4，但已 {{ attention.stale_days }} 天没打开</p>
          <div class="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <div
              v-for="item in attention.dusty"
              :key="item.id"
              class="rounded-xl overflow-hidden bg-white/[0.03] border border-white/10 group"
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
            </div>
          </div>
        </section>

        <section
          v-if="attention.unrated.length"
          class="rounded-2xl p-5 bg-white/[0.03] border border-white/10"
        >
          <h2 class="text-sm font-bold text-white/80 mb-1">看过但还没评分</h2>
          <p class="text-xs text-white/40 mb-4">补个评分，让推荐更准</p>
          <div class="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <div
              v-for="item in attention.unrated"
              :key="item.id"
              class="rounded-xl overflow-hidden bg-white/[0.03] border border-white/10 group"
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
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
