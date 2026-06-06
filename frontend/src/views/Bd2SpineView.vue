<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { AlertCircle, Box, Download, Film, Loader2, Play, RefreshCw, Sparkles, Upload } from 'lucide-vue-next'
import type { SpinePlayer as SpinePlayerInstance, SpinePlayerConfig } from '@esotericsoftware/spine-player'
import '@esotericsoftware/spine-player/dist/spine-player.css'
import { API_BASE_URL } from '../config'
import { authState } from '../auth'
import type { Bd2SpineAsset, Bd2SpineListResponse } from '../types'

const STORAGE_KEY = 'he_manager_bd2_target_dir'
const DEFAULT_TARGET_DIR = 'E:\\hhh\\BD2'

const assets = ref<Bd2SpineAsset[]>([])
const selectedId = ref('')
const selectedKind = ref<'char' | 'cutscene' | 'illust'>('char')
const hideEffectLayers = ref(false)
const loading = ref(true)

// BD2 download state
type DownloadStatus = 'idle' | 'checking' | 'cloning' | 'pulling' | 'done' | 'error' | 'cancelled'
const downloadStatus = ref<DownloadStatus>('idle')
const downloadError = ref('')
const downloadStep = ref('')
const downloadMode = ref<'' | 'clone' | 'pull'>('')
const downloadMb = ref(0)
const downloadSpeed = ref(0)   // MiB/s
const downloadPct = ref(0)
const downloadFemaleDirs = ref(0)

let _downloadPollTimer: ReturnType<typeof setInterval> | null = null
let _pollFailCount = 0
const POLL_FAIL_LIMIT = 5

const targetDir = ref(localStorage.getItem(STORAGE_KEY) || DEFAULT_TARGET_DIR)
watch(targetDir, (v) => localStorage.setItem(STORAGE_KEY, v))

const isAdmin = computed(() => Boolean(authState.user?.is_admin))
const isAuthed = computed(() => Boolean(authState.user))

const cancelDownload = async () => {
  try {
    await axios.post(`${API_BASE_URL}/bd2/spine/download/cancel`)
  } catch { /* ignore */ }
  if (_downloadPollTimer) { clearInterval(_downloadPollTimer); _downloadPollTimer = null }
  downloadStatus.value = 'cancelled'
  downloadStep.value = ''
}

const startDownload = async () => {
  downloadStatus.value = 'checking'
  downloadError.value = ''
  downloadStep.value = ''
  downloadMb.value = 0
  downloadSpeed.value = 0
  downloadPct.value = 0
  downloadMode.value = ''
  _pollFailCount = 0
  try {
    await axios.post(`${API_BASE_URL}/bd2/spine/download`, {
      target_dir: targetDir.value,
    })
    // Poll for completion
    if (_downloadPollTimer) clearInterval(_downloadPollTimer)
    _downloadPollTimer = setInterval(async () => {
      try {
        const res = await axios.get(`${API_BASE_URL}/bd2/spine/download/status`)
        const st = (res.data.status as string) || 'idle'
        downloadStep.value = (res.data.step as string) || ''
        downloadMb.value = Number(res.data.mb) || 0
        downloadSpeed.value = Number(res.data.speed_mb_s) || 0
        downloadPct.value = Number(res.data.pct) || 0
        downloadMode.value = (res.data.mode as 'clone' | 'pull') || ''
        if (res.data.female_dirs !== undefined) {
          downloadFemaleDirs.value = Number(res.data.female_dirs) || 0
        }
        if (st === 'done') {
          downloadStatus.value = 'done'
          if (_downloadPollTimer) { clearInterval(_downloadPollTimer); _downloadPollTimer = null }
          // Auto-reload the asset list so newly fetched assets show up
          // without forcing the user to hit "Refresh".
          await loadAssets()
        } else if (st === 'cancelled') {
          downloadStatus.value = 'cancelled'
          if (_downloadPollTimer) { clearInterval(_downloadPollTimer); _downloadPollTimer = null }
        } else if (st === 'error') {
          downloadStatus.value = 'error'
          downloadError.value = (res.data.error as string) || 'Unknown'
          if (_downloadPollTimer) { clearInterval(_downloadPollTimer); _downloadPollTimer = null }
        } else {
          // checking / cloning / pulling
          downloadStatus.value = st as DownloadStatus
        }
        _pollFailCount = 0
      } catch {
        _pollFailCount += 1
        if (_pollFailCount >= POLL_FAIL_LIMIT) {
          downloadStatus.value = 'error'
          downloadError.value = `与后端通信失败（${_pollFailCount} 次）`
          if (_downloadPollTimer) { clearInterval(_downloadPollTimer); _downloadPollTimer = null }
        }
      }
    }, 2000)
  } catch (err: unknown) {
    const e = err as { response?: { status?: number }, message?: string }
    if (e?.response?.status === 403) {
      downloadError.value = '需要管理员权限，请用 admin 账号登录'
    } else if (e?.response?.status === 401) {
      downloadError.value = '请先登录'
    } else {
      downloadError.value = e?.message || 'Download failed'
    }
    downloadStatus.value = 'error'
  }
}

