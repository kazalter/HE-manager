<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AtSign, ChevronDown, Globe2, Headphones, Plus, Settings, Check, Loader2 } from 'lucide-vue-next'
import axios from 'axios'
import { API_BASE_URL } from '../config'
import WnacgPanel from '../components/external/WnacgPanel.vue'
import XImportPanel from '../components/external/XImportPanel.vue'
import AsmrPanel from '../components/external/AsmrPanel.vue'

type SiteKey = 'wnacg' | 'x' | 'asmr'

interface SiteOption {
  key: SiteKey
  label: string
  description: string
  icon: any
  badge: string
}

const sites: SiteOption[] = [
  { key: 'wnacg', label: 'WNACG', description: '漫画收藏夹同步与下载', icon: Globe2, badge: 'Cookie 同步' },
  { key: 'x', label: 'X (Twitter)', description: '喜欢媒体一键导入', icon: AtSign, badge: '归档导入' },
  { key: 'asmr', label: 'ASMR.one', description: 'ASMR 标记作品同步与下载', icon: Headphones, badge: 'Token 同步' },
]

const activeSite = ref<SiteKey>('wnacg')
const pickerOpen = ref(false)

const activeOption = computed(() => sites.find(site => site.key === activeSite.value) || sites[0])

const selectSite = (key: SiteKey) => {
  activeSite.value = key
  pickerOpen.value = false
}

const globalProxy = ref('')
const savingProxy = ref(false)
const saveSuccess = ref(false)
const saveError = ref('')

const fetchGlobalProxy = async () => {
  try {
    const res = await axios.get(`${API_BASE_URL}/auto-sync/proxy`)
    globalProxy.value = res.data.proxy || ''
  } catch (err) {
    console.error('Failed to fetch global proxy:', err)
  }
}

const saveGlobalProxy = async () => {
  savingProxy.value = true
  saveSuccess.value = false
  saveError.value = ''
  try {
    const res = await axios.patch(`${API_BASE_URL}/auto-sync/proxy`, {
      proxy: globalProxy.value || null
    })
    globalProxy.value = res.data.proxy || ''
    saveSuccess.value = true
    setTimeout(() => {
      saveSuccess.value = false
    }, 2000)
  } catch (err: any) {
    console.error('Failed to save global proxy:', err)
    saveError.value = err.response?.data?.detail || '保存代理失败'
  } finally {
    savingProxy.value = false
  }
}

onMounted(() => {
  fetchGlobalProxy()
})
</script>

