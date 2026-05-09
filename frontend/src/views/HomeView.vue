<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { ChevronDown, Filter, Search, SortAsc, Star } from 'lucide-vue-next'
import { API_BASE_URL } from '../config'
import type { Media, Tag } from '../types'
import MediaCard from '../components/MediaCard.vue'
import MediaDetail from '../components/MediaDetail.vue'

const props = defineProps<{
  mediaType?: string
}>()

const route = useRoute()
const router = useRouter()
const mediaList = ref<Media[]>([])
const tags = ref<Tag[]>([])
const loading = ref(true)
const selectedMedia = ref<Media | null>(null)
const searchQuery = ref('')
const sortBy = ref<'date' | 'title' | 'rating' | 'opened'>('date')
const selectedTag = ref('')
const tagDropdownOpen = ref(false)
const favoriteOnly = ref(false)
const sourceFilter = ref<'' | 'x' | 'wnacg' | 'local'>('')

const pageTitle = computed(() => {
  if (props.mediaType === 'video') return '所有视频'
  if (props.mediaType === 'manga') return '所有漫画'
  if (props.mediaType === 'image') return '所有杂图'
  if (favoriteOnly.value) return '我的收藏'
  return '全部媒体'
})

const selectedTagLabel = computed(() => selectedTag.value || '全部标签')

const selectTag = (tagName: string) => {
  selectedTag.value = tagName
  tagDropdownOpen.value = false
}

const fetchTags = async () => {
  try {
    const res = await axios.get(`${API_BASE_URL}/tags`)
    tags.value = res.data
  } catch (err) {
    console.error('Failed to fetch tags:', err)
  }
}

const fetchMedia = async () => {
  loading.value = true
  try {
    const params: Record<string, string | boolean | undefined> = {
      media_type: props.mediaType,
      search: searchQuery.value || undefined,
      tag: selectedTag.value || undefined,
      favorite: favoriteOnly.value ? true : undefined,
      source_site: sourceFilter.value || undefined,
      sort: sortBy.value,
    }
    const res = await axios.get(`${API_BASE_URL}/media`, { params })
    mediaList.value = res.data
  } catch (err) {
    console.error('Failed to fetch media:', err)
  } finally {
    loading.value = false
  }
}

const updateMediaInList = (media: Media) => {
  const index = mediaList.value.findIndex(item => item.id === media.id)
  if (index >= 0) {
    mediaList.value[index] = media
  }
  if (selectedMedia.value?.id === media.id) {
    selectedMedia.value = media
  }
  fetchTags()
}

const openMedia = (media: Media, replace = false) => {
  selectedMedia.value = media
  const location = {
    path: route.path,
    query: {
      ...route.query,
      media: String(media.id),
    },
  }

  if (replace) {
    router.replace(location)
  } else {
    router.push(location)
  }
}

const closeMedia = () => {
  selectedMedia.value = null
  const query = { ...route.query }
  delete query.media
  router.push({ path: route.path, query })
}

const syncSelectedMediaFromRoute = async () => {
  const mediaId = Number(route.query.media)
  if (!mediaId) {
    selectedMedia.value = null
    return
  }

  if (selectedMedia.value?.id === mediaId) return

  const localMedia = mediaList.value.find(item => item.id === mediaId)
  if (localMedia) {
    selectedMedia.value = localMedia
    return
  }

  try {
    const res = await axios.get(`${API_BASE_URL}/media/${mediaId}`)
    selectedMedia.value = res.data
  } catch (err) {
    console.error('Failed to fetch selected media:', err)
  }
}

const toggleFavoriteFilter = () => {
  const nextFavorite = !favoriteOnly.value
  router.push({
    path: route.path,
    query: {
      ...route.query,
      favorite: nextFavorite ? 'true' : undefined,
    },
  })
}

let searchTimer: number | undefined
watch(searchQuery, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(fetchMedia, 250)
})

watch([() => props.mediaType, selectedTag, sortBy, favoriteOnly, sourceFilter], fetchMedia)
watch(() => route.query.favorite, value => {
  favoriteOnly.value = value === 'true'
}, { immediate: true })
watch(() => route.query.media, () => {
  syncSelectedMediaFromRoute()
})

const triggerMissingRecheck = async () => {
  try {
    const res = await axios.post(`${API_BASE_URL}/system/recheck-missing`)
    if (res.data.recovered > 0) {
      fetchMedia()
    }
  } catch (err) {
    // silently ignore errors
  }
}

onMounted(async () => {
  await fetchMedia()
  await syncSelectedMediaFromRoute()
  fetchTags()
  triggerMissingRecheck()
})
</script>

