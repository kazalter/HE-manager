<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { SpinePlayer as SpinePlayerInstance, SpinePlayerConfig } from '@esotericsoftware/spine-player'
import '@esotericsoftware/spine-player/dist/spine-player.css'
import { API_BASE_URL } from '../config'

const route = useRoute()
const SPINE_ASSET_CACHE_VERSION = 'spine41-atlas-alias-v2'

const skeletonUrlParam = ref(String(route.query.skeletonUrl || ''))
const atlasUrlParam = ref(String(route.query.atlasUrl || ''))

const playerHost = ref<HTMLDivElement | null>(null)
const hideEffectLayers = ref(false)
let player: SpinePlayerInstance | null = null

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
const hiddenAttachments = new WeakMap<SpineSlot, unknown>()

const assetUrl = (path: string) => {
  if (!path) return ''
  // If it's already an absolute URL, return it
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path
  }
  const separator = path.includes('?') ? '&' : '?'
  return `${API_BASE_URL}${path}${separator}v=${SPINE_ASSET_CACHE_VERSION}`
}

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
}

const mountPlayer = async () => {
  disposePlayer()
  const skel = skeletonUrlParam.value
  const atlas = atlasUrlParam.value
  if (!skel || !atlas) {
    console.error('Embed Spine: missing skeletonUrl or atlasUrl')
    return
  }

  await nextTick()
  if (!playerHost.value) return

  const config = {
    binaryUrl: assetUrl(skel),
    atlasUrl: assetUrl(atlas),
    showControls: false, // Hide controls, controlled natively via Android Compose UI
    showLoading: true,
    alpha: true,
    premultipliedAlpha: false,
    backgroundColor: '00000000',
    fullScreenBackgroundColor: '070a12',
    viewport: {
      x: 0,
      y: 0,
      width: 0,
      height: 0,
      padLeft: '5%',
      padRight: '5%',
      padTop: '5%',
      padBottom: '5%',
      debugRender: false,
      transitionTime: 0.2,
      animations: {},
    },
    success: (nextPlayer: SpinePlayerInstance) => {
      const runtimePlayer = nextPlayer as RuntimeSpinePlayer
      const animationNames = runtimePlayer.skeleton?.data?.animations?.map((a) => a.name) || []
      const skinNames = runtimePlayer.skeleton?.data?.skins?.map((s) => s.name) || []
      applyEffectLayerFilter(nextPlayer)

      // Notify Android host
      const android = (window as any).Android
      if (android && typeof android.onPlayerSuccess === 'function') {
        android.onPlayerSuccess(JSON.stringify(animationNames), JSON.stringify(skinNames))
      }
    },
    error: (_nextPlayer: SpinePlayerInstance, message: string) => {
      console.error('Spine Player Error:', message)
      const android = (window as any).Android
      if (android && typeof android.onPlayerError === 'function') {
        android.onPlayerError(message || 'WebGL loading failed')
      }
    },
    frame: (nextPlayer: SpinePlayerInstance) => {
      applyEffectLayerFilter(nextPlayer)
    },
  } as unknown as SpinePlayerConfig

  try {
    const { SpinePlayer } = await import('@esotericsoftware/spine-player')
    player = new SpinePlayer(playerHost.value, config)
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : 'Spine initialization failed'
    console.error(msg)
    const android = (window as any).Android
    if (android && typeof android.onPlayerError === 'function') {
      android.onPlayerError(msg)
    }
  }
}

// Expose APIs to window for Android to call via evaluateJavascript
const setupAndroidExposedApis = () => {
  const w = window as any
  w.playAnimation = (name: string, loop: boolean = true) => {
    if (player) {
      try {
        player.setAnimation(name, loop)
      } catch (e) {
        console.error('Play animation error:', e)
      }
    }
  }

  w.setSkin = (name: string) => {
    if (player) {
      try {
        (player as any).setSkin(name)
      } catch (e) {
        console.error('Set skin error:', e)
      }
    }
  }

  w.setHideEffectLayers = (hide: boolean) => {
    hideEffectLayers.value = hide
    applyEffectLayerFilter()
  }

  w.loadSpineAsset = (skel: string, atlas: string) => {
    skeletonUrlParam.value = skel
    atlasUrlParam.value = atlas
    mountPlayer()
  }
}

onMounted(() => {
  setupAndroidExposedApis()
  mountPlayer()
})

onBeforeUnmount(disposePlayer)

watch([skeletonUrlParam, atlasUrlParam], mountPlayer)
</script>

<template>
  <div class="w-screen h-screen overflow-hidden bg-transparent select-none relative">
    <div ref="playerHost" class="absolute inset-0 bd2-spine-embed-host"></div>
  </div>
</template>

<style scoped>
.bd2-spine-embed-host :deep(.spine-player) {
  width: 100%;
  height: 100%;
  background: transparent;
}

.bd2-spine-embed-host :deep(canvas) {
  width: 100% !important;
  height: 100% !important;
}
</style>