onBeforeUnmount(() => {
  if (_downloadPollTimer) clearInterval(_downloadPollTimer)
})
const playerLoading = ref(false)
const error = ref('')
const playerError = ref('')
const sourceRoot = ref('')
const animationNames = ref<string[]>([])
const skinNames = ref<string[]>([])
const playerHost = ref<HTMLDivElement | null>(null)
let player: SpinePlayerInstance | null = null
const hiddenAttachments = new WeakMap<SpineSlot, unknown>()

type SpineSlot = {
  data?: { name?: string }
  attachment?: unknown
  setAttachment?: (attachment: unknown) => void
}

type RuntimeSpinePlayer = SpinePlayerInstance & {
  skeleton?: {
    slots?: SpineSlot[]
    data?: {
      animations?: { name: string }[]
      skins?: { name: string }[]
    }
  }
}

const effectLayerPattern = /(lighting|effect|glow|shine|spark|particle|flash|aura|blur|_light\b|\blight_)/i

const filteredAssets = computed(() => assets.value.filter((asset) => asset.kind === selectedKind.value))
const selectedAsset = computed(() => assets.value.find((asset) => asset.id === selectedId.value) || null)
const charAssetCount = computed(() => assets.value.filter((asset) => asset.kind === 'char').length)
const cutsceneAssetCount = computed(() => assets.value.filter((asset) => asset.kind === 'cutscene').length)
const illustAssetCount = computed(() => assets.value.filter((asset) => asset.kind === 'illust').length)

const assetUrl = (path: string) => `${API_BASE_URL}${path}`

// Show "downloading" if a git process is actually running.
const isDownloading = computed(
  () => downloadStatus.value === 'checking'
    || downloadStatus.value === 'cloning'
    || downloadStatus.value === 'pulling',
)

const buttonLabel = computed(() => {
  if (isDownloading.value) {
    // Empty during the brief `checking` phase before the mode is known.
    if (!downloadMode.value) return '准备中…'
    return downloadMode.value === 'clone' ? '首次拉取中…' : '更新中…'
  }
  if (downloadStatus.value === 'done') return '更新'
  if (downloadStatus.value === 'error') return '重试'
  // idle / cancelled: pick based on whether the target already has a clone.
  // Backend's list endpoint exposes `root` when it resolves a checkout,
  // which only happens once .git exists.  Use the local target_dir
  // presence as a cheap hint.
  return '下载'
})

