<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import axios from 'axios'
import {
  CheckSquare,
  ChevronLeft,
  ChevronRight,
  Download,
  ExternalLink,
  Headphones,
  RefreshCw,
  Search,
  ShieldCheck,
  Square,
  X,
} from 'lucide-vue-next'
import { API_BASE_URL, authUrl } from '../../config'
import type { ExternalFavoriteItem, ExternalFavoriteSource, Media } from '../../types'
import MediaDetail from '../MediaDetail.vue'
import ThemeSelect from '../ThemeSelect.vue'
import { asmrDownloadStore } from '../../stores/asmrDownloadStore'

const AUDIO_FORMAT_OPTIONS: { value: 'all' | 'no_wav' | 'mp3_only'; label: string }[] = [
  { value: 'all', label: '全部格式' },
  { value: 'no_wav', label: '跳过 WAV（推荐）' },
  { value: 'mp3_only', label: '仅 MP3' },
]
const AUDIO_VERSION_OPTIONS: { value: 'all' | 'no_se' | 'se_only'; label: string }[] = [
  { value: 'all', label: '全部版本' },
  { value: 'no_se', label: '仅无 SE / 无背景声' },
  { value: 'se_only', label: '仅有 SE / 有背景声' },
]

// P2: sync the user's "喜欢" playlist, download works to the local library as
// media_type="audio", and open downloaded ones in the audio player.

// URL-shaped config (api base, mirror list, playlist URL) is persisted both
// to the backend source row (via persistSourceSettings -> PATCH) and to
// localStorage. The localStorage copy is the fallback when there is no source
// row yet — e.g. first time the user opens the panel, or after they nuked
// the source — so an F5 doesn't wipe what they just typed.
const URLS_KEY = 'he-manager:asmr-urls'
const DEFAULT_API_BASE = 'https://api.asmr-200.com'
const loadStoredUrls = (): { apiBase: string; apiMirrors: string; playlistUrl: string } => {
  try {
    const raw = localStorage.getItem(URLS_KEY)
    if (!raw) return { apiBase: DEFAULT_API_BASE, apiMirrors: '', playlistUrl: '' }
    const data = JSON.parse(raw)
    return {
      apiBase: typeof data?.apiBase === 'string' && data.apiBase ? data.apiBase : DEFAULT_API_BASE,
      apiMirrors: typeof data?.apiMirrors === 'string' ? data.apiMirrors : '',
      playlistUrl: typeof data?.playlistUrl === 'string' ? data.playlistUrl : '',
    }
  } catch {
    return { apiBase: DEFAULT_API_BASE, apiMirrors: '', playlistUrl: '' }
  }
}
const initialUrls = loadStoredUrls()
const apiBase = ref(initialUrls.apiBase)
const apiMirrors = ref(initialUrls.apiMirrors)
interface MirrorPing { base: string; ok: boolean; latency_ms: number | null; error: string | null }
const mirrorPings = ref<MirrorPing[]>([])
const pinging = ref(false)
const playlistUrl = ref(initialUrls.playlistUrl)
const persistUrls = () => {
  try {
    localStorage.setItem(URLS_KEY, JSON.stringify({
      apiBase: apiBase.value,
      apiMirrors: apiMirrors.value,
      playlistUrl: playlistUrl.value,
    }))
  } catch { /* quota / private-mode — ignore */ }
}

// Keep only the username in localStorage. The password travels once to
// /external/asmr/sync to mint a bearer token, then stays out of browser storage.
const CREDENTIALS_KEY = 'he-manager:asmr-credentials'
const loadStoredCredentials = (): { username: string; password: string } => {
  try {
    const raw = localStorage.getItem(CREDENTIALS_KEY)
    if (!raw) return { username: '', password: '' }
    const data = JSON.parse(raw)
    return {
      username: typeof data?.username === 'string' ? data.username : '',
      password: '',
    }
  } catch {
    return { username: '', password: '' }
  }
}
const initialCreds = loadStoredCredentials()
const username = ref(initialCreds.username)
const password = ref(initialCreds.password)
const persistCredentials = () => {
  try {
    localStorage.setItem(CREDENTIALS_KEY, JSON.stringify({
      username: username.value,
    }))
  } catch { /* quota / private-mode — ignore */ }
}
const clearStoredCredentials = () => {
  username.value = ''
  password.value = ''
  try { localStorage.removeItem(CREDENTIALS_KEY) } catch { /* ignore */ }
}
const pageLimit = ref(5)
const audioFormatFilter = ref<'all' | 'no_wav' | 'mp3_only'>('all')
const audioVersionFilter = ref<'all' | 'no_se' | 'se_only'>('all')
const downloadRootPath = ref('')
const searchQuery = ref('')
const loading = ref(false)
const syncing = ref(false)
const errorMessage = ref('')
const items = ref<ExternalFavoriteItem[]>([])
const sources = ref<ExternalFavoriteSource[]>([])
const activeSourceId = ref<number | null>(null)
const pageSize = 15
const currentPage = ref(1)
const downloadPanelOpen = ref(false)
const selectedDownloadIds = ref<Set<number>>(new Set())
const localAudioList = ref<Media[]>([])
const selectedLocalMedia = ref<Media | null>(null)

