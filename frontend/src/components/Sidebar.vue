<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { BarChart3, Book, ChevronLeft, ChevronRight, CopyMinus, Film, Globe2, Headphones, Home, Image as ImageIcon, LogOut, Palette, Settings as SettingsIcon, Sparkles, Star, Users, RefreshCw } from 'lucide-vue-next'
import type { User } from '../types'

const props = defineProps<{
  collapsed: boolean
  user: User | null
}>()

const emit = defineEmits(['update:collapsed', 'logout'])
const route = useRoute()

const baseLinkClass = 'flex items-center gap-4 px-3 py-3 rounded-xl transition-all duration-200 hover:bg-white/5 group text-white/55 hover:text-white'
const activeLinkClass = 'bg-white/10 text-white shadow-inner border border-white/5'
const bottomLinkClass = 'flex items-center gap-4 px-3 py-3 rounded-xl transition-all hover:bg-white/5 group text-white/60 hover:text-white'
const isHomeActive = computed(() => route.path === '/' && route.query.favorite !== 'true')
const isFavoriteActive = computed(() => route.path === '/' && route.query.favorite === 'true')

const isRefreshing = ref(false)
const refreshApp = () => {
  if (isRefreshing.value) return
  isRefreshing.value = true
  setTimeout(() => {
    window.location.reload()
  }, 400)
}

const toggle = () => {
  emit('update:collapsed', !props.collapsed)
}
</script>

<template>
  <aside
    :class="collapsed ? 'w-20' : 'w-64'"
    class="h-screen bg-sidebar/85 backdrop-blur-2xl border-r border-white/10 flex flex-col p-4 shadow-2xl transition-all duration-300 ease-in-out relative group"
  >
    <button
      @click="toggle"
      class="absolute -right-3 top-8 w-6 h-6 rounded-full bg-accent border border-white/20 flex items-center justify-center text-white shadow-lg hover:scale-110 active:scale-95 transition-all z-50 opacity-0 group-hover:opacity-100"
      :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
    >
      <ChevronLeft v-if="!collapsed" :size="14" />
      <ChevronRight v-else :size="14" />
    </button>

    <div class="mb-10 px-2 flex items-center gap-3 overflow-hidden whitespace-nowrap">
      <div class="shrink-0 w-8 h-8 rounded-lg bg-accent shadow-lg shadow-accent/20 flex items-center justify-center overflow-hidden">
        <span class="text-sm font-black text-white">HE</span>
      </div>
      
      <div v-if="!collapsed" class="flex items-center gap-2.5 flex-1 min-w-0">
        <h1 class="text-xl font-black tracking-tight text-white truncate transition-opacity duration-300">
          HE Manager
        </h1>
        <button 
          @click="refreshApp" 
          class="text-white/30 hover:text-accent hover:bg-accent/15 p-2 rounded-full transition-all group shrink-0 flex items-center justify-center"
          title="刷新页面"
        >
          <RefreshCw :size="17" :stroke-width="2.5" :class="{ 'animate-spin': isRefreshing }" class="group-hover:rotate-180 transition-transform duration-500" />
        </button>
      </div>
    </div>

    <nav class="flex-1 space-y-2 overflow-x-hidden">
      <router-link to="/" :class="[baseLinkClass, isHomeActive ? activeLinkClass : '']" title="全部媒体">
        <Home :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">全部媒体</span>
      </router-link>

      <router-link to="/type/video" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="视频">
        <Film :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">视频</span>
      </router-link>

      <router-link to="/type/manga" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="漫画">
        <Book :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">漫画</span>
      </router-link>

      <router-link to="/type/image" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="杂图">
        <ImageIcon :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">杂图</span>
      </router-link>

      <router-link to="/type/audio" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="音频">
        <Headphones :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">音频</span>
      </router-link>

      <router-link :to="{ path: '/', query: { favorite: 'true' } }" :class="[baseLinkClass, isFavoriteActive ? activeLinkClass : '']" title="收藏">
        <Star :size="22" class="group-hover:scale-110 transition-transform shrink-0" :fill="isFavoriteActive ? 'currentColor' : 'none'" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">收藏</span>
      </router-link>

      <router-link to="/external" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="外部收藏">
        <Globe2 :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">外部收藏</span>
      </router-link>

      <router-link to="/recommend" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="AI 推荐">
        <Sparkles :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">AI 推荐</span>
      </router-link>

      <router-link to="/creators" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="创作者">
        <Palette :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">创作者</span>
      </router-link>

      <router-link to="/stats" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="统计">
        <BarChart3 :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">统计</span>
      </router-link>

      <router-link to="/dedup" :class="baseLinkClass" active-class="bg-white/10 text-white shadow-inner border border-white/5" title="重复管理">
        <CopyMinus :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">重复管理</span>
      </router-link>
    </nav>

    <div class="mt-auto space-y-2 pt-6 border-t border-white/5 overflow-hidden">
      <router-link to="/settings" :class="bottomLinkClass" active-class="bg-white/10 text-white" title="设置">
        <SettingsIcon :size="22" class="group-hover:rotate-45 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">设置</span>
      </router-link>

      <router-link v-if="user?.is_admin" to="/users" :class="bottomLinkClass" active-class="bg-white/10 text-white" title="用户管理">
        <Users :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">用户管理</span>
      </router-link>

      <button
        @click="emit('logout')"
        class="w-full flex items-center gap-4 px-3 py-3 rounded-xl transition-all hover:bg-white/5 group text-white/60 hover:text-white text-left"
        title="退出登录"
      >
        <LogOut :size="22" class="group-hover:scale-110 transition-transform shrink-0" />
        <span v-if="!collapsed" class="font-medium whitespace-nowrap overflow-hidden">退出登录</span>
      </button>
    </div>
  </aside>
</template>