const stepLabel = computed(() => {
  switch (downloadStep.value) {
    case 'checking': return '检测已有仓库…'
    case 'cloning': return '首次拉取（sparse-checkout 走代理）…'
    case 'fetching': return '拉取远端增量…'
    case 'merging': return '重置到 origin/master…'
    case 'sparse_checkout': return '配置 sparse-checkout…'
    case 'checking_out': return 'checkout 工作区…'
    case 'checking_out_all': return 'fallback 全量 checkout…'
    default: return downloadStep.value
  }
})

const pctForBar = computed(() => Math.max(0, Math.min(100, downloadPct.value)))

const etaText = computed(() => {
  if (!isDownloading.value || downloadSpeed.value <= 0) return ''
  // We don't track total bytes, but `pct` and the live `mb` give a rough
  // estimate:  pct done = mb_done / mb_total, so  remaining = mb_done * (100-pct) / pct
  if (downloadPct.value <= 0 || downloadPct.value >= 100) return ''
  const remainingMb = downloadMb.value * (100 - downloadPct.value) / downloadPct.value
  const etaSec = remainingMb / downloadSpeed.value
  if (!isFinite(etaSec) || etaSec <= 0) return ''
  if (etaSec < 60) return `≈ ${Math.round(etaSec)}s 剩余`
  return `≈ ${Math.round(etaSec / 60)}min 剩余`
})

const setSlotAttachment = (slot: SpineSlot, attachment: unknown) => {
  if (typeof slot.setAttachment === 'function') {
    slot.setAttachment(attachment)
  } else {
    slot.attachment = attachment
  }
}

const attachmentName = (attachment: unknown) => {
  if (!attachment || typeof attachment !== 'object') return ''
  return String((attachment as { name?: string }).name || '')
}

const applyEffectLayerFilter = (targetPlayer = player) => {
  const runtimePlayer = targetPlayer as RuntimeSpinePlayer | null
  const slots = runtimePlayer?.skeleton?.slots || []
  for (const slot of slots) {
    const currentAttachment = slot.attachment
    const token = `${slot.data?.name || ''} ${attachmentName(currentAttachment)}`
    const shouldHide = hideEffectLayers.value && Boolean(currentAttachment) && effectLayerPattern.test(token)
    if (shouldHide) {
      if (!hiddenAttachments.has(slot)) {
        hiddenAttachments.set(slot, currentAttachment)
      }
      setSlotAttachment(slot, null)
    } else if (!hideEffectLayers.value && hiddenAttachments.has(slot)) {
      setSlotAttachment(slot, hiddenAttachments.get(slot) || null)
      hiddenAttachments.delete(slot)
    }
  }
}

const disposePlayer = () => {
  if (player) {
    player.dispose()
    player = null
  }
  animationNames.value = []
  skinNames.value = []
}

const loadAssets = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await axios.get<Bd2SpineListResponse>(`${API_BASE_URL}/bd2/spine`)
    assets.value = res.data.assets || []
    sourceRoot.value = res.data.root || ''
    if (!filteredAssets.value.length && selectedKind.value !== 'char' && charAssetCount.value > 0) {
      selectedKind.value = 'char'
    }
    if (!selectedId.value || !filteredAssets.value.some((asset) => asset.id === selectedId.value)) {
      selectedId.value = filteredAssets.value[0]?.id || ''
    }
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '加载失败'
    error.value = message
    assets.value = []
    selectedId.value = ''
  } finally {
    loading.value = false
  }
}

