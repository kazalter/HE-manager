<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { AlertCircle, Box, Film, Loader2, Play, RefreshCw, Sparkles } from 'lucide-vue-next'
import type { SpinePlayer as SpinePlayerInstance, SpinePlayerConfig } from '@esotericsoftware/spine-player'
import '@esotericsoftware/spine-player/dist/spine-player.css'
import { API_BASE_URL } from '../config'
import type { Bd2SpineAsset, Bd2SpineListResponse } from '../types'

const assets = ref<Bd2SpineAsset[]>([])
const selectedId = ref('')
const selectedKind = ref<'char' | 'cutscene'>('char')
const hideEffectLayers = ref(false)
const loading = ref(true)
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

const assetUrl = (path: string) => `${API_BASE_URL}${path}`

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
    if (!filteredAssets.value.length && selectedKind.value === 'cutscene' && charAssetCount.value > 0) {
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
      <button
        class="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-bold text-white/75 hover:bg-white/10 hover:text-white transition"
        @click="loadAssets"
      >
        <RefreshCw :size="16" />
        刷新
      </button>
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
        <div class="grid grid-cols-2 gap-2 border-b border-white/10 p-3">
          <button
            class="inline-flex min-w-0 items-center justify-center gap-2 rounded-lg px-3 py-2 text-xs font-black transition"
            :class="selectedKind === 'char' ? 'bg-white/[0.12] text-white' : 'bg-white/[0.04] text-white/50 hover:text-white/75'"
            @click="selectedKind = 'char'"
          >
            <Box :size="14" />
            角色 {{ charAssetCount }}
          </button>
          <button
            class="inline-flex min-w-0 items-center justify-center gap-2 rounded-lg px-3 py-2 text-xs font-black transition"
            :class="selectedKind === 'cutscene' ? 'bg-white/[0.12] text-white' : 'bg-white/[0.04] text-white/50 hover:text-white/75'"
            @click="selectedKind = 'cutscene'"
          >
            <Film :size="14" />
            Cutscene {{ cutsceneAssetCount }}
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