<template>
  <div class="z-10 relative">
    <header class="sticky top-0 z-40 bg-background/75 backdrop-blur-xl border-b border-white/10 px-6 md:px-8 py-5 mb-6">
      <div class="flex flex-wrap items-center justify-between gap-5">
        <div class="flex items-baseline gap-3">
          <h1 class="text-2xl md:text-3xl font-black text-white tracking-tight">
            {{ pageTitle }}
          </h1>
          <p class="text-[11px] font-bold text-accent bg-accent/10 px-2 py-0.5 rounded-full border border-accent/20 uppercase tracking-widest">
            {{ mediaList.length }} ITEMS
          </p>
        </div>

        <div class="flex flex-1 min-w-[260px] max-w-3xl gap-3">
          <div class="relative flex-1 group">
            <Search class="absolute left-4 top-1/2 -translate-y-1/2 text-white/30 group-focus-within:text-accent transition-colors" :size="18" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索标题、文件名..."
              class="w-full bg-white/5 border border-white/10 rounded-xl pl-12 pr-4 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50 focus:bg-white/10 transition-all"
            />
          </div>

          <button
            @click="toggleFavoriteFilter"
            :class="favoriteOnly ? 'bg-accent text-white' : 'bg-white/5 text-white/55 hover:text-white'"
            class="w-12 h-12 rounded-xl border border-white/10 flex items-center justify-center transition-all"
            title="只看收藏"
          >
            <Star :size="18" :fill="favoriteOnly ? 'currentColor' : 'none'" />
          </button>
        </div>
      </div>

      <div class="mt-4 flex flex-wrap items-center gap-3 text-sm">
        <div class="flex items-center gap-2 text-white/45">
          <Filter :size="16" />
          <span>筛选</span>
        </div>

        <div class="relative">
          <button
            @click="tagDropdownOpen = !tagDropdownOpen"
            class="min-w-36 bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-accent/50 flex items-center justify-between gap-3 hover:bg-white/10 transition-all"
          >
            <span class="truncate">{{ selectedTagLabel }}</span>
            <ChevronDown :size="15" :class="tagDropdownOpen ? 'rotate-180' : ''" class="transition-transform text-white/45" />
          </button>
          <div
            v-if="tagDropdownOpen"
            class="absolute left-0 top-full mt-2 z-50 min-w-48 max-h-72 overflow-y-auto rounded-2xl border border-white/10 bg-sidebar/95 backdrop-blur-xl shadow-2xl p-1"
          >
            <button
              @click="selectTag('')"
              :class="selectedTag === '' ? 'bg-accent text-white' : 'text-white/65 hover:text-white hover:bg-white/8'"
              class="w-full rounded-xl px-3 py-2 text-left text-sm font-bold transition-all"
            >
              全部标签
            </button>
            <button
              v-for="tag in tags"
              :key="tag.id"
              @click="selectTag(tag.name)"
              :class="selectedTag === tag.name ? 'bg-accent text-white' : 'text-white/65 hover:text-white hover:bg-white/8'"
              class="w-full rounded-xl px-3 py-2 text-left text-sm font-bold transition-all"
            >
              {{ tag.name }}
            </button>
          </div>
          <template v-if="false">
          <option value="" class="bg-white text-slate-950">全部标签</option>
          <option v-for="tag in tags" :key="tag.id" :value="tag.name" class="bg-white text-slate-950">{{ tag.name }}</option>
          </template>
        </div>

        <div class="flex bg-white/5 rounded-xl p-1 border border-white/10">
          <button
            @click="sourceFilter = ''"
            :class="sourceFilter === '' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
          >
            全部来源
          </button>
          <button
            @click="sourceFilter = 'local'"
            :class="sourceFilter === 'local' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
            title="本地扫描的媒体（无外部来源）"
          >
            本地
          </button>
          <button
            @click="sourceFilter = 'x'"
            :class="sourceFilter === 'x' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
            title="X (Twitter) 导入的媒体"
          >
            X
          </button>
          <button
            @click="sourceFilter = 'wnacg'"
            :class="sourceFilter === 'wnacg' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
            title="wnacg 下载的媒体"
          >
            wnacg
          </button>
        </div>

        <div class="flex bg-white/5 rounded-xl p-1 border border-white/10">
          <button
            @click="sortBy = 'date'"
            :class="sortBy === 'date' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
          >
            最近添加
          </button>
          <button
            @click="sortBy = 'opened'"
            :class="sortBy === 'opened' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
          >
            最近打开
          </button>
          <button
            @click="sortBy = 'rating'"
            :class="sortBy === 'rating' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all"
          >
            评分
          </button>
          <button
            @click="sortBy = 'title'"
            :class="sortBy === 'title' ? 'bg-accent text-white' : 'text-white/45 hover:text-white'"
            class="px-3 py-1.5 rounded-lg text-xs font-bold transition-all flex items-center gap-1"
          >
            <SortAsc :size="13" /> 名称
          </button>
        </div>
      </div>
    </header>

    <div class="px-6 md:px-8 pb-12">
      <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6 gap-5 md:gap-7">
        <div v-for="i in 12" :key="i" class="aspect-[3/4.5] bg-white/5 animate-pulse rounded-2xl border border-white/5"></div>
      </div>

      <div
        v-else-if="mediaList.length > 0"
        class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6 gap-5 md:gap-7"
      >
        <MediaCard
          v-for="item in mediaList"
          :key="item.id"
          :media="item"
          @click="openMedia(item)"
        />
      </div>

      <div v-else class="flex flex-col items-center justify-center py-32 text-white/35 text-center">
        <div class="w-16 h-16 rounded-2xl bg-white/5 flex items-center justify-center mb-5 border border-white/10">
          <Search :size="28" />
        </div>
        <p class="text-lg font-bold mb-2">没有找到匹配的媒体</p>
        <p class="text-sm">可以去设置页添加扫描目录，或调整当前筛选条件。</p>
      </div>
    </div>

    <MediaDetail
      v-if="selectedMedia"
      :initial-media="selectedMedia"
      :all-media="mediaList"
      @close="closeMedia"
      @updated="updateMediaInList"
      @navigate="openMedia($event, true)"
    />
  </div>
</template>