const mountPlayer = async () => {
  const asset = selectedAsset.value
  disposePlayer()
  playerError.value = ''
  if (!asset) return
  playerLoading.value = true
  await nextTick()
  if (!playerHost.value) {
    playerLoading.value = false
    return
  }

  const config = {
    binaryUrl: assetUrl(asset.skeleton_url),
    atlasUrl: assetUrl(asset.atlas_url),
    showControls: true,
    showLoading: true,
    alpha: true,
    premultipliedAlpha: false,
    backgroundColor: '00000000',
    fullScreenBackgroundColor: '101216',
    viewport: {
      x: 0,
      y: 0,
      width: 0,
      height: 0,
      padLeft: '8%',
      padRight: '8%',
      padTop: '8%',
      padBottom: '8%',
      debugRender: false,
      transitionTime: 0.2,
      animations: {},
    },
    success: (nextPlayer: SpinePlayerInstance) => {
      const runtimePlayer = nextPlayer as RuntimeSpinePlayer
      playerLoading.value = false
      animationNames.value = runtimePlayer.skeleton?.data?.animations?.map((animation) => animation.name) || []
      skinNames.value = runtimePlayer.skeleton?.data?.skins?.map((skin) => skin.name) || []
      applyEffectLayerFilter(nextPlayer)
    },
    error: (_nextPlayer: SpinePlayerInstance, message: string) => {
      playerLoading.value = false
      playerError.value = message || 'Spine 播放器加载失败'
    },
    frame: (nextPlayer: SpinePlayerInstance) => {
      applyEffectLayerFilter(nextPlayer)
    },
  } as unknown as SpinePlayerConfig

  try {
    const { SpinePlayer } = await import('@esotericsoftware/spine-player')
    player = new SpinePlayer(playerHost.value, config)
  } catch (err: unknown) {
    playerLoading.value = false
    playerError.value = err instanceof Error ? err.message : 'Spine 播放器初始化失败'
  }
}

onMounted(loadAssets)
onBeforeUnmount(disposePlayer)
watch(selectedAsset, mountPlayer)
watch(selectedKind, () => {
  if (!filteredAssets.value.some((asset) => asset.id === selectedId.value)) {
    selectedId.value = filteredAssets.value[0]?.id || ''
  }
})
watch(hideEffectLayers, () => applyEffectLayerFilter())
</script>

