<script setup lang="ts">
import { Book, Eye, Film, Image as ImageIcon, Play, Star } from 'lucide-vue-next'
import { THUMBNAIL_URL } from '../config'
import type { Media } from '../types'

defineProps<{
  media: Media
}>()

const getThumb = (path: string | null) => {
  if (!path) return 'https://via.placeholder.com/400x600?text=No+Cover'
  return `${THUMBNAIL_URL}/${path}`
}

const formatSize = (bytes: number) => {
  if (bytes === 0) return '本地目录'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

const formatDuration = (seconds: number) => {
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return `${minutes}:${rest.toString().padStart(2, '0')}`
}

const progressPercent = (media: Media) => {
  if (media.media_type === 'video' && media.duration && media.progress > 0) {
    return Math.min(100, Math.max(0, Math.round((media.progress / media.duration) * 100)))
  }

  if (media.media_type === 'manga' && media.page_count && media.progress >= 0) {
    return Math.min(100, Math.max(0, Math.round(((media.progress + 1) / media.page_count) * 100)))
  }

  return 0
}

const mangaProgressText = (media: Media) => {
  if (media.media_type !== 'manga' || !media.page_count) return ''
  const current = Math.min(media.page_count, Math.max(1, media.progress + 1))
  return `${current} / ${media.page_count}`
}

const formatMeta = (media: Media) => {
  if (media.media_type === 'video') {
    const parts = []
    if (media.duration) parts.push(formatDuration(media.duration))
    const percent = progressPercent(media)
    if (percent > 0) parts.push(`已看 ${percent}%`)
    return parts.length ? parts.join(' · ') : formatSize(media.file_size)
  }
  if (media.media_type === 'manga' && media.page_count) {
    const percent = progressPercent(media)
    return percent > 0 ? `${mangaProgressText(media)} 页 · ${percent}%` : `${media.page_count} 页`
  }
  if (media.width && media.height) return `${media.width} x ${media.height}`
  return formatSize(media.file_size)
}

const typeLabel = (type: Media['media_type']) => {
  if (type === 'video') return '视频'
  if (type === 'manga') return '漫画'
  return '杂图'
}
</script>

<template>
  <button class="group relative text-left flex flex-col cursor-pointer transition-all duration-300 hover:-translate-y-1 focus:outline-none focus:ring-2 focus:ring-accent/50 rounded-2xl">
    <div class="aspect-[3/4.5] relative overflow-hidden bg-sidebar/70 rounded-2xl border border-white/10 shadow-lg group-hover:shadow-2xl group-hover:shadow-accent/10 group-hover:border-white/20 transition-all duration-300">
      <img
        :src="getThumb(media.cover_path)"
        :alt="media.title"
        class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
        loading="lazy"
      />

      <div class="absolute inset-0 flex items-center justify-center bg-black/0 group-hover:bg-black/25 transition-colors duration-300">
        <div class="opacity-0 group-hover:opacity-100 w-12 h-12 rounded-full bg-black/45 backdrop-blur-md border border-white/20 flex items-center justify-center scale-90 group-hover:scale-100 transition-all duration-300">
          <Play v-if="media.media_type === 'video'" :size="24" fill="white" class="ml-1 text-white" />
          <Book v-else-if="media.media_type === 'manga'" :size="22" class="text-white" />
          <ImageIcon v-else :size="22" class="text-white" />
        </div>
      </div>

      <div class="absolute top-2 right-2 px-2 py-1 rounded-lg bg-black/65 backdrop-blur-md border border-white/10 flex items-center gap-1.5 z-20">
        <Film v-if="media.media_type === 'video'" :size="12" class="text-accent" />
        <Book v-else-if="media.media_type === 'manga'" :size="12" class="text-purple-300" />
        <ImageIcon v-else :size="12" class="text-green-300" />
        <span class="text-[10px] font-bold text-white/90">{{ typeLabel(media.media_type) }}</span>
      </div>

      <div v-if="media.favorite" class="absolute top-2 left-2 w-8 h-8 rounded-lg bg-black/65 backdrop-blur-md border border-white/10 flex items-center justify-center text-amber-300">
        <Star :size="15" fill="currentColor" />
      </div>

      <div v-if="media.media_type === 'manga' && media.page_count && progressPercent(media) > 0" class="absolute left-2 bottom-3 rounded-lg bg-black/65 backdrop-blur-md border border-white/10 px-2 py-1 z-20">
        <span class="text-[10px] font-black text-white/85">{{ mangaProgressText(media) }}</span>
      </div>

      <div v-if="progressPercent(media) > 0" class="absolute inset-x-0 bottom-0 h-1 bg-black/50">
        <div
          class="h-full transition-all"
          :class="media.media_type === 'manga' ? 'bg-purple-300' : 'bg-accent'"
          :style="{ width: `${progressPercent(media)}%` }"
        ></div>
      </div>

      <div v-if="media.is_missing" class="absolute inset-x-2 bottom-2 rounded-lg bg-red-500/85 px-2 py-1 text-center text-xs font-bold text-white">
        文件丢失
      </div>
    </div>

    <div class="mt-3 px-1 tracking-tight min-w-0 w-full">
      <h3 class="text-[15px] font-bold text-white/90 group-hover:text-accent transition-colors line-clamp-1 leading-tight mb-1.5" :title="media.title">
        {{ media.title }}
      </h3>
      <div class="flex items-center gap-2 text-[11px] text-white/45 font-medium tracking-wide min-w-0">
        <span class="px-1.5 py-0.5 rounded bg-white/10 uppercase font-black text-white/65 shrink-0">{{ media.extension.replace('.', '') || 'DIR' }}</span>
        <span class="truncate">{{ formatMeta(media) }}</span>
        <span v-if="media.view_status === 'viewed'" class="ml-auto text-green-300 shrink-0" title="已看">
          <Eye :size="13" />
        </span>
      </div>
    </div>
  </button>
</template>
