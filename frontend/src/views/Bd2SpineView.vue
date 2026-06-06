<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { AlertCircle, Box, Loader2, Play, RefreshCw } from 'lucide-vue-next'
import type { SpinePlayer as SpinePlayerInstance, SpinePlayerConfig } from '@esotericsoftware/spine-player'
import '@esotericsoftware/spine-player/dist/spine-player.css'
import { API_BASE_URL } from '../config'
import type { Bd2SpineAsset, Bd2SpineListResponse } from '../types'

const assets = ref<Bd2SpineAsset[]>([])
const selectedId = ref('')
const loading = ref(true)
const playerLoading = ref(false)
const error = ref('')
const playerError = ref('')
const sourceRoot = ref('')
const animationNames = ref<string[]>([])
const skinNames = ref<string[]>([])
const playerHost = ref<HTMLDivElement | null>(null)
let player: SpinePlayerInstance | null = null

const selectedAsset = computed(() => assets.value.find((asset) => asset.id === selectedId.value) || null)

const assetUrl = (path: string) => `${API_BASE_URL}${path}`

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
    if (!selectedId.value || !assets.value.some((asset) => asset.id === selectedId.value)) {
      selectedId.value = assets.value[0]?.id || ''
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

  const config: SpinePlayerConfig = {
    skeleton: assetUrl(asset.skeleton_url),
    atlas: assetUrl(asset.atlas_url),
    showControls: true,
    showLoading: true,
    alpha: true,
    backgroundColor: '00000000',
    fullScreenBackgroundColor: '101216',
    preserveDrawingBuffer: false,
    viewport: {
      padLeft: '8%',
      padRight: '8%',
      padTop: '8%',
      padBottom: '8%',
      transitionTime: 0.2,
    },
    success: (nextPlayer) => {
      playerLoading.value = false
      animationNames.value = nextPlayer.skeleton?.data.animations.map((animation) => animation.name) || []
      skinNames.value = nextPlayer.skeleton?.data.skins.map((skin) => skin.name) || []
    },
    error: (_nextPlayer, message) => {
      playerLoading.value = false
      playerError.value = message || 'Spine 播放器加载失败'
    },
  }

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
            <p class="text-xs text-white/35 mt-1">{{ assets.length }} 组 Spine</p>
          </div>
          <Loader2 v-if="loading" :size="17" class="animate-spin text-white/45" />
        </div>
        <div class="max-h-[calc(100vh-260px)] overflow-y-auto custom-scrollbar">
          <button
            v-for="asset in assets"
            :key="asset.id"
            class="w-full text-left px-4 py-3 border-b border-white/[0.06] hover:bg-white/[0.06] transition"
            :class="selectedId === asset.id ? 'bg-accent/15 text-white' : 'text-white/65'"
            @click="selectedId = asset.id"
          >
            <span class="block text-sm font-bold truncate">{{ asset.title }}</span>
            <span class="block text-[11px] text-white/35 mt-1">{{ asset.id }} · {{ asset.textures.length }} texture</span>
          </button>
          <div v-if="!loading && assets.length === 0" class="px-4 py-10 text-center text-sm text-white/40">
            暂无 Spine 测试资源
          </div>
        </div>
      </aside>

      <section class="min-w-0 rounded-2xl border border-white/10 bg-black/30 overflow-hidden">
        <div class="border-b border-white/10 px-5 py-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div class="min-w-0">
            <p class="text-sm font-black text-white truncate">{{ selectedAsset?.title || '未选择资源' }}</p>
            <p class="text-xs text-white/35 mt-1 truncate">{{ sourceRoot || 'BD2 asset root not resolved' }}</p>
          </div>
          <div class="flex items-center gap-2 text-xs text-white/45">
            <Box :size="15" />
            <span>{{ animationNames.length }} animations</span>
            <span class="text-white/20">/</span>
            <span>{{ skinNames.length }} skins</span>
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
