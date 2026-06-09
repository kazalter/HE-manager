<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import axios from 'axios'
import {
  CheckSquare,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Download,
  ExternalLink,
  Globe2,
  RefreshCw,
  Search,
  ShieldCheck,
  Square,
  X,
} from 'lucide-vue-next'
import { API_BASE_URL, authUrl } from '../../config'
import type { ExternalFavoriteItem, ExternalFavoriteSource, Media } from '../../types'
import MediaDetail from '../MediaDetail.vue'
import ExternalDownloadProgress from '../ExternalDownloadProgress.vue'
import AutoSyncSection from './AutoSyncSection.vue'
import { externalDownloadStore } from '../../stores/externalDownloadStore'

const favoritesUrl = ref('https://www.wnacg.com/users-users_fav.html')
const downloadRootPath = ref('')
const cookie = ref('')
const pageLimit = ref(30)
const searchQuery = ref('')
const loading = ref(false)
const syncing = ref(false)
const errorMessage = ref('')
const items = ref<ExternalFavoriteItem[]>([])
const sources = ref<ExternalFavoriteSource[]>([])
const activeSourceId = ref<number | null>(null)
const pageSize = 15
const currentPage = ref(1)
const pageInput = ref('1')
const pageDropdownOpen = ref(false)
const downloadPanelOpen = ref(false)
const selectedDownloadIds = ref<Set<number>>(new Set())
const downloadJob = externalDownloadStore.job
const downloadInProgress = externalDownloadStore.inProgress
const failedTasks = externalDownloadStore.failedTasks
const localMangaList = ref<Media[]>([])
const selectedLocalMedia = ref<Media | null>(null)

const activeSiteSources = computed(() => sources.value.filter(source => source.source_type === 'wnacg'))
const activeSource = computed(() => {
  return activeSiteSources.value.find(source => source.id === activeSourceId.value) || activeSiteSources.value[0] || null
})

const filteredItems = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) return items.value
  return items.value.filter(item => {
    return item.title.toLowerCase().includes(keyword) || (item.category_name || '').toLowerCase().includes(keyword)
  })
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredItems.value.length / pageSize)))
const pagedItems = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredItems.value.slice(start, start + pageSize)
})
const pageStart = computed(() => filteredItems.value.length === 0 ? 0 : (currentPage.value - 1) * pageSize + 1)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize, filteredItems.value.length))
const pageOptions = computed(() => Array.from({ length: totalPages.value }, (_, index) => index + 1))
const downloadableFilteredItems = computed(() => filteredItems.value.filter(item => !item.local_media_id))
const selectedDownloadItems = computed(() => {
  return items.value.filter(item => selectedDownloadIds.value.has(item.id) && !item.local_media_id)
})
const allFilteredSelected = computed(() => {
  return downloadableFilteredItems.value.length > 0 && downloadableFilteredItems.value.every(item => selectedDownloadIds.value.has(item.id))
})

const statusText = computed(() => {
  if (!activeSource.value) return '未同步'
  if (activeSource.value.status === 'ok') return '已同步'
  if (activeSource.value.status === 'syncing') return '同步中'
  if (activeSource.value.status === 'error') return '同步失败'
  return '待同步'
})