const settingsStatus = ref<'idle' | 'saving' | 'saved'>('idle')
// True while we're writing form fields FROM a source (initial load / source
// switch / post-sync). The auto-save watcher must ignore those writes so it
// doesn't PATCH the value straight back and fight the load.
let hydrating = false
let saveTimer: ReturnType<typeof setTimeout> | undefined

const downloadJob = asmrDownloadStore.job
const downloadInProgress = asmrDownloadStore.inProgress

const activeSiteSources = computed(() => sources.value.filter(source => source.source_type === 'asmr'))
const activeSource = computed(() =>
  activeSiteSources.value.find(source => source.id === activeSourceId.value) || activeSiteSources.value[0] || null,
)

const filteredItems = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) return items.value
  return items.value.filter(item =>
    item.title.toLowerCase().includes(keyword) || (item.category_name || '').toLowerCase().includes(keyword),
  )
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredItems.value.length / pageSize)))
const pagedItems = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredItems.value.slice(start, start + pageSize)
})
const pageStart = computed(() => (filteredItems.value.length === 0 ? 0 : (currentPage.value - 1) * pageSize + 1))
const pageEnd = computed(() => Math.min(currentPage.value * pageSize, filteredItems.value.length))

const downloadableItems = computed(() => filteredItems.value.filter(item => !item.local_media_id))
const selectedDownloadItems = computed(() =>
  items.value.filter(item => selectedDownloadIds.value.has(item.id) && !item.local_media_id),
)
const allDownloadableSelected = computed(() =>
  downloadableItems.value.length > 0 && downloadableItems.value.every(item => selectedDownloadIds.value.has(item.id)),
)

const statusText = computed(() => {
  if (!activeSource.value) return '未同步'
  if (activeSource.value.status === 'ok') return '已同步'
  if (activeSource.value.status === 'syncing') return '同步中'
  if (activeSource.value.status === 'error') return '同步失败'
  return '待同步'
})

const formatTime = (value: string | null) => (value ? new Date(value).toLocaleString() : '-')
const coverSrc = (item: ExternalFavoriteItem) => authUrl(`${API_BASE_URL}/external/favorites/${item.id}/cover`)

const goToPage = (page: number) => {
  currentPage.value = Math.min(Math.max(page, 1), totalPages.value)
}

const hydrateFromSource = (s: ExternalFavoriteSource | null | undefined) => {
  if (!s) return
  hydrating = true
  if (s.favorites_url) apiBase.value = s.favorites_url
  apiMirrors.value = s.api_mirrors || ''
  audioFormatFilter.value = (s.audio_format_filter as typeof audioFormatFilter.value) || 'all'
  audioVersionFilter.value = (s.audio_version_filter as typeof audioVersionFilter.value) || 'all'
  playlistUrl.value = s.playlist_url || ''
  downloadRootPath.value = s.download_root_path || ''
  // Watchers flush before nextTick, so hydrating is still true when they run.
  nextTick(() => { hydrating = false })
}

