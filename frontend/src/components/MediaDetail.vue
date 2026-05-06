<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import axios from 'axios'
import { ChevronLeft, ChevronRight, Maximize, Minimize, Plus, Star, Tag as TagIcon, Trash2, X } from 'lucide-vue-next'
import Artplayer from 'artplayer'
import artplayerPluginVttThumbnail from 'artplayer-plugin-vtt-thumbnail'
import { API_BASE_URL, STREAM_URL, THUMBNAIL_URL } from '../config'
import type { Media } from '../types'

const props = defineProps<{
  initialMedia: Media
  allMedia: Media[]
}>()

const emit = defineEmits<{
  close: []
  updated: [media: Media]
  navigate: [media: Media]
}>()

const currentMedia = ref<Media>(props.initialMedia)
const currentPage = ref(0)
const totalMangaPages = ref<number | null>(null)
const isFullscreen = ref(false)
const showControls = ref(true)
const tagInput = ref('')
const artRef = ref<HTMLDivElement | null>(null)

let controlTimer: number | undefined
let clickTimer: number | undefined
let artInstance: Artplayer | null = null
let volumeWheelElement: HTMLElement | null = null
let progressVideoElement: HTMLVideoElement | null = null
let vttBlobUrl = ''
let artInitToken = 0
let lastProgressSavedAt = 0
let lastSavedProgress = -1
let lastMangaWheelAt = 0
let mangaProgressTimer: number | undefined
let lastSavedMangaProgress = -1

const VOLUME_WHEEL_STEP = 0.05
const PROGRESS_SAVE_INTERVAL_MS = 5000
const MANGA_WHEEL_INTERVAL_MS = 320
const VOLUME_WHEEL_SELECTOR = [
  '.art-control-volume',
  '.art-volume-panel',
  '.art-volume-inner',
  '.art-volume-slider',
  '.art-volume-handle',
  '.art-volume-loaded',
  '.art-volume-indicator',
  '.art-icon-volume',
  '.art-icon-volumeClose',
].join(', ')
const VOLUME_WHEEL_HOVER_SELECTOR = VOLUME_WHEEL_SELECTOR
  .split(', ')
  .map(selector => `${selector}:hover`)
  .join(', ')

const pageUrl = computed(() => {
  if (currentMedia.value.media_type === 'image') return `${API_BASE_URL}/stream/${currentMedia.value.id}`
  return `${API_BASE_URL}/manga/${currentMedia.value.id}/page/${currentPage.value}`
})
const videoUrl = computed(() => `${STREAM_URL}/${currentMedia.value.id}`)
const coverUrl = computed(() => currentMedia.value.cover_path ? `${THUMBNAIL_URL}/${currentMedia.value.cover_path}` : '')
const isImage = computed(() => currentMedia.value.media_type === 'image')
const isManga = computed(() => currentMedia.value.media_type === 'manga')
const isVideo = computed(() => currentMedia.value.media_type === 'video')
const clickOnlyViewerControls = computed(() => isFullscreen.value && (isManga.value || isImage.value))
const currentIndex = computed(() => props.allMedia.findIndex(m => m.id === currentMedia.value.id))
const videoProgressPercent = computed(() => {
  if (!isVideo.value || !currentMedia.value.duration || currentMedia.value.progress <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((currentMedia.value.progress / currentMedia.value.duration) * 100)))
})
const mangaPageTotal = computed(() => totalMangaPages.value || currentMedia.value.page_count || 0)
const mangaCurrentPageNumber = computed(() => {
  const current = currentPage.value + 1
  return mangaPageTotal.value ? Math.min(mangaPageTotal.value, Math.max(1, current)) : Math.max(1, current)
})
const mangaProgressPercent = computed(() => {
  if (!isManga.value || !mangaPageTotal.value) return 0
  return Math.min(100, Math.max(0, Math.round((mangaCurrentPageNumber.value / mangaPageTotal.value) * 100)))
})
const mangaProgressText = computed(() => {
  return mangaPageTotal.value ? `${mangaCurrentPageNumber.value} / ${mangaPageTotal.value}` : `${mangaCurrentPageNumber.value}`
})

const resetTimer = () => {
  if (clickOnlyViewerControls.value) return
  showControls.value = true
  window.clearTimeout(controlTimer)
  controlTimer = window.setTimeout(() => {
    showControls.value = false
  }, 1400)
}