<template>
  <div class="min-h-full p-6 lg:p-8 space-y-6">
    <header class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <p class="text-xs uppercase tracking-[0.22em] text-white/35 font-bold">Brown Dust 2</p>
        <h2 class="text-3xl font-black text-white mt-2">Spine 预览</h2>
        <p class="text-sm text-white/45 mt-2 max-w-2xl">
          测试 BD2 的 .skel / .atlas / texture 三件套。这里播放的是 Spine 动画数据，不是 Cubism Live2D。
        </p>
      </div>
      <div class="flex flex-col gap-2 items-stretch lg:items-end min-w-0 lg:max-w-[520px] w-full">
        <div class="flex items-center gap-2">
          <input
            v-model="targetDir"
            type="text"
            spellcheck="false"
            class="flex-1 min-w-0 rounded-lg border border-white/10 bg-white/[0.04] px-3 py-2 text-xs font-mono text-white/85 focus:border-white/30 focus:outline-none"
            placeholder="git 仓库目标目录（首次会自动 clone，已有则 pull）"
            :disabled="isDownloading"
          />
          <button
            class="inline-flex items-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-bold transition shrink-0"
            :class="isDownloading
              ? 'border-amber-300/35 bg-amber-300/12 text-amber-100 cursor-wait'
              : downloadStatus === 'error'
              ? 'border-red-400/25 bg-red-500/10 text-red-100'
              : downloadStatus === 'done'
              ? 'border-emerald-300/35 bg-emerald-500/12 text-emerald-100'
              : 'border-white/10 bg-white/5 text-white/75 hover:bg-white/10 hover:text-white'
              + (isAuthed && !isAdmin ? ' opacity-50 cursor-not-allowed' : '')"
            :disabled="isAuthed && !isAdmin"
            :title="isAuthed && !isAdmin ? '需要管理员权限' : ''"
            @click="isDownloading ? cancelDownload() : startDownload()"
          >
            <Loader2 v-if="isDownloading" :size="16" class="animate-spin" />
            <Upload v-else-if="downloadStatus === 'done'" :size="16" />
            <Download v-else :size="16" />
            {{ buttonLabel }}
          </button>
        </div>
        <div v-if="isDownloading" class="space-y-1.5">
          <div class="flex items-center justify-between text-[11px] font-bold text-white/55">
            <span class="truncate">{{ stepLabel }}</span>
            <span class="tabular-nums text-white/75">
              {{ pctForBar }}%
              <span class="text-white/30 mx-1">·</span>
              {{ downloadMb.toFixed(1) }} MB
              <span v-if="downloadSpeed > 0" class="text-white/30 mx-1">·</span>
              <span v-if="downloadSpeed > 0">{{ downloadSpeed.toFixed(1) }} MB/s</span>
              <span class="text-white/30 mx-1">·</span>
              <span class="text-white/45">{{ etaText || '估算中' }}</span>
            </span>
          </div>
          <div class="h-1.5 w-full rounded-full bg-white/[0.06] overflow-hidden">
            <div
              class="h-full bg-gradient-to-r from-amber-300/70 to-amber-200/90 transition-all duration-300"
              :style="{ width: pctForBar + '%' }"
            />
          </div>
          <div v-if="downloadFemaleDirs > 0" class="text-[10px] text-white/35 font-bold">
            目标：{{ downloadFemaleDirs }} 个女性角色目录
          </div>
        </div>
        <div v-else-if="downloadStatus === 'done'" class="text-[11px] font-bold text-emerald-300/80">
          ✓ 已同步（{{ downloadMode === 'pull' ? '增量' : '首次' }}），点击「更新」再次拉取
        </div>
        <div v-else-if="downloadStatus === 'error'" class="text-[11px] text-red-300/80 break-all" :title="downloadError">
          ✗ {{ downloadError }}
        </div>
        <div v-else-if="downloadStatus === 'cancelled'" class="text-[11px] text-white/45">
          已取消（下次按「下载」可断点续传 .git pack）
        </div>
        <div v-else-if="!isAuthed" class="text-[11px] text-amber-300/70 font-bold">
          下载需要 admin 账号
        </div>
        <div class="flex justify-end">
          <button
            class="inline-flex items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-bold text-white/65 hover:bg-white/10 hover:text-white transition"
            @click="loadAssets"
          >
            <RefreshCw :size="13" />
            刷新列表
          </button>
        </div>
      </div>
    </header>

    <div v-if="error" class="flex items-center gap-3 rounded-xl border border-red-400/25 bg-red-500/10 px-4 py-3 text-sm text-red-100">
      <AlertCircle :size="18" />
      <span>{{ error }}</span>
    </div>

    <div class="grid gap-5 xl:grid-cols-[320px_minmax(0,1fr)]">
      <aside class="rounded-2xl border border-white/10 bg-white/[0.04] overflow-hidden">
        <div class="border-b border-white/10 px-4 py-3 flex items-center justify-between">
          <div>
            <p class="text-sm font-black text-white">测试资源</p>
            <p class="text-xs text-white/35 mt-1">{{ filteredAssets.length }} / {{ assets.length }} 组 Spine</p>
          </div>
          <Loader2 v-if="loading" :size="17" class="animate-spin text-white/45" />
        </div>
        <div class="grid grid-cols-3 gap-2 border-b border-white/10 p-3">
          <button
            class="inline-flex min-w-0 items-center justify-center gap-1 rounded-lg px-2 py-2 text-xs font-black transition"
            :class="selectedKind === 'char' ? 'bg-white/[0.12] text-white' : 'bg-white/[0.04] text-white/50 hover:text-white/75'"
            @click="selectedKind = 'char'"
          >
            <Box :size="13" />
            角色 {{ charAssetCount }}
          </button>
          <button
            class="inline-flex min-w-0 items-center justify-center gap-1 rounded-lg px-2 py-2 text-xs font-black transition"
            :class="selectedKind === 'cutscene' ? 'bg-white/[0.12] text-white' : 'bg-white/[0.04] text-white/50 hover:text-white/75'"
            @click="selectedKind = 'cutscene'"
          >
            <Film :size="13" />
            Cut {{ cutsceneAssetCount }}
          </button>
          <button
            class="inline-flex min-w-0 items-center justify-center gap-1 rounded-lg px-2 py-2 text-xs font-black transition"
            :class="selectedKind === 'illust' ? 'bg-white/[0.12] text-white' : 'bg-white/[0.04] text-white/50 hover:text-white/75'"
            @click="selectedKind = 'illust'"
          >
            <Sparkles :size="13" />
            立绘 {{ illustAssetCount }}
          </button>
        </div>
        <div class="max-h-[calc(100vh-260px)] overflow-y-auto custom-scrollbar">
          <button
            v-for="asset in filteredAssets"
            :key="asset.id"
            class="w-full text-left px-4 py-3 border-b border-white/[0.06] hover:bg-white/[0.06] transition"
            :class="selectedId === asset.id ? 'bg-accent/15 text-white' : 'text-white/65'"
            @click="selectedId = asset.id"
          >
            <span class="block text-sm font-bold truncate">{{ asset.title }}</span>
            <span class="block text-[11px] text-white/35 mt-1">{{ asset.asset_id }} · {{ asset.textures.length }} texture</span>
          </button>
          <div v-if="!loading && filteredAssets.length === 0" class="px-4 py-10 text-center text-sm text-white/40">
            暂无 Spine 测试资源
          </div>
        </div>
      </aside>

      <section class="min-w-0 rounded-2xl border border-white/10 bg-black/30 overflow-hidden">
        <div class="border-b border-white/10 px-5 py-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div class="min-w-0">
            <p class="text-sm font-black text-white truncate">{{ selectedAsset?.title || '未选择资源' }}</p>
            <p class="text-xs text-white/35 mt-1 truncate">{{ selectedAsset?.asset_id || sourceRoot || 'BD2 asset root not resolved' }}</p>
          </div>
          <div class="flex flex-wrap items-center gap-2 text-xs text-white/45">
            <button
              class="inline-flex items-center gap-2 rounded-lg border px-3 py-2 font-bold transition"
              :class="hideEffectLayers ? 'border-amber-300/35 bg-amber-300/12 text-amber-100' : 'border-white/10 bg-white/[0.04] text-white/55 hover:text-white/80'"
              @click="hideEffectLayers = !hideEffectLayers"
            >
              <Sparkles :size="14" />
              隐藏特效层
            </button>
            <div class="inline-flex items-center gap-2">
              <Box :size="15" />
              <span>{{ animationNames.length }} animations</span>
              <span class="text-white/20">/</span>
              <span>{{ skinNames.length }} skins</span>
            </div>
          </div>
        </div>

        <div class="relative min-h-[620px] bg-[linear-gradient(180deg,rgba(255,255,255,0.04),rgba(0,0,0,0.18))]">
          <div ref="playerHost" class="absolute inset-0 bd2-spine-host"></div>
          <div
            v-if="playerLoading"
            class="absolute inset-0 flex items-center justify-center bg-black/45 text-white/70"
          >
            <Loader2 :size="22" class="animate-spin mr-3" />
            加载 Spine
          </div>
          <div
            v-if="playerError"
            class="absolute left-5 right-5 top-5 flex items-center gap-3 rounded-xl border border-red-400/25 bg-red-500/10 px-4 py-3 text-sm text-red-100"
          >
            <AlertCircle :size="18" />
            <span>{{ playerError }}</span>
          </div>
          <div
            v-if="!selectedAsset && !loading"
            class="absolute inset-0 flex flex-col items-center justify-center text-white/45"
          >
            <Play :size="34" class="mb-3" />
            <p class="font-bold">没有可播放的 Spine 资源</p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.bd2-spine-host :deep(.spine-player) {
  width: 100%;
  height: 100%;
  background: transparent;
}

.bd2-spine-host :deep(canvas) {
  width: 100% !important;
  height: 100% !important;
}
</style>