// Persist the format / SE-version / playlist-URL prefs on change, no button
// and no full re-sync. Debounced so typing in the playlist field doesn't
// PATCH on every keystroke.
const persistSourceSettings = () => {
  if (hydrating || !activeSourceId.value) return
  if (saveTimer) clearTimeout(saveTimer)
  settingsStatus.value = 'saving'
  saveTimer = setTimeout(async () => {
    try {
      const res = await axios.patch(`${API_BASE_URL}/external/sources/${activeSourceId.value}`, {
        audio_format_filter: audioFormatFilter.value,
        audio_version_filter: audioVersionFilter.value,
        playlist_url: playlistUrl.value.trim() || null,
      })
      const updated = res.data as ExternalFavoriteSource
      sources.value = sources.value.map(s => (s.id === updated.id ? updated : s))
      settingsStatus.value = 'saved'
      setTimeout(() => { if (settingsStatus.value === 'saved') settingsStatus.value = 'idle' }, 2000)
    } catch (err: any) {
      settingsStatus.value = 'idle'
      errorMessage.value = err.response?.data?.detail || '保存设置失败'
    }
  }, 600)
}

const fetchSources = async () => {
  const res = await axios.get(`${API_BASE_URL}/external/sources`)
  sources.value = res.data
  if (!activeSourceId.value && activeSiteSources.value.length > 0) {
    const first = activeSiteSources.value[0]
    activeSourceId.value = first.id
    hydrateFromSource(first)
  }
}

const pingMirrors = async () => {
  pinging.value = true
  mirrorPings.value = []
  try {
    const res = await axios.post(`${API_BASE_URL}/external/asmr/mirrors/ping`, {
      api_base: apiBase.value.trim() || undefined,
      api_mirrors: apiMirrors.value.trim() || undefined,
    })
    mirrorPings.value = res.data.results || []
  } catch (err) {
    console.error('Failed to ping ASMR mirrors:', err)
    errorMessage.value = '镜像探活失败'
  } finally {
    pinging.value = false
  }
}

const fetchItems = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await axios.get(`${API_BASE_URL}/external/favorites`, {
      params: { source_type: 'asmr', source_id: activeSourceId.value || undefined },
    })
    items.value = res.data
    goToPage(1)
  } catch (err) {
    console.error('Failed to fetch ASMR favorites:', err)
    errorMessage.value = '读取 ASMR 收藏失败'
  } finally {
    loading.value = false
  }
}

const fetchLocalAudioList = async () => {
  const res = await axios.get(`${API_BASE_URL}/media`, { params: { media_type: 'audio' } })
  localAudioList.value = res.data
  return localAudioList.value
}

const syncAsmr = async () => {
  if (!playlistUrl.value.trim() || !username.value.trim() || !password.value) {
    errorMessage.value = '请填写「喜欢」播放列表地址 + 你本人的 asmr.one 账号密码'
    return
  }
  syncing.value = true
  errorMessage.value = ''
  try {
    const res = await axios.post(`${API_BASE_URL}/external/asmr/sync`, {
      source_id: activeSourceId.value || undefined,
      name: 'ASMR',
      api_base: apiBase.value.trim(),
      api_mirrors: apiMirrors.value.trim() || undefined,
      audio_format_filter: audioFormatFilter.value,
      audio_version_filter: audioVersionFilter.value,
      playlist_url: playlistUrl.value.trim() || undefined,
      username: username.value.trim() || undefined,
      password: password.value || undefined,
      page_limit: pageLimit.value,
    })
    const source = res.data.source as ExternalFavoriteSource
    activeSourceId.value = source.id
    hydrateFromSource(source)
    items.value = res.data.items
    goToPage(1)
    password.value = ''
    persistCredentials()
    await fetchSources()
  } catch (err: any) {
    console.error('Failed to sync ASMR favorites:', err)
    errorMessage.value = err.response?.data?.detail || '同步 ASMR 收藏失败'
  } finally {
    syncing.value = false
  }
}

const selectSource = async (source: ExternalFavoriteSource) => {
  activeSourceId.value = source.id
  hydrateFromSource(source)
  await fetchItems()
}

const saveDownloadRootPath = async () => {
  if (!activeSourceId.value) return true
  const trimmed = downloadRootPath.value.trim()
  if (!trimmed) {
    errorMessage.value = '请先设置下载位置'
    return false
  }
  try {
    const res = await axios.patch(`${API_BASE_URL}/external/sources/${activeSourceId.value}`, {
      download_root_path: trimmed,
    })
    const updated = res.data as ExternalFavoriteSource
    sources.value = sources.value.map(s => (s.id === updated.id ? updated : s))
    downloadRootPath.value = updated.download_root_path || ''
    return true
  } catch (err: any) {
    errorMessage.value = err.response?.data?.detail || '保存路径失败'
    return false
  }
}