const toggleViewerControls = () => {
  if (!clickOnlyViewerControls.value) {
    resetTimer()
    return
  }
  window.clearTimeout(controlTimer)
  if (showControls.value) {
    showControls.value = false
    return
  }
  showControls.value = true
  controlTimer = window.setTimeout(() => {
    showControls.value = false
  }, 1800)
}

const handleViewerClick = () => {
  window.clearTimeout(clickTimer)
  clickTimer = window.setTimeout(() => {
    clickTimer = undefined
    toggleViewerControls()
  }, 240)
}

const handleViewerDoubleClick = () => {
  window.clearTimeout(clickTimer)
  clickTimer = undefined
  toggleFullscreen()
}

const formatSize = (bytes: number) => {
  if (bytes === 0) return '本地目录'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

const formatDuration = (seconds: number | null) => {
  if (!seconds) return '未知'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return h > 0 ? `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}` : `${m}:${s.toString().padStart(2, '0')}`
}

const mediaTypeLabel = computed(() => {
  if (currentMedia.value.media_type === 'video') return '视频'
  if (currentMedia.value.media_type === 'manga') return '漫画'
  return '图片'
})

const progressText = computed(() => {
  if (isManga.value) return mangaProgressText.value
  if (!isVideo.value) return '-'
  return `${formatDuration(currentMedia.value.progress || 0)} / ${formatDuration(currentMedia.value.duration)}`
})

const applyMediaPatch = (media: Media) => {
  Object.assign(currentMedia.value, media)
  emit('updated', { ...currentMedia.value })
}

const updateMedia = async (payload: Partial<Pick<Media, 'duration' | 'favorite' | 'rating' | 'view_status' | 'progress' | 'title' | 'source_url' | 'source_site'>>) => {
  const res = await axios.patch(`${API_BASE_URL}/media/${currentMedia.value.id}`, payload)
  applyMediaPatch(res.data)
}

const setRating = async (score: number) => {
  const previousMedia = { ...currentMedia.value, tags: [...currentMedia.value.tags] }
  const nextRating = currentMedia.value.rating === score ? 0 : score
  const optimisticMedia = { ...currentMedia.value, rating: nextRating }
  Object.assign(currentMedia.value, optimisticMedia)
  emit('updated', optimisticMedia)

  try {
    await updateMedia({ rating: nextRating })
  } catch (err) {
    Object.assign(currentMedia.value, previousMedia)
    emit('updated', previousMedia)
    console.error('Failed to update rating:', err)
    alert('评分保存失败。后端当前没有响应新版更新接口，请重新运行 run_app.bat。')
  }
}

const addTag = async () => {
  const name = tagInput.value.trim()
  if (!name) return
  const res = await axios.post(`${API_BASE_URL}/media/${currentMedia.value.id}/tags`, { name })
  applyMediaPatch(res.data)
  tagInput.value = ''
}

const removeTag = async (tagId: number) => {
  const res = await axios.delete(`${API_BASE_URL}/media/${currentMedia.value.id}/tags/${tagId}`)
  applyMediaPatch(res.data)
}

const nextMedia = () => {
  if (currentIndex.value < props.allMedia.length - 1) {
    const next = props.allMedia[currentIndex.value + 1]
    currentMedia.value = next
    currentPage.value = 0
    emit('navigate', next)
  }
}

const prevMedia = () => {
  if (currentIndex.value > 0) {
    const prev = props.allMedia[currentIndex.value - 1]
    currentMedia.value = prev
    currentPage.value = 0
    emit('navigate', prev)
  }
}

const isArtVolumeTarget = (e: WheelEvent) => {
  const path = e.composedPath()
  const isVolumePath = path.some(node => node instanceof Element && !!node.closest(VOLUME_WHEEL_SELECTOR))
  if (isVolumePath) return true
  return !!volumeWheelElement?.querySelector(VOLUME_WHEEL_HOVER_SELECTOR)
}

const handleVolumeWheel = (e: WheelEvent) => {
  if (!artInstance || !isArtVolumeTarget(e)) return
  e.preventDefault()
  e.stopPropagation()

  const delta = e.deltaY || e.deltaX
  const direction = delta < 0 ? 1 : -1
  const currentVolume = artInstance.muted ? 0 : artInstance.volume
  const nextVolume = Math.min(1, Math.max(0, currentVolume + direction * VOLUME_WHEEL_STEP))

  artInstance.muted = nextVolume === 0
  artInstance.volume = Number(nextVolume.toFixed(2))
}

const interceptClick = (e: MouseEvent) => {
  const target = e.target as HTMLElement
  if (target.tagName.toLowerCase() !== 'video' && !target.classList.contains('art-state')) return

  e.stopPropagation()
  e.stopImmediatePropagation()
  e.preventDefault()

  if (e.type === 'dblclick') {
    window.clearTimeout(clickTimer)
    clickTimer = undefined
    if (artInstance) artInstance.fullscreen = !artInstance.fullscreen
    return
  }

  if (clickTimer) {
    window.clearTimeout(clickTimer)
    clickTimer = undefined
  } else {
    clickTimer = window.setTimeout(() => {
      clickTimer = undefined
      if (artInstance) artInstance.toggle()
    }, 300)
  }
}

const inferredStatus = (progress: number, duration: number | null): Media['view_status'] => {
  if (!duration || progress <= 0) return progress > 0 ? 'viewing' : 'unviewed'
  if (progress / duration >= 0.95) return 'viewed'
  return 'viewing'
}

const saveVideoProgress = async (force = false) => {
  const video = progressVideoElement
  if (!video || !isVideo.value) return

  const progress = Math.max(0, Math.floor(video.currentTime || 0))
  const duration = Number.isFinite(video.duration) && video.duration > 0
    ? Math.floor(video.duration)
    : currentMedia.value.duration

  const now = Date.now()
  if (!force && now - lastProgressSavedAt < PROGRESS_SAVE_INTERVAL_MS) return
  if (!force && Math.abs(progress - lastSavedProgress) < 3) return

  lastProgressSavedAt = now
  lastSavedProgress = progress

  Object.assign(currentMedia.value, {
    progress,
    duration,
    view_status: inferredStatus(progress, duration),
  })
  emit('updated', { ...currentMedia.value })

  try {
    await updateMedia({ progress, duration: duration ?? undefined })
  } catch (err) {
    console.error('Failed to save video progress:', err)
  }
}

const saveMangaProgress = async (force = false) => {
  if (!isManga.value) return

  const maxPage = totalMangaPages.value ? totalMangaPages.value - 1 : currentMedia.value.page_count ? currentMedia.value.page_count - 1 : null
  const progress = maxPage === null
    ? Math.max(0, currentPage.value)
    : Math.max(0, Math.min(currentPage.value, maxPage))

  if (!force && progress === lastSavedMangaProgress) return
  lastSavedMangaProgress = progress

  Object.assign(currentMedia.value, {
    progress,
    view_status: progress > 0 ? 'viewing' : currentMedia.value.view_status,
  })
  emit('updated', { ...currentMedia.value })

  try {
    await updateMedia({ progress })
  } catch (err) {
    console.error('Failed to save manga progress:', err)
  }
}

const scheduleMangaProgressSave = () => {
  window.clearTimeout(mangaProgressTimer)
  mangaProgressTimer = window.setTimeout(() => {
    saveMangaProgress(false)
  }, 350)
}

const handleVideoLoadedMetadata = () => {
  const video = progressVideoElement
  if (!video) return

  if (currentMedia.value.progress > 0 && video.duration && currentMedia.value.progress < video.duration - 3) {
    video.currentTime = currentMedia.value.progress
  }

  saveVideoProgress(true)
}

const handleVideoEnded = () => {
  const video = progressVideoElement
  if (video && video.duration) {
    video.currentTime = video.duration
  }
  saveVideoProgress(true)
}

const handleVideoTimeUpdate = () => {
  saveVideoProgress(false)
}

const handleVideoPause = () => {
  saveVideoProgress(true)
}

const bindVideoProgressEvents = () => {
  const video = (artInstance as unknown as { video?: HTMLVideoElement } | null)?.video
  if (!video) return

  progressVideoElement = video
  video.addEventListener('loadedmetadata', handleVideoLoadedMetadata)
  video.addEventListener('timeupdate', handleVideoTimeUpdate)
  video.addEventListener('pause', handleVideoPause)
  video.addEventListener('ended', handleVideoEnded)
}

const unbindVideoProgressEvents = () => {
  if (!progressVideoElement) return
  progressVideoElement.removeEventListener('loadedmetadata', handleVideoLoadedMetadata)
  progressVideoElement.removeEventListener('timeupdate', handleVideoTimeUpdate)
  progressVideoElement.removeEventListener('pause', handleVideoPause)
  progressVideoElement.removeEventListener('ended', handleVideoEnded)
  progressVideoElement = null
}

const destroyArtplayer = () => {
  unbindVideoProgressEvents()
  volumeWheelElement?.removeEventListener('wheel', handleVolumeWheel, { capture: true })
  artRef.value?.removeEventListener('click', interceptClick, true)
  artRef.value?.removeEventListener('dblclick', interceptClick, true)

  if (artInstance) {
    try {
      artInstance.destroy(false)
    } catch (err) {
      console.warn('Artplayer destroy failed:', err)
    }
    artInstance = null
  }

  if (vttBlobUrl) {
    URL.revokeObjectURL(vttBlobUrl)
    vttBlobUrl = ''
  }

  volumeWheelElement = null
  artRef.value?.replaceChildren()
}

const stopArtplayer = () => {
  artInitToken++
  destroyArtplayer()
}

const initArtplayer = async () => {
  const token = ++artInitToken
  destroyArtplayer()
  await nextTick()

  const container = artRef.value
  if (token !== artInitToken || !container || !isVideo.value) return

  const plugins = []
  if (currentMedia.value.cover_path) {
    try {
      const vttRoute = `${API_BASE_URL}/thumbnails/${currentMedia.value.cover_path.replace('.jpg', '.vtt')}`
      const res = await axios.get(vttRoute)
      const text = String(res.data).replace(/(?:\/thumbnails\/)?([^\s]+\.jpg(#xywh=[0-9,]+)?)/g, `${API_BASE_URL}/thumbnails/$1`)
      const blob = new Blob([text], { type: 'text/vtt' })
      const nextVttBlobUrl = URL.createObjectURL(blob)

      if (token !== artInitToken) {
        URL.revokeObjectURL(nextVttBlobUrl)
        return
      }

      vttBlobUrl = nextVttBlobUrl
      plugins.push(artplayerPluginVttThumbnail({ vtt: vttBlobUrl }))
    } catch {
      console.log('VTT thumbnail not available for this video.')
    }
  }

  if (token !== artInitToken) return

  container.replaceChildren()
  artInstance = new Artplayer({
    container,
    url: videoUrl.value,
    volume: 0.5,
    autoplay: true,
    pip: true,
    autoSize: true,
    autoMini: true,
    screenshot: true,
    setting: true,
    playbackRate: true,
    aspectRatio: true,
    fullscreen: true,
    fullscreenWeb: true,
    miniProgressBar: true,
    mutex: true,
    backdrop: true,
    playsInline: true,
    autoPlayback: true,
    airplay: true,
    theme: '#818cf8',
    plugins,
  })

  container.addEventListener('click', interceptClick, true)
  container.addEventListener('dblclick', interceptClick, true)
  volumeWheelElement = container.querySelector('.art-video-player') ?? container
  volumeWheelElement.addEventListener('wheel', handleVolumeWheel, { capture: true, passive: false })
  bindVideoProgressEvents()
}

watch(
  () => [currentMedia.value.id, currentMedia.value.media_type] as const,
  async () => {
    const newVal = currentMedia.value
    currentPage.value = newVal.media_type === 'manga' ? Math.max(0, newVal.progress || 0) : 0
    lastSavedMangaProgress = newVal.media_type === 'manga' ? currentPage.value : -1

    if (newVal.media_type === 'manga') {
      totalMangaPages.value = null
      try {
        const res = await axios.get(`${API_BASE_URL}/manga/${newVal.id}/pages`)
        totalMangaPages.value = res.data.total_pages
      } catch {
        totalMangaPages.value = null
      }
    }

    if (newVal.media_type === 'video') {
      await initArtplayer()
    } else {
      stopArtplayer()
    }
  },
  { immediate: true },
)

watch(currentPage, () => {
  if (isManga.value) scheduleMangaProgressSave()
})

const nextPage = () => {
  if (totalMangaPages.value === null || currentPage.value < totalMangaPages.value - 1) {
    currentPage.value++
  }
}

const prevPage = () => {
  if (currentPage.value > 0) currentPage.value--
}

const handleMangaWheel = (e: WheelEvent) => {
  if (!isManga.value) return
  const now = Date.now()
  if (now - lastMangaWheelAt < MANGA_WHEEL_INTERVAL_MS) return

  const delta = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX
  if (Math.abs(delta) < 8) return

  e.preventDefault()
  lastMangaWheelAt = now
  if (delta > 0) {
    nextPage()
  } else {
    prevPage()
  }
}

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') emit('close')
  if (e.key === 'ArrowRight') isManga.value ? nextPage() : nextMedia()
  if (e.key === 'ArrowLeft') isManga.value ? prevPage() : prevMedia()
}

const toggleFullscreen = () => {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen()
  } else {
    document.exitFullscreen()
  }
}

const onFullscreenChange = () => {
  isFullscreen.value = !!document.fullscreenElement
  window.clearTimeout(controlTimer)
  showControls.value = true
  if (clickOnlyViewerControls.value) {
    controlTimer = window.setTimeout(() => {
      showControls.value = false
    }, 1800)
  }
}

onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('mousemove', resetTimer)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  resetTimer()
})