<template>
  <div class="z-10 relative min-h-screen">
    <header class="sticky top-0 z-40 bg-background/75 backdrop-blur-xl border-b border-white/10 px-6 md:px-8 py-5 mb-6">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div class="flex items-baseline gap-3">
          <h1 class="text-2xl md:text-3xl font-black text-white tracking-tight">外部收藏</h1>
          <p class="text-[11px] font-bold text-accent bg-accent/10 px-2 py-0.5 rounded-full border border-accent/20 uppercase tracking-widest">
            MULTI-SOURCE
          </p>
        </div>
        <p class="text-xs text-white/45">
          当前数据源：<span class="text-white/85 font-bold">{{ activeOption.label }}</span>
        </p>
      </div>
    </header>

    <main class="px-6 md:px-8 pb-12 space-y-6">
      <!-- Source picker (collapsible) -->
      <section class="bg-white/[0.04] border border-white/10 rounded-2xl overflow-hidden">
        <button
          type="button"
          @click="pickerOpen = !pickerOpen"
          class="w-full px-5 py-4 flex items-center justify-between gap-4 hover:bg-white/[0.02] transition-all text-left"
        >
          <div class="flex items-center gap-3 min-w-0">
            <div class="w-11 h-11 rounded-xl bg-accent/15 text-accent flex items-center justify-center border border-accent/20 shrink-0">
              <component :is="activeOption.icon" :size="22" />
            </div>
            <div class="min-w-0">
              <p class="text-[10px] font-black text-white/45 uppercase tracking-widest">数据源</p>
              <div class="flex items-center gap-2 mt-0.5">
                <h2 class="text-base font-black text-white truncate">{{ activeOption.label }}</h2>
                <span class="text-[10px] font-bold text-accent bg-accent/10 border border-accent/20 rounded-full px-2 py-0.5 uppercase tracking-widest shrink-0">
                  {{ activeOption.badge }}
                </span>
              </div>
              <p class="text-xs text-white/45 mt-0.5 truncate">{{ activeOption.description }}</p>
            </div>
          </div>
          <div class="flex items-center gap-2 text-xs text-white/55 shrink-0">
            <span class="hidden sm:inline">{{ pickerOpen ? '收起' : '切换数据源' }}</span>
            <ChevronDown :size="18" :class="pickerOpen ? 'rotate-180' : ''" class="transition-transform" />
          </div>
        </button>

        <div
          v-if="pickerOpen"
          class="px-5 pb-5 pt-1 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 border-t border-white/5"
        >
          <button
            v-for="site in sites"
            :key="site.key"
            type="button"
            @click="selectSite(site.key)"
            :class="activeSite === site.key
              ? 'border-accent/60 bg-accent/10 ring-1 ring-accent/30 shadow-lg shadow-accent/5'
              : 'border-white/10 bg-black/20 hover:border-white/25 hover:bg-white/[0.04]'"
            class="text-left rounded-xl border p-4 transition-all flex items-start gap-3"
          >
            <div
              :class="activeSite === site.key ? 'bg-accent/20 border-accent/30 text-accent' : 'bg-white/[0.06] border-white/10 text-white/85'"
              class="w-10 h-10 rounded-lg border flex items-center justify-center shrink-0"
            >
              <component :is="site.icon" :size="18" />
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <p class="text-sm font-black text-white truncate">{{ site.label }}</p>
                <span
                  :class="activeSite === site.key ? 'text-accent' : 'text-white/35'"
                  class="text-[10px] font-bold uppercase tracking-widest shrink-0"
                >
                  {{ site.badge }}
                </span>
              </div>
              <p class="text-xs text-white/55 mt-1 leading-snug line-clamp-2">{{ site.description }}</p>
              <p
                v-if="activeSite === site.key"
                class="text-[10px] font-black text-accent mt-2 uppercase tracking-widest"
              >
                · 当前选中
              </p>
            </div>
          </button>

          <div class="rounded-xl border border-dashed border-white/10 bg-black/15 p-4 flex flex-col items-center justify-center text-center text-white/35 min-h-[88px]">
            <Plus :size="18" class="mb-1.5" />
            <p class="text-[11px] font-bold leading-snug">新增数据源</p>
            <p class="text-[10px] mt-0.5 leading-snug">后续接入新网站后会显示在这里</p>
          </div>
        </div>
      </section>

      <!-- Global Proxy Settings -->
      <section class="bg-white/[0.04] border border-white/10 rounded-2xl p-5 space-y-4">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3 text-white">
            <div class="w-10 h-10 rounded-xl bg-accent/15 text-accent flex items-center justify-center border border-accent/20 text-accent">
              <Settings :size="20" />
            </div>
            <div>
              <h2 class="text-base font-black">全局网络代理</h2>
              <p class="text-xs text-white/45">设置适用于整个收藏页面（WNACG 和 X）的全局代理服务器</p>
            </div>
          </div>
          <div class="flex items-center gap-2">
            <span v-if="savingProxy" class="text-xs text-white/45 flex items-center gap-1">
              <Loader2 :size="12" class="animate-spin text-accent" /> 保存中...
            </span>
            <span v-else-if="saveSuccess" class="text-xs text-emerald-400 flex items-center gap-1">
              <Check :size="12" /> 已保存
            </span>
            <span v-else-if="saveError" class="text-xs text-red-400">
              {{ saveError }}
            </span>
          </div>
        </div>

        <div class="flex flex-col sm:flex-row gap-3">
          <div class="flex-1 relative">
            <input
              v-model="globalProxy"
              type="text"
              placeholder="例如 http://127.0.0.1:7890 (留空表示不使用代理)"
              @blur="saveGlobalProxy"
              @keydown.enter="saveGlobalProxy"
              class="w-full bg-black/20 border border-white/10 rounded-xl px-4 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </div>
          <button
            @click="saveGlobalProxy"
            :disabled="savingProxy"
            class="h-12 px-6 rounded-xl bg-accent text-white font-bold flex items-center justify-center gap-2 hover:brightness-110 disabled:opacity-60 disabled:cursor-not-allowed transition-all shrink-0"
          >
            <Loader2 v-if="savingProxy" :size="14" class="animate-spin" />
            保存设置
          </button>
        </div>
      </section>

      <!-- Active panel; keep-alive so panel state survives switching tabs -->
      <KeepAlive>
        <WnacgPanel v-if="activeSite === 'wnacg'" key="wnacg" />
        <XImportPanel v-else-if="activeSite === 'x'" key="x" />
        <AsmrPanel v-else-if="activeSite === 'asmr'" key="asmr" />
      </KeepAlive>
    </main>
  </div>
</template>