const toggleSelect = (item: ExternalFavoriteItem) => {
  if (item.local_media_id) return
  const next = new Set(selectedDownloadIds.value)
  next.has(item.id) ? next.delete(item.id) : next.add(item.id)
  selectedDownloadIds.value = next
}

const toggleSelectAll = () => {
  const next = new Set(selectedDownloadIds.value)
  if (allDownloadableSelected.value) downloadableItems.value.forEach(i => next.delete(i.id))
  else downloadableItems.value.forEach(i => next.add(i.id))
  selectedDownloadIds.value = next
}

const startDownload = async () => {
  if (selectedDownloadItems.value.length === 0 || downloadInProgress.value) return
  if (!downloadRootPath.value.trim()) {
    downloadPanelOpen.value = true
    errorMessage.value = '请先设置下载位置'
    return
  }
  errorMessage.value = ''
  asmrDownloadStore.clearError()
  if (!(await saveDownloadRootPath())) return
  try {
    await asmrDownloadStore.startDownload(
      selectedDownloadItems.value.map(i => i.id),
      downloadRootPath.value.trim(),
    )
  } catch (err: any) {
    errorMessage.value = err.response?.data?.detail || '启动下载失败'
  }
}

const openLocalMedia = async (mediaId: number) => {
  let media = localAudioList.value.find(m => m.id === mediaId)
  if (!media) {
    const list = await fetchLocalAudioList()
    media = list.find(m => m.id === mediaId)
  }
  if (!media) {
    const res = await axios.get(`${API_BASE_URL}/media/${mediaId}`)
    media = res.data as Media
    localAudioList.value = [media, ...localAudioList.value.filter(m => m.id !== mediaId)]
  }
  if (media) selectedLocalMedia.value = media
}

const openItem = async (item: ExternalFavoriteItem) => {
  if (item.local_media_id) {
    try {
      await openLocalMedia(item.local_media_id)
      return
    } catch (err) {
      console.error('Failed to open local audio:', err)
    }
  }
  window.open(item.url, '_blank', 'noreferrer')
}

const updateLocalMediaInList = (media: Media) => {
  const idx = localAudioList.value.findIndex(m => m.id === media.id)
  if (idx >= 0) localAudioList.value[idx] = media
  else localAudioList.value = [media, ...localAudioList.value]
  selectedLocalMedia.value = media
}

let unsubscribeCompleted: (() => void) | null = null

onMounted(async () => {
  asmrDownloadStore.ensureResumed()
  unsubscribeCompleted = asmrDownloadStore.onCompleted(async () => {
    await fetchItems()
    await fetchLocalAudioList()
  })
  await fetchSources()
  await fetchItems()
})

onUnmounted(() => {
  if (unsubscribeCompleted) { unsubscribeCompleted(); unsubscribeCompleted = null }
})

watch([audioFormatFilter, audioVersionFilter, playlistUrl], persistSourceSettings)
// Mirror the URL-shaped fields into localStorage on every change. No
// hydrating guard here — when hydrateFromSource() pulls values from the
// freshly synced source row, we *want* localStorage to track that copy.
watch([apiBase, apiMirrors, playlistUrl], persistUrls)
watch(username, persistCredentials)
watch(() => asmrDownloadStore.errorMessage.value, msg => { if (msg) errorMessage.value = msg })
watch([searchQuery, filteredItems], () => {
  if (currentPage.value > totalPages.value) currentPage.value = totalPages.value
  if (searchQuery.value) currentPage.value = 1
})
</script>