const formatTime = (value: string | null) => {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

const coverSrc = (item: ExternalFavoriteItem) => {
  return authUrl(`${API_BASE_URL}/external/favorites/${item.id}/cover`)
}

const fetchLocalMangaList = async () => {
  const res = await axios.get(`${API_BASE_URL}/media`, { params: { media_type: 'manga' } })
  localMangaList.value = res.data
  return localMangaList.value
}

const openLocalMedia = async (mediaId: number) => {
  let media = localMangaList.value.find(item => item.id === mediaId)
  if (!media) {
    const mediaList = await fetchLocalMangaList()
    media = mediaList.find(item => item.id === mediaId)
  }
  if (!media) {
    const res = await axios.get(`${API_BASE_URL}/media/${mediaId}`)
    media = res.data as Media
    localMangaList.value = [media, ...localMangaList.value.filter(item => item.id !== mediaId)]
  }
  if (media) selectedLocalMedia.value = media
}

const openExternalItem = async (item: ExternalFavoriteItem) => {
  if (item.local_media_id) {
    try {
      await openLocalMedia(item.local_media_id)
      return
    } catch (err) {
      console.error('Failed to open local manga:', err)
    }
  }
  window.open(item.url, '_blank', 'noreferrer')
}

const closeLocalMedia = () => {
  selectedLocalMedia.value = null
}

const updateLocalMediaInList = (media: Media) => {
  const index = localMangaList.value.findIndex(item => item.id === media.id)
  if (index >= 0) {
    localMangaList.value[index] = media
  } else {
    localMangaList.value = [media, ...localMangaList.value]
  }
  selectedLocalMedia.value = media
}

const goToPage = (page: number) => {
  currentPage.value = Math.min(Math.max(page, 1), totalPages.value)
  pageInput.value = String(currentPage.value)
  pageDropdownOpen.value = false
}

const submitPageInput = () => {
  const page = Number.parseInt(pageInput.value, 10)
  goToPage(Number.isFinite(page) ? page : currentPage.value)
}

const toggleDownloadSelection = (item: ExternalFavoriteItem) => {
  if (item.local_media_id) return
  const next = new Set(selectedDownloadIds.value)
  if (next.has(item.id)) {
    next.delete(item.id)
  } else {
    next.add(item.id)
  }
  selectedDownloadIds.value = next
}

const toggleAllFilteredSelection = () => {
  const next = new Set(selectedDownloadIds.value)
  if (allFilteredSelected.value) {
    downloadableFilteredItems.value.forEach(item => next.delete(item.id))
  } else {
    downloadableFilteredItems.value.forEach(item => next.add(item.id))
  }
  selectedDownloadIds.value = next
}

const clearDownloadSelection = () => {
  selectedDownloadIds.value = new Set()
}

const exportSelectedLinks = () => {
  if (selectedDownloadItems.value.length === 0) return
  const content = selectedDownloadItems.value.map(item => `${item.title}\n${item.url}`).join('\n\n')
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `he-manager-external-downloads-${new Date().toISOString().slice(0, 10)}.txt`
  link.click()
  URL.revokeObjectURL(url)
}

const startWnacgDownload = async () => {
  if (selectedDownloadItems.value.length === 0 || downloadInProgress.value) return
  const pendingItems = selectedDownloadItems.value.filter(item => !item.local_media_id)
  if (pendingItems.length === 0) return
  const trimmedDownloadRootPath = downloadRootPath.value.trim()
  if (!trimmedDownloadRootPath) {
    downloadPanelOpen.value = true
    errorMessage.value = '请先设置下载位置'
    return
  }
  errorMessage.value = ''
  externalDownloadStore.clearError()
  const saved = await saveDownloadRootPath()
  if (!saved) return
  try {
    await externalDownloadStore.startDownload(
      pendingItems.map(item => item.id),
      trimmedDownloadRootPath,
    )
  } catch (err: any) {
    console.error('Failed to start WNACG download:', err)
    errorMessage.value = err.response?.data?.detail || '启动下载失败'
  }
}

const retryFailedDownloads = async () => {
  if (downloadInProgress.value) return
  const trimmedDownloadRootPath = downloadRootPath.value.trim()
  if (!trimmedDownloadRootPath) {
    errorMessage.value = '请先设置下载位置'
    return
  }
  errorMessage.value = ''
  externalDownloadStore.clearError()
  const saved = await saveDownloadRootPath()
  if (!saved) return
  try {
    await externalDownloadStore.retryFailed(trimmedDownloadRootPath)
  } catch (err: any) {
    console.error('Failed to retry WNACG downloads:', err)
    errorMessage.value = err.response?.data?.detail || '重试失败'
  }
}

const dismissDownloadJob = () => externalDownloadStore.dismissJob()

const fetchSources = async () => {
  const res = await axios.get(`${API_BASE_URL}/external/sources`)
  sources.value = res.data
  if (!activeSourceId.value && activeSiteSources.value.length > 0) {
    activeSourceId.value = activeSiteSources.value[0].id
    favoritesUrl.value = activeSiteSources.value[0].favorites_url
    downloadRootPath.value = activeSiteSources.value[0].download_root_path || ''
  }
}

const fetchItems = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await axios.get(`${API_BASE_URL}/external/favorites`, {
      params: {
        source_type: 'wnacg',
        source_id: activeSourceId.value || undefined,
      },
    })
    items.value = res.data
    goToPage(1)
  } catch (err) {
    console.error('Failed to fetch external favorites:', err)
    errorMessage.value = '读取外部收藏失败'
  } finally {
    loading.value = false
  }
}

