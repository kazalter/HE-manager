<script setup lang="ts">
import { computed, ref } from 'vue'
import { AtSign, ChevronDown, Globe2, Plus } from 'lucide-vue-next'
import WnacgPanel from '../components/external/WnacgPanel.vue'
import XImportPanel from '../components/external/XImportPanel.vue'

type SiteKey = 'wnacg' | 'x'

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
]

const activeSite = ref<SiteKey>('wnacg')
const pickerOpen = ref(false)

const activeOption = computed(() => sites.find(site => site.key === activeSite.value) || sites[0])

const selectSite = (key: SiteKey) => {
  activeSite.value = key
  pickerOpen.value = false
}
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

      <!-- Active panel; keep-alive so panel state survives switching tabs -->
      <KeepAlive>
        <WnacgPanel v-if="activeSite === 'wnacg'" key="wnacg" />
        <XImportPanel v-else-if="activeSite === 'x'" key="x" />
      </KeepAlive>
    </main>
  </div>
</template>