onUnmounted(() => {
  window.clearTimeout(mangaProgressTimer)
  saveMangaProgress(true)
  saveVideoProgress(true)
  document.body.style.overflow = 'auto'
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('mousemove', resetTimer)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  window.clearTimeout(controlTimer)
  window.clearTimeout(clickTimer)
  if (document.fullscreenElement) document.exitFullscreen()
  stopArtplayer()
})
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[200] flex items-center justify-center">
      <div class="absolute inset-0 bg-background/85 backdrop-blur-2xl" @click="emit('close')"></div>

      <div class="relative w-full h-full bg-[#060606] shadow-2xl flex overflow-hidden">
        <section class="relative flex-1 min-w-0 bg-black flex flex-col">
          <header
            :class="showControls
              ? 'opacity-100 translate-y-0'
              : clickOnlyViewerControls
                ? 'opacity-0 -translate-y-3 pointer-events-none'
                : 'opacity-0 -translate-y-3 hover:opacity-100 hover:translate-y-0'"
            class="absolute top-0 left-0 right-0 flex items-center justify-between px-6 py-5 z-50 bg-gradient-to-b from-black/80 to-transparent transition-all duration-300"
          >
            <h2 class="text-lg font-bold truncate pr-4 grow text-white/95 drop-shadow-xl select-none">{{ currentMedia.title }}</h2>
            <div class="flex items-center gap-2">
              <button @click="toggleFullscreen" class="w-11 h-11 rounded-xl bg-black/35 backdrop-blur-md hover:bg-black/55 text-white/65 hover:text-white transition-all" :title="isFullscreen ? '退出全屏' : '全屏'">
                <Minimize v-if="isFullscreen" :size="19" class="mx-auto" />
                <Maximize v-else :size="19" class="mx-auto" />
              </button>
              <button @click="emit('close')" class="w-11 h-11 rounded-xl bg-red-500/20 backdrop-blur-md hover:bg-red-500/40 text-red-100 hover:text-white transition-all" title="关闭">
                <X :size="20" class="mx-auto" />
              </button>
            </div>
          </header>

          <div v-if="isVideo" class="relative flex-1 min-h-0 bg-black">
            <div ref="artRef" class="media-detail-player w-full h-full outline-none"></div>
          </div>

          <div v-else class="flex-1 min-h-0 flex flex-col items-center bg-black overflow-hidden relative group" @wheel="handleMangaWheel" @click="handleViewerClick" @dblclick="handleViewerDoubleClick">
            <div class="flex-1 flex items-center justify-center w-full h-full relative">
              <button
                @click.stop="isManga ? prevPage() : prevMedia()"
                :class="showControls
                  ? 'opacity-100 translate-x-0'
                  : clickOnlyViewerControls
                    ? 'opacity-0 -translate-x-6 pointer-events-none'
                    : 'opacity-0 -translate-x-6 hover:opacity-100 hover:translate-x-0'"
                class="absolute left-5 z-10 w-14 h-14 rounded-2xl bg-black/45 backdrop-blur-md text-white/55 hover:text-white hover:bg-black/70 transition-all duration-300"
                title="上一项"
              >
                <ChevronLeft :size="34" class="mx-auto" />
              </button>

              <img
                :src="pageUrl"
                class="h-full w-full object-contain transition-opacity duration-300"
                :class="{ 'cursor-zoom-in': isImage }"
                :alt="currentMedia.title"
              />

              <button
                @click.stop="isManga ? nextPage() : nextMedia()"
                :class="showControls
                  ? 'opacity-100 translate-x-0'
                  : clickOnlyViewerControls
                    ? 'opacity-0 translate-x-6 pointer-events-none'
                    : 'opacity-0 translate-x-6 hover:opacity-100 hover:translate-x-0'"
                class="absolute right-5 z-10 w-14 h-14 rounded-2xl bg-black/45 backdrop-blur-md text-white/55 hover:text-white hover:bg-black/70 transition-all duration-300"
                title="下一项"
              >
                <ChevronRight :size="34" class="mx-auto" />
              </button>

              <div
                v-if="isManga"
                :class="showControls || !clickOnlyViewerControls ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-3 pointer-events-none'"
                class="absolute bottom-6 left-1/2 z-20 w-[min(520px,calc(100%-2rem))] -translate-x-1/2 rounded-2xl bg-black/60 backdrop-blur-md border border-white/10 px-4 py-3 shadow-2xl transition-all duration-300"
                @click.stop
              >
                <div class="flex items-center justify-between gap-4 text-sm font-mono tracking-widest">
                  <p class="text-white/70">
                    PAGE <span class="text-white/95 font-bold ml-1">{{ mangaProgressText }}</span>
                  </p>
                  <p class="font-bold text-purple-200">{{ mangaProgressPercent }}%</p>
                </div>
                <div class="mt-2 h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div class="h-full bg-purple-300 transition-all duration-300" :style="{ width: `${mangaProgressPercent}%` }"></div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="isVideo" class="shrink-0 border-t border-white/10 bg-background/95 px-6 py-4">
            <div class="flex items-start justify-between gap-6">
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2 mb-2">
                  <span class="rounded-md bg-accent/15 px-2 py-1 text-[11px] font-black text-accent">{{ mediaTypeLabel }}</span>
                  <span class="text-xs text-white/35 truncate">{{ currentMedia.relative_path }}</span>
                </div>
                <h3 class="text-xl font-black text-white truncate">{{ currentMedia.title }}</h3>
                <div class="mt-3 h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div class="h-full bg-accent transition-all" :style="{ width: `${videoProgressPercent}%` }"></div>
                </div>
              </div>

              <div class="grid grid-cols-3 gap-2 text-right shrink-0 min-w-[300px]">
                <div class="rounded-xl bg-white/5 border border-white/10 px-4 py-3">
                  <p class="text-[11px] text-white/35 mb-1">进度</p>
                  <p class="text-sm font-bold text-white">{{ videoProgressPercent }}%</p>
                </div>
                <div class="rounded-xl bg-white/5 border border-white/10 px-4 py-3">
                  <p class="text-[11px] text-white/35 mb-1">播放</p>
                  <p class="text-sm font-bold text-white">{{ progressText }}</p>
                </div>
                <div class="rounded-xl bg-white/5 border border-white/10 px-4 py-3">
                  <p class="text-[11px] text-white/35 mb-1">大小</p>
                  <p class="text-sm font-bold text-white">{{ formatSize(currentMedia.file_size) }}</p>
                </div>
              </div>
            </div>
          </div>
        </section>

        <aside v-if="!isFullscreen" class="hidden xl:flex w-[360px] shrink-0 border-l border-white/10 bg-background/95 p-5 flex-col gap-5 overflow-y-auto">
          <div class="flex gap-4">
            <div class="w-24 h-24 rounded-xl bg-white/5 border border-white/10 overflow-hidden shrink-0">
              <img v-if="coverUrl" :src="coverUrl" class="w-full h-full object-cover" :alt="currentMedia.title" />
            </div>
            <div class="min-w-0 flex-1">
              <p class="text-xs font-bold text-white/40 uppercase tracking-widest mb-2">媒体信息</p>
              <h3 class="text-xl font-black text-white leading-snug break-words">{{ currentMedia.title }}</h3>
              <p class="mt-2 text-xs text-white/40 break-all line-clamp-2">{{ currentMedia.relative_path }}</p>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-2 text-sm">
            <div class="rounded-xl bg-white/5 p-3 border border-white/10">
              <p class="text-white/35 text-xs mb-1">类型</p>
              <p class="font-semibold">{{ mediaTypeLabel }}</p>
            </div>
            <div class="rounded-xl bg-white/5 p-3 border border-white/10">
              <p class="text-white/35 text-xs mb-1">进度</p>
              <p class="font-semibold">{{ isVideo ? `${videoProgressPercent}%` : isManga ? `${mangaProgressPercent}%` : '-' }}</p>
            </div>
            <div class="rounded-xl bg-white/5 p-3 border border-white/10">
              <p class="text-white/35 text-xs mb-1">时长/页数</p>
              <p class="font-semibold">{{ isVideo ? formatDuration(currentMedia.duration) : isManga ? progressText : (currentMedia.page_count || '-') }}</p>
            </div>
            <div class="rounded-xl bg-white/5 p-3 border border-white/10">
              <p class="text-white/35 text-xs mb-1">尺寸</p>
              <p class="font-semibold">{{ currentMedia.width && currentMedia.height ? `${currentMedia.width} x ${currentMedia.height}` : '-' }}</p>
            </div>
          </div>

          <div v-if="isManga && mangaPageTotal" class="rounded-2xl bg-white/5 border border-white/10 p-4">
            <div class="flex items-center justify-between text-sm">
              <span class="font-bold text-white/75">阅读进度</span>
              <span class="font-mono font-bold text-purple-200">{{ progressText }}</span>
            </div>
            <div class="mt-3 h-1.5 rounded-full bg-white/10 overflow-hidden">
              <div class="h-full bg-purple-300 transition-all duration-300" :style="{ width: `${mangaProgressPercent}%` }"></div>
            </div>
          </div>

          <button
            type="button"
            @click="updateMedia({ favorite: !currentMedia.favorite })"
            :class="currentMedia.favorite ? 'bg-amber-400 text-black' : 'bg-white/5 text-white/70 hover:text-white'"
            class="w-full h-11 rounded-xl border border-white/10 font-bold flex items-center justify-center gap-2 transition-all"
          >
            <Star :size="17" :fill="currentMedia.favorite ? 'currentColor' : 'none'" />
            {{ currentMedia.favorite ? '已收藏' : '收藏' }}
          </button>

          <div>
            <p class="text-xs font-bold text-white/40 uppercase tracking-widest mb-2">评分</p>
            <div class="flex gap-2">
              <button
                v-for="score in 5"
                :key="score"
                type="button"
                @click.stop="setRating(score)"
                class="w-10 h-10 rounded-xl bg-white/5 hover:bg-white/10 transition-all text-amber-300 cursor-pointer"
                :title="`${score} 星`"
              >
                <Star :size="20" class="mx-auto" :fill="currentMedia.rating >= score ? 'currentColor' : 'none'" />
              </button>
            </div>
          </div>

          <div>
            <div class="flex items-center gap-2 text-xs font-bold text-white/40 uppercase tracking-widest mb-2">
              <TagIcon :size="14" />
              <span>标签</span>
            </div>
            <div class="flex flex-wrap gap-2 mb-3">
              <span v-for="tag in currentMedia.tags" :key="tag.id" class="inline-flex items-center gap-1 rounded-lg bg-white/8 border border-white/10 px-2 py-1 text-xs">
                {{ tag.name }}
                <button type="button" @click="removeTag(tag.id)" class="text-white/35 hover:text-red-300" title="移除标签">
                  <Trash2 :size="12" />
                </button>
              </span>
              <span v-if="currentMedia.tags.length === 0" class="text-sm text-white/35">还没有标签</span>
            </div>
            <div class="flex gap-2">
              <input
                v-model="tagInput"
                @keydown.enter="addTag"
                class="min-w-0 flex-1 rounded-xl bg-white/5 border border-white/10 px-3 py-2 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
                placeholder="添加标签"
              />
              <button type="button" @click="addTag" class="w-10 rounded-xl bg-accent text-white flex items-center justify-center" title="添加标签">
                <Plus :size="18" />
              </button>
            </div>
          </div>
        </aside>
      </div>
    </div>
  </Teleport>
</template>