const syncWnacg = async () => {
  syncing.value = true
  errorMessage.value = ''
  try {
    const res = await axios.post(`${API_BASE_URL}/external/wnacg/sync`, {
      source_id: activeSourceId.value || undefined,
      name: 'WNACG',
      favorites_url: favoritesUrl.value.trim(),
      cookie: cookie.value.trim() || undefined,
      page_limit: pageLimit.value,
    })
    const source = res.data.source as ExternalFavoriteSource
    activeSourceId.value = source.id
    items.value = res.data.items
    goToPage(1)
    cookie.value = ''
    await fetchSources()
  } catch (err: any) {
    console.error('Failed to sync WNACG favorites:', err)
    errorMessage.value = err.response?.data?.detail || '同步 WNACG 收藏失败'
  } finally {
    syncing.value = false
  }
}

const selectSource = async (source: ExternalFavoriteSource) => {
  activeSourceId.value = source.id
  favoritesUrl.value = source.favorites_url
  downloadRootPath.value = source.download_root_path || ''
  await fetchItems()
}

const saveDownloadRootPath = async () => {
  if (!activeSourceId.value) return true
  const trimmedDownloadRootPath = downloadRootPath.value.trim()
  if (!trimmedDownloadRootPath) {
    errorMessage.value = '请先设置下载位置'
    return false
  }
  errorMessage.value = ''
  try {
    const res = await axios.patch(`${API_BASE_URL}/external/sources/${activeSourceId.value}`, {
      download_root_path: trimmedDownloadRootPath,
    })
    const updated = res.data as ExternalFavoriteSource
    sources.value = sources.value.map(source => source.id === updated.id ? updated : source)
    downloadRootPath.value = updated.download_root_path || ''
    return true
  } catch (err: any) {
    console.error('Failed to save download path:', err)
    errorMessage.value = err.response?.data?.detail || '保存路径失败'
    return false
  }
}

const handleAutoSyncUpdate = async (payload: { auto_sync_enabled?: boolean; auto_sync_interval_hours?: number }) => {
  if (!activeSourceId.value) return
  try {
    const res = await axios.patch(`${API_BASE_URL}/auto-sync/wnacg/${activeSourceId.value}`, payload)
    const updated = res.data as ExternalFavoriteSource
    sources.value = sources.value.map(s => s.id === updated.id ? updated : s)
  } catch (err: any) {
    errorMessage.value = err.response?.data?.detail || '更新自动同步配置失败'
  }
}

let unsubscribeCompleted: (() => void) | null = null

onMounted(async () => {
  externalDownloadStore.ensureResumed()
  unsubscribeCompleted = externalDownloadStore.onCompleted(async () => {
    await fetchItems()
    await fetchLocalMangaList()
  })
  await fetchSources()
  await fetchItems()
})

onUnmounted(() => {
  if (unsubscribeCompleted) {
    unsubscribeCompleted()
    unsubscribeCompleted = null
  }
})

watch(() => externalDownloadStore.errorMessage.value, (msg) => {
  if (msg) errorMessage.value = msg
})

watch([searchQuery, filteredItems], () => {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (searchQuery.value) {
    currentPage.value = 1
  }
  pageInput.value = String(currentPage.value)
})
</script>