<template>
  <section class="space-y-6">
    <div class="grid grid-cols-1 xl:grid-cols-[minmax(320px,420px),1fr] gap-6">
      <div class="bg-white/[0.04] border border-white/10 rounded-2xl p-5 space-y-4">
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-center gap-3 text-white">
            <div class="w-10 h-10 rounded-xl bg-accent/15 text-accent flex items-center justify-center border border-accent/20">
              <Headphones :size="20" />
            </div>
            <div>
              <h2 class="text-base font-black">ASMR · asmr.one</h2>
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
          <span class="text-xs font-bold text-white/55">API 地址（镜像被封时自动回退其它镜像）</span>
          <input
            v-model="apiBase"
            type="url"
            placeholder="https://api.asmr-200.com"
            class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
          />
        </label>

        <label class="block space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-xs font-bold text-white/55">镜像列表（每行一个，留空用内置默认）</span>
            <button
              type="button"
              @click="pingMirrors"
              :disabled="pinging"
              class="text-xs font-bold text-accent hover:text-accent/80 disabled:opacity-40 transition-colors"
            >{{ pinging ? '探活中…' : '探活' }}</button>
          </div>
          <textarea
            v-model="apiMirrors"
            rows="3"
            spellcheck="false"
            placeholder="https://api.asmr-200.com&#10;https://api.asmr.one&#10;https://api.asmr-100.com"
            class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 font-mono focus:outline-none focus:ring-2 focus:ring-accent/50 resize-y"
          ></textarea>
          <ul v-if="mirrorPings.length" class="space-y-1">
            <li
              v-for="p in mirrorPings"
              :key="p.base"
              class="flex items-center gap-2 text-xs"
            >
              <span :class="p.ok ? 'text-green-400' : 'text-red-400'">{{ p.ok ? '●' : '○' }}</span>
              <span class="text-white/65 truncate flex-1 font-mono">{{ p.base }}</span>
              <span v-if="p.ok" class="text-white/40 shrink-0">{{ p.latency_ms }}ms</span>
              <span v-else class="text-red-400/70 shrink-0">连不上</span>
            </li>
          </ul>
        </label>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/55">「喜欢」播放列表地址<span class="text-white/35 font-medium">（自动保存）</span></span>
          <input
            v-model="playlistUrl"
            type="text"
            placeholder="https://asmr.one/playlist?id=xxxxxxxx-xxxx-…"
            class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
          />
        </label>

        <div class="grid grid-cols-2 gap-3">
          <label class="block space-y-2">
            <span class="text-xs font-bold text-white/55">账号 <span class="text-red-300">*</span></span>
            <input
              v-model="username"
              type="text"
              autocomplete="off"
              placeholder="asmr.one 用户名"
              class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </label>
          <label class="block space-y-2">
            <span class="text-xs font-bold text-white/55">密码 <span class="text-red-300">*</span></span>
            <input
              v-model="password"
              type="password"
              autocomplete="off"
              placeholder="asmr.one 密码"
              class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </label>
        </div>
        <div class="flex items-center justify-between text-[11px] gap-2">
          <p class="text-white/35 flex-1">
            <span v-if="username" class="text-accent/85 font-bold">已记住用户名，密码不会保存在浏览器</span>
            <span v-else>密码只用于本次换取 token，不会保存在浏览器或后端。</span>
          </p>
          <button
            v-if="username || password"
            type="button"
            @click="clearStoredCredentials"
            class="text-white/45 hover:text-red-300 font-bold transition-colors shrink-0"
          >
            清除已保存
          </button>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <label class="block space-y-2">
            <span class="text-xs font-bold text-white/55">同步页数</span>
            <input
              v-model.number="pageLimit"
              type="number"
              min="1"
              max="50"
              class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </label>
          <label class="block space-y-2">
            <span class="text-xs font-bold text-white/55">下载位置</span>
            <input
              v-model="downloadRootPath"
              type="text"
              placeholder="例如 C:\Users\25768\Desktop\HE_Project\HE_manager\external_downloads"
              class="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </label>
        </div>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/55">下载格式（单作品 WAV 可达数 GB）</span>
          <ThemeSelect v-model="audioFormatFilter" :options="AUDIO_FORMAT_OPTIONS" />
        </label>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/55">SE 版本（按文件夹名启发式判断）</span>
          <ThemeSelect v-model="audioVersionFilter" :options="AUDIO_VERSION_OPTIONS" />
        </label>

        <p class="text-[11px] flex items-center gap-1.5 min-h-[1rem]">
          <span v-if="settingsStatus === 'saving'" class="text-accent">保存中…</span>
          <span v-else-if="settingsStatus === 'saved'" class="text-emerald-300">已自动保存</span>
          <span v-else class="text-white/35">下载格式 / SE 版本 / 播放列表地址改动后自动保存，刷新不会重置</span>
        </p>

        <button
          @click="syncAsmr"
          :disabled="syncing"
          class="w-full h-12 rounded-xl bg-accent text-white font-black flex items-center justify-center gap-2 hover:brightness-110 disabled:opacity-60 disabled:cursor-not-allowed transition-all"
        >
          <RefreshCw :size="18" :class="syncing ? 'animate-spin' : ''" />
          {{ syncing ? '同步中' : '同步收藏' }}
        </button>

        <p class="text-[11px] text-white/35 leading-snug">
          粘贴你的「喜欢」播放列表地址 + 你本人的 asmr.one 账号密码。该列表对外不可见，必须本人令牌才能读取（密码仅用于换令牌、不保存）。音频会下载到「下载位置」下的 audio 目录，下完即可在库内播放。
        </p>

        <p v-if="activeSource?.last_error" class="text-xs text-red-300 bg-red-400/10 border border-red-400/20 rounded-xl px-3 py-2">
          {{ activeSource.last_error }}
        </p>
      </div>

      <div class="min-h-[360px] space-y-4">
        <div class="bg-white/[0.04] border border-white/10 rounded-2xl px-3 py-3 flex flex-wrap items-center gap-3">
          <p class="text-[11px] font-bold text-accent bg-accent/10 px-2 py-0.5 rounded-full border border-accent/20 uppercase tracking-widest">
            {{ filteredItems.length }} WORKS
          </p>
          <div class="relative flex-1 min-w-[200px] group">
            <Search class="absolute left-4 top-1/2 -translate-y-1/2 text-white/30 group-focus-within:text-accent transition-colors" :size="16" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索标题或社团"
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

        <div v-if="downloadInProgress && downloadJob" class="bg-white/[0.04] border border-accent/25 rounded-2xl px-4 py-3 space-y-2">
          <div class="flex items-center justify-between text-xs text-white/65">
            <span class="truncate">下载中：{{ downloadJob.current_book_title || '准备中' }}</span>
            <span>{{ asmrDownloadStore.downloadedTracks.value }}/{{ asmrDownloadStore.totalTracks.value }} 轨</span>
          </div>
          <div class="h-1.5 rounded-full bg-white/10 overflow-hidden">
            <div class="h-full bg-accent transition-all" :style="{ width: `${asmrDownloadStore.progressPercent.value}%` }"></div>
          </div>
          <button
            v-if="asmrDownloadStore.canCancel.value"
            @click="asmrDownloadStore.cancelDownload()"
            class="text-[11px] text-red-300 hover:text-red-200"
          >
            取消下载
          </button>
        </div>

        <div v-if="errorMessage" class="bg-red-400/10 border border-red-400/20 text-red-200 rounded-xl px-4 py-3 text-sm">
          {{ errorMessage }}
        </div>

        <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5 gap-5">
          <div v-for="i in 10" :key="i" class="aspect-square bg-white/5 animate-pulse rounded-2xl border border-white/5"></div>
        </div>

        <div v-else-if="filteredItems.length > 0" class="space-y-5">
          <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5 gap-5">
            <button
              v-for="item in pagedItems"
              :key="item.id"
              type="button"
              @click="openItem(item)"
              class="group bg-white/[0.04] border border-white/10 rounded-2xl overflow-hidden hover:-translate-y-1 hover:border-accent/35 transition-all text-left"
            >
              <div class="aspect-square bg-black/30 overflow-hidden relative">
                <img
                  v-if="item.cover_url"
                  :src="coverSrc(item)"
                  :alt="item.title"
                  class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                />
                <div v-else class="w-full h-full flex items-center justify-center text-white/25">
                  <Headphones :size="34" />
                </div>
                <span
                  v-if="item.local_media_id"
                  class="absolute left-2 top-2 rounded-lg bg-emerald-400/90 px-2 py-1 text-[10px] font-black text-slate-950"
                >
                  已下载
                </span>
                <span v-else class="absolute left-2 top-2 rounded-lg bg-black/55 px-2 py-1 text-[10px] font-black text-white/85">
                  {{ item.external_id }}
                </span>
              </div>
              <div class="p-3 space-y-2">
                <h3 class="text-sm font-bold text-white line-clamp-2 leading-snug min-h-[2.6em]">{{ item.title }}</h3>
                <div class="flex items-center justify-between gap-2 text-xs text-white/40">
                  <span class="truncate">{{ item.category_name || 'asmr.one' }}</span>
                  <ExternalLink :size="14" class="shrink-0 text-white/35 group-hover:text-accent" />
                </div>
              </div>
            </button>
          </div>

          <div class="flex flex-wrap items-center justify-between gap-3 bg-white/[0.04] border border-white/10 rounded-2xl px-4 py-3">
            <p class="text-xs text-white/45">{{ pageStart }}-{{ pageEnd }} / {{ filteredItems.length }}</p>
            <div class="flex items-center gap-2">
              <button
                @click="goToPage(currentPage - 1)"
                :disabled="currentPage <= 1"
                class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white disabled:opacity-35 disabled:cursor-not-allowed flex items-center justify-center transition-all"
                title="上一页"
              >
                <ChevronLeft :size="18" />
              </button>
              <span class="text-sm font-bold text-white/70 px-2">第 {{ currentPage }} / {{ totalPages }} 页</span>
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
          <Headphones :size="34" class="mb-4" />
          <p class="text-lg font-bold text-white/45">还没有 ASMR 收藏</p>
          <p class="text-sm mt-2">填好账号后点「同步收藏」</p>
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
            <p class="text-xs text-white/45 mt-1">已选 {{ selectedDownloadItems.length }} 个 · 当前列表 {{ filteredItems.length }} 个</p>
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
          <p class="text-[11px] text-white/35">音频保存到该路径下的 audio 目录，单作品可能数百 MB~数 GB。</p>
        </div>

        <div class="px-5 py-3 border-b border-white/10 flex flex-wrap items-center gap-2">
          <button
            @click="toggleSelectAll"
            :disabled="downloadableItems.length === 0"
            class="h-10 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white disabled:opacity-35 flex items-center gap-2 text-xs font-bold transition-all"
          >
            <CheckSquare v-if="allDownloadableSelected" :size="16" />
            <Square v-else :size="16" />
            全选未下载
          </button>
          <button
            @click="selectedDownloadIds = new Set()"
            :disabled="selectedDownloadItems.length === 0"
            class="h-10 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white disabled:opacity-35 disabled:cursor-not-allowed text-xs font-bold transition-all"
          >
            清空选择
          </button>
          <button
            @click="startDownload"
            :disabled="selectedDownloadItems.length === 0 || !downloadRootPath.trim() || downloadInProgress"
            class="h-10 px-4 rounded-xl bg-accent text-white disabled:opacity-45 disabled:cursor-not-allowed flex items-center gap-2 text-xs font-black transition-all"
          >
            <Download :size="16" :class="downloadInProgress ? 'animate-pulse' : ''" />
            {{ downloadInProgress ? '下载中' : '开始下载' }}
          </button>
        </div>

        <div v-if="downloadInProgress && downloadJob" class="px-5 py-3 border-b border-white/10 bg-black/15 space-y-2">
          <div class="flex items-center justify-between text-[11px] text-white/55">
            <span class="truncate">{{ downloadJob.current_book_title || '准备中' }}</span>
            <span>{{ asmrDownloadStore.downloadedTracks.value }}/{{ asmrDownloadStore.totalTracks.value }} 轨 · {{ asmrDownloadStore.progressPercent.value }}%</span>
          </div>
          <div class="h-1.5 rounded-full bg-white/10 overflow-hidden">
            <div class="h-full bg-accent transition-all" :style="{ width: `${asmrDownloadStore.progressPercent.value}%` }"></div>
          </div>
        </div>
        <div v-if="downloadJob?.results?.length" class="px-5 py-2 border-b border-white/10 bg-black/15 max-h-24 overflow-y-auto space-y-1">
          <p
            v-for="result in downloadJob.results.slice(-5)"
            :key="`${result.item_id}-${result.status}`"
            :class="result.status === 'completed' ? 'text-emerald-300' : result.status === 'canceled' ? 'text-amber-300' : 'text-red-300'"
            class="text-[11px] truncate"
          >
            {{ result.status === 'completed' ? '完成' : result.status === 'canceled' ? '已取消' : '失败' }} ·
            {{ result.title || result.item_id }}{{ result.error ? ` · ${result.error}` : '' }}
          </p>
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
              @change="toggleSelect(item)"
            />
            <div class="w-14 h-14 rounded-lg bg-black/30 overflow-hidden border border-white/10">
              <img v-if="item.cover_url" :src="coverSrc(item)" :alt="item.title" class="w-full h-full object-cover" />
              <div v-else class="w-full h-full flex items-center justify-center text-white/25"><Headphones :size="20" /></div>
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
              <p class="text-xs text-white/40 mt-1 truncate">{{ item.external_id }} · {{ item.category_name || 'asmr.one' }}</p>
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
      :all-media="localAudioList"
      @close="selectedLocalMedia = null"
      @updated="updateLocalMediaInList"
      @navigate="selectedLocalMedia = $event"
    />
  </section>
</template>
