<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertTriangle,
  Check,
  CopyMinus,
  FileImage,
  Film,
  Layers,
  RefreshCw,
  Replace,
  Star,
  Trash2,
  X,
} from 'lucide-vue-next'
import { THUMBNAIL_URL } from '../config'
import { dedupStore } from '../stores/dedupStore'
import type { DedupMediaSummary, DuplicateCandidatePair } from '../types'

const summary = dedupStore.summary
const pairs = dedupStore.pairs
const loading = dedupStore.loading
const errorMessage = dedupStore.errorMessage

const refreshSpinning = ref(false)
const confirmDeletePair = ref<{ pair: DuplicateCandidatePair; target: 'existing' | 'candidate' } | null>(null)

const formatSize = (bytes: number | null) => {
  if (!bytes && bytes !== 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

const formatDuration = (seconds: number | null) => {
  if (!seconds && seconds !== 0) return null
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${m}:${String(s).padStart(2, '0')}`
}

const dimText = (m: DedupMediaSummary) => {
  if (m.width && m.height) return `${m.width}×${m.height}`
  return null
}

const detailsText = (m: DedupMediaSummary): string[] => {
  const parts: string[] = []
  parts.push(formatSize(m.file_size))
  const dim = dimText(m)
  if (dim) parts.push(dim)
  if (m.media_type === 'video' && m.duration) {
    const dur = formatDuration(m.duration)
    if (dur) parts.push(dur)
  }
  if (m.media_type === 'manga' && m.page_count) {
    parts.push(`${m.page_count} 页`)
  }
  return parts
}

const coverSrc = (m: DedupMediaSummary) => {
  if (!m.cover_path) return ''
  return `${THUMBNAIL_URL}/${m.cover_path}`
}

const levelMeta = (level: string) => {
  if (level === 'strong_duplicate') return { label: '强重复', cls: 'bg-red-400/15 border-red-400/30 text-red-200' }
  if (level === 'suspected_duplicate') return { label: '疑似重复', cls: 'bg-amber-400/15 border-amber-400/30 text-amber-200' }
  if (level === 'weak_suspected') return { label: '弱疑似', cls: 'bg-sky-400/15 border-sky-400/30 text-sky-200' }
  return { label: level, cls: 'bg-white/10 border-white/15 text-white/80' }
}

const typeIcon = (type: string) => {
  if (type === 'video') return Film
  if (type === 'manga') return Layers
  return FileImage
}

const filterStatusOptions = [
  { value: 'pending', label: '待处理' },
  { value: 'merged', label: '已合并' },
  { value: 'replaced', label: '已替换路径' },
  { value: 'kept_both', label: '保留两个' },
  { value: 'ignored', label: '已忽略' },
  { value: 'all', label: '全部' },
]

const filterLevelOptions = [
  { value: '', label: '所有等级' },
  { value: 'strong_duplicate', label: '强重复' },
  { value: 'suspected_duplicate', label: '疑似' },
  { value: 'weak_suspected', label: '弱疑似' },
]

const filterTypeOptions = [
  { value: '', label: '所有类型' },
  { value: 'video', label: '视频' },
  { value: 'manga', label: '漫画' },
  { value: 'image', label: '杂图' },
]

const setLevel = async (val: string) => { dedupStore.setFilters({ level: val as any }); await dedupStore.fetchPairs() }
const setStatus = async (val: string) => { dedupStore.setFilters({ status: val as any }); await dedupStore.fetchPairs() }
const setType = async (val: string) => { dedupStore.setFilters({ mediaType: val as any }); await dedupStore.fetchPairs() }

const onRefresh = async () => {
  refreshSpinning.value = true
  window.setTimeout(() => { refreshSpinning.value = false }, 1000)
  await dedupStore.refresh()
}

const onResolve = async (
  pair: DuplicateCandidatePair,
  action: 'keep_existing' | 'replace_path' | 'keep_both' | 'ignore',
) => {
  await dedupStore.resolvePair(pair.id, action)
}

const askDeleteFile = (pair: DuplicateCandidatePair, target: 'existing' | 'candidate') => {
  confirmDeletePair.value = { pair, target }
}

const onDeleteConfirmed = async () => {
  if (!confirmDeletePair.value) return
  const { pair, target } = confirmDeletePair.value
  const mediaId = target === 'existing' ? pair.existing.id : pair.candidate.id
  try {
    await dedupStore.deleteMediaFile(mediaId)
  } finally {
    confirmDeletePair.value = null
  }
}

const noPairs = computed(() => !loading.value && pairs.value.length === 0)

onMounted(async () => {
  await dedupStore.refresh()
})
</script>

<template>
  <div class="z-10 relative min-h-screen">
    <header class="sticky top-0 z-40 bg-background/75 backdrop-blur-xl border-b border-white/10 px-6 md:px-8 py-5 mb-6">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div class="flex items-baseline gap-3">
          <h1 class="text-2xl md:text-3xl font-black text-white tracking-tight">重复管理</h1>
          <p class="text-[11px] font-bold text-accent bg-accent/10 px-2 py-0.5 rounded-full border border-accent/20 uppercase tracking-widest">
            LOCAL DEDUP
          </p>
        </div>
        <button
          @click="onRefresh"
          class="h-11 px-4 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white hover:bg-white/10 flex items-center gap-2 text-sm font-bold transition-all"
        >
          <RefreshCw :size="16" :class="(loading || refreshSpinning) ? 'animate-spin' : ''" />
          刷新
        </button>
      </div>
    </header>

    <main class="px-6 md:px-8 pb-12 space-y-6">
      <div v-if="errorMessage" class="bg-red-400/10 border border-red-400/20 text-red-200 rounded-xl px-4 py-3 text-sm flex items-center justify-between gap-3">
        <span>{{ errorMessage }}</span>
        <button @click="dedupStore.clearError()" class="text-white/55 hover:text-white">
          <X :size="16" />
        </button>
      </div>

      <!-- Summary cards -->
      <section class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
        <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
          <p class="text-[11px] text-white/45 font-bold uppercase tracking-widest">待处理</p>
          <p class="text-2xl font-black text-white mt-1">{{ summary?.pending_pairs ?? 0 }}</p>
        </div>
        <div class="rounded-2xl bg-red-400/10 border border-red-400/15 p-4">
          <p class="text-[11px] text-red-200 font-bold uppercase tracking-widest">强重复</p>
          <p class="text-2xl font-black text-white mt-1">{{ summary?.strong_duplicate ?? 0 }}</p>
        </div>
        <div class="rounded-2xl bg-amber-400/10 border border-amber-400/15 p-4">
          <p class="text-[11px] text-amber-200 font-bold uppercase tracking-widest">疑似</p>
          <p class="text-2xl font-black text-white mt-1">{{ summary?.suspected_duplicate ?? 0 }}</p>
        </div>
        <div class="rounded-2xl bg-sky-400/10 border border-sky-400/15 p-4">
          <p class="text-[11px] text-sky-200 font-bold uppercase tracking-widest">弱疑似</p>
          <p class="text-2xl font-black text-white mt-1">{{ summary?.weak_suspected ?? 0 }}</p>
        </div>
        <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
          <p class="text-[11px] text-white/45 font-bold uppercase tracking-widest">检测中</p>
          <p class="text-2xl font-black text-white mt-1">{{ summary?.checking ?? 0 }}</p>
          <p class="text-[10px] text-white/40 mt-0.5">队列 {{ summary?.queue_size ?? 0 }}</p>
        </div>
        <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
          <p class="text-[11px] text-white/45 font-bold uppercase tracking-widest">后台任务</p>
          <p :class="summary?.worker_running ? 'text-emerald-300' : 'text-white/55'" class="text-base font-black mt-1.5">
            {{ summary?.worker_running ? '运行中' : '空闲' }}
          </p>
        </div>
      </section>

      <!-- Filters -->
      <section class="rounded-2xl bg-white/[0.04] border border-white/10 p-4 flex flex-wrap items-center gap-3">
        <div class="flex items-center gap-2">
          <span class="text-xs font-bold text-white/55">状态</span>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="opt in filterStatusOptions"
              :key="opt.value"
              @click="setStatus(opt.value)"
              :class="dedupStore.state.filterStatus === opt.value ? 'bg-accent text-white' : 'bg-white/5 text-white/60 hover:text-white'"
              class="px-3 py-1.5 rounded-lg border border-white/10 text-[11px] font-bold transition-all"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-xs font-bold text-white/55">等级</span>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="opt in filterLevelOptions"
              :key="opt.value"
              @click="setLevel(opt.value)"
              :class="dedupStore.state.filterLevel === opt.value ? 'bg-accent text-white' : 'bg-white/5 text-white/60 hover:text-white'"
              class="px-3 py-1.5 rounded-lg border border-white/10 text-[11px] font-bold transition-all"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-xs font-bold text-white/55">类型</span>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="opt in filterTypeOptions"
              :key="opt.value"
              @click="setType(opt.value)"
              :class="dedupStore.state.filterMediaType === opt.value ? 'bg-accent text-white' : 'bg-white/5 text-white/60 hover:text-white'"
              class="px-3 py-1.5 rounded-lg border border-white/10 text-[11px] font-bold transition-all"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
      </section>

      <!-- Empty state -->
      <div
        v-if="noPairs"
        class="rounded-2xl border border-dashed border-white/10 bg-white/[0.02] py-16 flex flex-col items-center justify-center text-center"
      >
        <CopyMinus :size="38" class="text-white/35 mb-3" />
        <p class="text-base font-bold text-white/55">没有需要处理的重复条目</p>
        <p class="text-xs text-white/35 mt-1">扫描时如果发现疑似重复，会显示在这里</p>
      </div>

      <!-- Loading state -->
      <div v-if="loading && pairs.length === 0" class="space-y-4">
        <div v-for="i in 3" :key="i" class="rounded-2xl bg-white/5 animate-pulse h-48"></div>
      </div>

      <!-- Pair cards -->
      <section v-if="pairs.length > 0" class="space-y-4">
        <article
          v-for="pair in pairs"
          :key="pair.id"
          class="rounded-2xl border border-white/10 bg-white/[0.04] overflow-hidden"
        >
          <header class="px-5 py-3 flex flex-wrap items-center justify-between gap-3 border-b border-white/10 bg-black/15">
            <div class="flex items-center gap-2">
              <span :class="levelMeta(pair.level).cls" class="rounded-lg border px-2.5 py-1 text-[11px] font-black uppercase tracking-widest">
                {{ levelMeta(pair.level).label }}
              </span>
              <span class="text-xs text-white/55">相似度 {{ pair.similarity }}%</span>
              <span v-if="pair.status !== 'pending'" class="text-[11px] text-white/40 italic">· 已处理</span>
            </div>
            <p v-if="pair.reason" class="text-[11px] text-white/55 truncate max-w-[60%]">{{ pair.reason }}</p>
          </header>

          <div class="grid grid-cols-1 lg:grid-cols-2 gap-0 divide-x divide-white/10">
            <!-- Existing -->
            <div class="p-4">
              <div class="flex items-center justify-between mb-3">
                <span class="text-[10px] font-black text-emerald-300 uppercase tracking-widest">已有条目</span>
                <span v-if="pair.existing.is_missing" class="text-[10px] font-bold text-red-300 uppercase tracking-widest">文件丢失</span>
              </div>
              <div class="flex gap-3">
                <div class="w-24 h-32 rounded-xl bg-black/30 border border-white/10 overflow-hidden shrink-0 flex items-center justify-center">
                  <img v-if="pair.existing.cover_path" :src="coverSrc(pair.existing)" class="w-full h-full object-cover" />
                  <component v-else :is="typeIcon(pair.existing.media_type)" :size="28" class="text-white/30" />
                </div>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <p class="text-sm font-bold text-white line-clamp-2 break-all">{{ pair.existing.title }}</p>
                  <p class="text-[10px] text-white/40 break-all line-clamp-2 font-mono">{{ pair.existing.absolute_path }}</p>
                  <div class="flex flex-wrap items-center gap-1.5 text-[11px] text-white/55">
                    <span v-for="(part, idx) in detailsText(pair.existing)" :key="`e${idx}`" class="px-1.5 py-0.5 rounded bg-white/5">
                      {{ part }}
                    </span>
                    <span v-if="pair.existing.favorite" class="px-1.5 py-0.5 rounded bg-amber-400/15 text-amber-200 flex items-center gap-1"><Star :size="10" /> 收藏</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- Candidate -->
            <div class="p-4 bg-black/10">
              <div class="flex items-center justify-between mb-3">
                <span class="text-[10px] font-black text-amber-300 uppercase tracking-widest">新扫描条目</span>
                <span v-if="pair.candidate.is_missing" class="text-[10px] font-bold text-red-300 uppercase tracking-widest">文件丢失</span>
              </div>
              <div class="flex gap-3">
                <div class="w-24 h-32 rounded-xl bg-black/30 border border-white/10 overflow-hidden shrink-0 flex items-center justify-center">
                  <img v-if="pair.candidate.cover_path" :src="coverSrc(pair.candidate)" class="w-full h-full object-cover" />
                  <component v-else :is="typeIcon(pair.candidate.media_type)" :size="28" class="text-white/30" />
                </div>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <p class="text-sm font-bold text-white line-clamp-2 break-all">{{ pair.candidate.title }}</p>
                  <p class="text-[10px] text-white/40 break-all line-clamp-2 font-mono">{{ pair.candidate.absolute_path }}</p>
                  <div class="flex flex-wrap items-center gap-1.5 text-[11px] text-white/55">
                    <span v-for="(part, idx) in detailsText(pair.candidate)" :key="`c${idx}`" class="px-1.5 py-0.5 rounded bg-white/5">
                      {{ part }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Actions (pending only) -->
          <footer v-if="pair.status === 'pending'" class="px-5 py-3 border-t border-white/10 bg-black/15 flex flex-wrap items-center gap-2">
            <button
              @click="onResolve(pair, 'keep_existing')"
              class="h-9 px-3 rounded-lg bg-accent text-white text-xs font-black flex items-center gap-1.5 hover:brightness-110 transition-all"
              title="保留已有条目，新条目从库中移除（文件不删）"
            >
              <Check :size="14" /> 合并到已有
            </button>
            <button
              @click="onResolve(pair, 'replace_path')"
              class="h-9 px-3 rounded-lg bg-white/10 border border-white/15 text-white text-xs font-bold flex items-center gap-1.5 hover:bg-white/15"
              title="把新路径填到已有条目上（已有路径丢失时常用）"
            >
              <Replace :size="14" /> 替换为新路径
            </button>
            <button
              @click="onResolve(pair, 'keep_both')"
              class="h-9 px-3 rounded-lg bg-white/5 border border-white/10 text-white/80 text-xs font-bold flex items-center gap-1.5 hover:text-white hover:bg-white/10"
            >
              保留两个
            </button>
            <button
              @click="onResolve(pair, 'ignore')"
              class="h-9 px-3 rounded-lg bg-white/5 border border-white/10 text-white/55 text-xs font-bold hover:text-white"
            >
              忽略
            </button>
            <span class="flex-1"></span>
            <button
              @click="askDeleteFile(pair, 'candidate')"
              class="h-9 px-3 rounded-lg bg-red-500/10 border border-red-400/20 text-red-200 text-xs font-bold flex items-center gap-1.5 hover:bg-red-500/20"
              title="删除新扫描的文件"
            >
              <Trash2 :size="14" /> 删除新文件
            </button>
          </footer>
          <footer v-else class="px-5 py-2 border-t border-white/10 bg-black/15 text-[11px] text-white/45">
            <span v-if="pair.resolution_note">备注：{{ pair.resolution_note }}</span>
            <span v-else>已处理 · {{ pair.status }}</span>
          </footer>
        </article>
      </section>
    </main>

    <!-- Delete confirm modal -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="confirmDeletePair"
          class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-background/65 backdrop-blur-xl"
          @click.self="confirmDeletePair = null"
        >
          <div class="w-full max-w-md rounded-2xl bg-[rgb(var(--color-sidebar))] border border-white/10 p-6 shadow-2xl space-y-4">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-red-500/15 text-red-300 flex items-center justify-center">
                <AlertTriangle :size="20" />
              </div>
              <div>
                <h3 class="text-base font-black text-white">删除文件</h3>
                <p class="text-xs text-white/50 mt-0.5">这个操作不可撤销，会从磁盘删除文件并移除媒体库条目</p>
              </div>
            </div>
            <div class="rounded-xl bg-black/30 border border-white/10 p-3 text-xs text-white/65 space-y-1">
              <p class="font-bold text-white truncate">{{ confirmDeletePair.target === 'existing' ? confirmDeletePair.pair.existing.title : confirmDeletePair.pair.candidate.title }}</p>
              <p class="font-mono text-white/45 break-all line-clamp-2">{{ confirmDeletePair.target === 'existing' ? confirmDeletePair.pair.existing.absolute_path : confirmDeletePair.pair.candidate.absolute_path }}</p>
            </div>
            <div class="flex gap-2.5 pt-1">
              <button
                @click="confirmDeletePair = null"
                class="flex-1 h-11 rounded-xl bg-white/5 border border-white/10 text-white/75 font-bold hover:bg-white/10"
              >
                取消
              </button>
              <button
                @click="onDeleteConfirmed"
                class="flex-1 h-11 rounded-xl bg-red-500 text-white font-black hover:brightness-110 flex items-center justify-center gap-2"
              >
                <Trash2 :size="14" /> 确认删除
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