<template>
  <section class="space-y-6">
    <div class="grid grid-cols-1 xl:grid-cols-[minmax(320px,420px),1fr] gap-6">
      <div class="bg-white/[0.04] border border-white/10 rounded-2xl p-5 space-y-4">
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-center gap-3 text-white">
            <div class="w-10 h-10 rounded-xl bg-accent/15 text-accent flex items-center justify-center border border-accent/20">
              <Globe2 :size="20" />
            </div>
            <div>
              <h2 class="text-base font-black">WNACG</h2>
              <p class="text-xs text-white/45">{{ statusText }} · {{ formatTime(activeSource?.last_synced_at || null) }}</p>
            </div>
          </div>
          <div class="flex items-center gap-1.5 text-[11px] text-emerald-300 bg-emerald-400/10 border border-emerald-400/15 rounded-full px-2 py-1">
            <ShieldCheck :size="13" />
            本地保存
          </div>
        </div>

        <div v-if="activeSiteSources.length > 0" class="flex flex-wrap gap-2">
          <button
            v-for="source in activeSiteSources"
            :key="source.id"
            @click="selectSource(source)"
            :class="activeSourceId === source.id ? 'bg-accent text-white' : 'bg-white/5 text-white/55 hover:text-white'"
            class="px-3 py-2 rounded-xl border border-white/10 text-xs font-bold transition-all"
          >
            {{ source.name }}
          </button>
        </div>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/55">喜欢页地址</span>
          <input
            v-model="favoritesUrl"
            type="url"
            class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
          />
        </label>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/55">Cookie</span>
          <textarea
            v-model="cookie"
            rows="4"
            placeholder="粘贴你自己账号在 WNACG 的 Cookie"
            class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50 resize-none"
          ></textarea>
        </label>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/55">每个分类同步页数</span>
          <input
            v-model.number="pageLimit"
            type="number"
            min="1"
            max="30"
            class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50"
          />
        </label>

        <button
          @click="syncWnacg"
          :disabled="syncing"
          class="w-full h-12 rounded-xl bg-accent text-white font-black flex items-center justify-center gap-2 hover:brightness-110 disabled:opacity-60 disabled:cursor-not-allowed transition-all"
        >
          <RefreshCw :size="18" :class="syncing ? 'animate-spin' : ''" />
          {{ syncing ? '同步中' : '同步收藏' }}
        </button>

        <AutoSyncSection
          v-if="activeSource"
          source-type="wnacg"
          :source-id="activeSource.id"
          :enabled="activeSource.auto_sync_enabled"
          :interval-hours="activeSource.auto_sync_interval_hours"
          :last-run-at="activeSource.auto_sync_last_run_at"
          :next-run-at="activeSource.auto_sync_next_run_at"
          :last-status="activeSource.auto_sync_last_status"
          :last-message="activeSource.auto_sync_last_message"
          :can-enable="!!activeSource.cookie_saved && !!activeSource.download_root_path"
          disable-reason="请先保存 Cookie 并设置下载路径"
          @update="handleAutoSyncUpdate"
        />

        <p v-if="activeSource?.last_error" class="text-xs text-red-300 bg-red-400/10 border border-red-400/20 rounded-xl px-3 py-2">
          {{ activeSource.last_error }}
        </p>

      </div>

      <div class="min-h-[360px] space-y-4">
        <!-- Panel toolbar: search + refresh + download (was previously in page header) -->
        <div class="bg-white/[0.04] border border-white/10 rounded-2xl px-3 py-3 flex flex-wrap items-center gap-3">
          <p class="text-[11px] font-bold text-accent bg-accent/10 px-2 py-0.5 rounded-full border border-accent/20 uppercase tracking-widest">
            {{ filteredItems.length }} ITEMS
          </p>
          <div class="relative flex-1 min-w-[200px] group">
            <Search class="absolute left-4 top-1/2 -translate-y-1/2 text-white/30 group-focus-within:text-accent transition-colors" :size="16" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索标题或分类"
              class="w-full bg-black/20 border border-white/10 rounded-xl pl-10 pr-3 py-2.5 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </div>
          <button
            @click="fetchItems"
            class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white flex items-center justify-center transition-all"
            title="刷新列表"
          >
            <RefreshCw :size="16" />
          </button>
          <button
            @click="downloadPanelOpen = true"
            class="h-10 px-3.5 rounded-xl bg-accent text-white font-black border border-white/10 flex items-center gap-2 hover:brightness-110 transition-all text-sm"
            title="下载选择"
          >
            <Download :size="16" />
            <span>下载</span>
            <span v-if="selectedDownloadItems.length > 0" class="min-w-5 h-5 rounded-full bg-white/20 px-1.5 text-[11px] leading-5 text-center">
              {{ selectedDownloadItems.length }}
            </span>
          </button>
        </div>

        <div v-if="errorMessage" class="bg-red-400/10 border border-red-400/20 text-red-200 rounded-xl px-4 py-3 text-sm">
          {{ errorMessage }}
        </div>

        <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5 gap-5">
          <div v-for="i in 10" :key="i" class="aspect-[3/4.3] bg-white/5 animate-pulse rounded-2xl border border-white/5"></div>
        </div>

        <div v-else-if="filteredItems.length > 0" class="space-y-5">
          <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5 gap-5">
            <button
              v-for="item in pagedItems"
              :key="item.id"
              type="button"
              @click="openExternalItem(item)"
              class="group bg-white/[0.04] border border-white/10 rounded-2xl overflow-hidden hover:-translate-y-1 hover:border-accent/35 transition-all text-left"
            >
              <div class="aspect-[3/4] bg-black/30 overflow-hidden relative">
                <img
                  v-if="item.cover_url"
                  :src="coverSrc(item)"
                  :alt="item.title"
                  class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                />
                <div v-else class="w-full h-full flex items-center justify-center text-white/25">
                  <Globe2 :size="34" />
                </div>
                <span
                  v-if="item.local_media_id"
                  class="absolute left-2 top-2 rounded-lg bg-emerald-400/90 px-2 py-1 text-[10px] font-black text-slate-950"
                >
                  已下载
                </span>
              </div>
              <div class="p-3 space-y-2">
                <h3 class="text-sm font-bold text-white line-clamp-2 leading-snug min-h-[2.6em]">{{ item.title }}</h3>
                <div class="flex items-center justify-between gap-2 text-xs text-white/40">
                  <span class="truncate">{{ item.category_name || 'WNACG' }}</span>
                  <ExternalLink :size="14" class="shrink-0 text-white/35 group-hover:text-accent" />
                </div>
              </div>
            </button>
          </div>

          <div class="flex flex-wrap items-center justify-between gap-3 bg-white/[0.04] border border-white/10 rounded-2xl px-4 py-3">
            <p class="text-xs text-white/45">
              {{ pageStart }}-{{ pageEnd }} / {{ filteredItems.length }}
            </p>
            <div class="flex flex-wrap items-center gap-2">
              <button
                @click="toggleAllFilteredSelection"
                class="h-10 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white flex items-center gap-2 text-xs font-bold transition-all"
                title="全选当前列表"
              >
                <CheckSquare v-if="allFilteredSelected" :size="16" />
                <Square v-else :size="16" />
                全选
              </button>
              <button
                @click="goToPage(currentPage - 1)"
                :disabled="currentPage <= 1"
                class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white disabled:opacity-35 disabled:cursor-not-allowed flex items-center justify-center transition-all"
                title="上一页"
              >
                <ChevronLeft :size="18" />
              </button>
              <form @submit.prevent="submitPageInput" class="flex items-center gap-2">
                <input
                  v-model="pageInput"
                  type="number"
                  min="1"
                  :max="totalPages"
                  class="w-16 h-10 rounded-xl bg-black/20 border border-white/10 px-2 text-center text-sm font-bold text-white focus:outline-none focus:ring-2 focus:ring-accent/50"
                  title="输入页码"
                />
                <span class="text-sm font-bold text-white/45">/ {{ totalPages }}</span>
                <div class="relative">
                  <button
                    type="button"
                    @click="pageDropdownOpen = !pageDropdownOpen"
                    class="h-10 min-w-24 rounded-xl bg-black/20 border border-white/10 px-3 text-sm font-bold text-white focus:outline-none focus:ring-2 focus:ring-accent/50 flex items-center justify-between gap-2 hover:bg-white/10 transition-all"
                    title="选择页码"
                  >
                    <span>第 {{ currentPage }} 页</span>
                    <ChevronDown :size="15" :class="pageDropdownOpen ? 'rotate-180' : ''" class="transition-transform text-white/45" />
                  </button>
                  <div
                    v-if="pageDropdownOpen"
                    class="absolute right-0 bottom-full mb-2 z-50 min-w-28 max-h-72 overflow-y-auto rounded-2xl border border-white/10 bg-sidebar/95 backdrop-blur-xl shadow-2xl p-1"
                  >
                    <button
                      v-for="page in pageOptions"
                      :key="page"
                      type="button"
                      @click="goToPage(page)"
                      :class="currentPage === page ? 'bg-accent text-white' : 'text-white/65 hover:text-white hover:bg-white/8'"
                      class="w-full rounded-xl px-3 py-2 text-left text-sm font-bold transition-all"
                    >
                      第 {{ page }} 页
                    </button>
                  </div>
                </div>
              </form>
              <button
                @click="goToPage(currentPage + 1)"
                :disabled="currentPage >= totalPages"
                class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white disabled:opacity-35 disabled:cursor-not-allowed flex items-center justify-center transition-all"
                title="下一页"
              >
                <ChevronRight :size="18" />
              </button>
            </div>
          </div>
        </div>

        <div v-else class="min-h-[360px] flex flex-col items-center justify-center text-center text-white/35 border border-dashed border-white/10 rounded-2xl">
          <Globe2 :size="34" class="mb-4" />
          <p class="text-lg font-bold text-white/45">还没有外部收藏</p>
          <p class="text-sm mt-2">同步后会显示在这里</p>
        </div>
      </div>
    </div>

    <div
      v-if="downloadPanelOpen"
      class="fixed inset-0 z-50 bg-black/65 backdrop-blur-sm flex justify-end"
      @click.self="downloadPanelOpen = false"
    >
      <aside class="w-full max-w-2xl h-full bg-sidebar border-l border-white/10 shadow-2xl flex flex-col">
        <div class="px-5 py-4 border-b border-white/10 flex items-center justify-between gap-3">
          <div>
            <h2 class="text-xl font-black text-white">下载选择</h2>
            <p class="text-xs text-white/45 mt-1">已选择 {{ selectedDownloadItems.length }} 个，当前列表 {{ filteredItems.length }} 个</p>
          </div>
          <button
            @click="downloadPanelOpen = false"
            class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white flex items-center justify-center transition-all"
            title="关闭"
          >
            <X :size="18" />
          </button>
        </div>

        <div class="px-5 py-4 border-b border-white/10 space-y-2">
          <label class="block space-y-2">
            <span class="text-xs font-bold text-white/70">下载位置 <span class="text-red-300">*</span></span>
            <input
              v-model="downloadRootPath"
              type="text"
              required
              placeholder="例如 C:\Users\25768\Desktop\HE_Project\HE_manager\external_downloads"
              class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </label>
          <p class="text-[11px] text-white/35">必填。漫画会保存到该路径下的 manga 目录，开始下载时会自动记住这个位置。</p>
        </div>

        <div class="px-5 py-3 border-b border-white/10 flex flex-wrap items-center gap-2">
          <button
            @click="toggleAllFilteredSelection"
            :disabled="downloadableFilteredItems.length === 0"
            class="h-10 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white flex items-center gap-2 text-xs font-bold transition-all"
          >
            <CheckSquare v-if="allFilteredSelected" :size="16" />
            <Square v-else :size="16" />
            全选当前列表
          </button>
          <button
            @click="clearDownloadSelection"
            :disabled="selectedDownloadItems.length === 0"
            class="h-10 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white disabled:opacity-35 disabled:cursor-not-allowed text-xs font-bold transition-all"
          >
            清空选择
          </button>
          <button
            @click="startWnacgDownload"
            :disabled="selectedDownloadItems.length === 0 || !downloadRootPath.trim() || downloadInProgress"
            class="h-10 px-4 rounded-xl bg-accent text-white disabled:opacity-45 disabled:cursor-not-allowed flex items-center gap-2 text-xs font-black transition-all"
          >
            <Download :size="16" :class="downloadInProgress ? 'animate-pulse' : ''" />
            {{ downloadInProgress ? '下载中' : '开始下载' }}
          </button>
          <button
            @click="exportSelectedLinks"
            :disabled="selectedDownloadItems.length === 0"
            class="h-10 px-4 rounded-xl bg-accent text-white disabled:opacity-45 disabled:cursor-not-allowed flex items-center gap-2 text-xs font-black transition-all"
          >
            <Download :size="16" />
            导出所选链接
          </button>
        </div>

        <ExternalDownloadProgress />
        <div
          v-if="downloadJob && !downloadInProgress"
          class="px-5 py-3 border-b border-white/10 bg-black/15 flex flex-wrap items-center gap-2"
        >
          <p
            class="text-[11px] font-bold mr-auto"
            :class="failedTasks.length ? 'text-red-300' : 'text-emerald-300'"
          >
            {{ failedTasks.length ? `${failedTasks.length} 本下载失败（明细见上方列表）` : '本次下载已结束' }}
          </p>
          <button
            v-if="failedTasks.length"
            @click="retryFailedDownloads"
            :disabled="!downloadRootPath.trim()"
            class="h-8 px-3 rounded-lg bg-accent text-white disabled:opacity-45 disabled:cursor-not-allowed flex items-center gap-1 text-[11px] font-black transition-all"
            title="只重新下载失败的漫画，已下好的页会跳过"
          >
            <RefreshCw :size="13" />
            重试失败项
          </button>
          <button
            @click="dismissDownloadJob"
            class="h-8 px-3 rounded-lg bg-white/5 border border-white/10 text-white/60 hover:text-white flex items-center gap-1 text-[11px] font-black transition-all"
            title="关闭进度面板"
          >
            <X :size="13" />
            关闭
          </button>
        </div>

        <div class="flex-1 overflow-y-auto p-5 space-y-3">
          <label
            v-for="item in filteredItems"
            :key="item.id"
            :class="item.local_media_id ? 'opacity-70 cursor-default' : 'cursor-pointer hover:border-accent/35'"
            class="grid grid-cols-[auto,56px,1fr,auto] gap-3 items-center rounded-2xl border border-white/10 bg-white/[0.04] p-3 transition-all"
          >
            <input
              type="checkbox"
              :checked="selectedDownloadIds.has(item.id) && !item.local_media_id"
              :disabled="!!item.local_media_id"
              class="w-4 h-4 accent-accent disabled:opacity-35 disabled:cursor-not-allowed"
              @change="toggleDownloadSelection(item)"
            />
            <div class="w-14 h-[74px] rounded-lg bg-black/30 overflow-hidden border border-white/10">
              <img
                v-if="item.cover_url"
                :src="coverSrc(item)"
                :alt="item.title"
                class="w-full h-full object-cover"
              />
              <div v-else class="w-full h-full flex items-center justify-center text-white/25">
                <Globe2 :size="20" />
              </div>
            </div>
            <div class="min-w-0">
              <div class="flex items-start gap-2">
                <p class="min-w-0 text-sm font-bold text-white line-clamp-2 leading-snug">{{ item.title }}</p>
                <span
                  v-if="item.local_media_id"
                  class="shrink-0 rounded-md bg-emerald-400/15 border border-emerald-300/20 px-1.5 py-0.5 text-[10px] font-black text-emerald-200"
                >
                  已下载
                </span>
              </div>
              <p class="text-xs text-white/40 mt-1 truncate">{{ item.category_name || 'WNACG' }}</p>
            </div>
            <a
              :href="item.url"
              target="_blank"
              rel="noreferrer"
              class="w-9 h-9 rounded-xl bg-white/5 border border-white/10 text-white/55 hover:text-accent flex items-center justify-center transition-all"
              title="打开原站"
              @click.stop
            >
              <ExternalLink :size="16" />
            </a>
          </label>
        </div>
      </aside>
    </div>

    <MediaDetail
      v-if="selectedLocalMedia"
      :initial-media="selectedLocalMedia"
      :all-media="localMangaList"
      @close="closeLocalMedia"
      @updated="updateLocalMediaInList"
      @navigate="selectedLocalMedia = $event"
    />
  </section>
</template>
