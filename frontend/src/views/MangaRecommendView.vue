<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import axios from 'axios'
import { AlertTriangle, ChevronDown, KeyRound, Loader2, RefreshCw, Save, Send, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { API_BASE_URL } from '../config'
import type { AiRecommendationStatus, MangaMetadataJob, MangaMetadataStats, MangaProfileJob, MangaProfileStats, MangaRecommendationResponse, Media } from '../types'
import MediaCard from '../components/MediaCard.vue'
import MediaDetail from '../components/MediaDetail.vue'

const query = ref('')
const limit = ref(12)
const avoidInput = ref('')
const preferredInput = ref('')
const loading = ref(false)
const statusLoading = ref(true)
const deepseekSaving = ref(false)
const error = ref('')
const deepseekApiKey = ref('')
const deepseekModel = ref('deepseek-chat')
const deepseekBaseUrl = ref('https://api.deepseek.com')
const deepseekMessage = ref('')
const modelInputRef = ref<HTMLInputElement | null>(null)
const showDeepSeekPanel = ref(false)
const result = ref<MangaRecommendationResponse | null>(null)
const aiStatus = ref<AiRecommendationStatus | null>(null)
const metadataStats = ref<MangaMetadataStats | null>(null)
const metadataJob = ref<MangaMetadataJob | null>(null)
const metadataLoading = ref(false)
const profileStats = ref<MangaProfileStats | null>(null)
const profileJob = ref<MangaProfileJob | null>(null)
const profileLoading = ref(false)
const selectedMedia = ref<Media | null>(null)

const deepseekModelOptions = [
  { id: 'deepseek-chat', label: 'deepseek-chat', description: '主推荐' },
  { id: 'deepseek-reasoner', label: 'deepseek-reasoner', description: '推理' },
]

const parseCsv = (value: string) => value
  .split(/[,，\n]/)
  .map(item => item.trim())
  .filter(Boolean)

const syncDeepSeekForm = (status: AiRecommendationStatus) => {
  deepseekModel.value = status.model || 'deepseek-chat'
  deepseekBaseUrl.value = status.base_url || 'https://api.deepseek.com'
}

const isKnownDeepSeekModel = (model: string) => deepseekModelOptions.some(option => option.id === model)

const selectDeepSeekModel = (model: string) => {
  deepseekModel.value = model
}

const focusCustomModel = async () => {
  await nextTick()
  modelInputRef.value?.focus()
}

const fetchStatus = async () => {
  statusLoading.value = true
  try {
    const res = await axios.get<AiRecommendationStatus>(`${API_BASE_URL}/ai/recommendations/status`)
    aiStatus.value = res.data
    syncDeepSeekForm(res.data)
  } catch (err) {
    console.error('Failed to fetch AI recommendation status:', err)
  } finally {
    statusLoading.value = false
  }
}

const saveDeepSeekConfig = async () => {
  deepseekSaving.value = true
  deepseekMessage.value = ''
  try {
    const res = await axios.put<AiRecommendationStatus>(`${API_BASE_URL}/ai/recommendations/config`, {
      api_key: deepseekApiKey.value.trim() || undefined,
      model: deepseekModel.value.trim() || 'deepseek-chat',
      base_url: deepseekBaseUrl.value.trim() || 'https://api.deepseek.com',
    })
    aiStatus.value = res.data
    syncDeepSeekForm(res.data)
    deepseekApiKey.value = ''
    deepseekMessage.value = '已保存'
    window.setTimeout(() => {
      if (deepseekMessage.value === '已保存') deepseekMessage.value = ''
    }, 2000)
  } catch (err: any) {
    deepseekMessage.value = err?.response?.data?.detail || '保存失败'
  } finally {
    deepseekSaving.value = false
  }
}

const clearDeepSeekKey = async () => {
  if (!confirm('清除已保存的 DeepSeek API Key？')) return
  deepseekSaving.value = true
  deepseekMessage.value = ''
  try {
    const res = await axios.put<AiRecommendationStatus>(`${API_BASE_URL}/ai/recommendations/config`, {
      model: deepseekModel.value.trim() || 'deepseek-chat',
      base_url: deepseekBaseUrl.value.trim() || 'https://api.deepseek.com',
      clear_api_key: true,
    })
    aiStatus.value = res.data
    syncDeepSeekForm(res.data)
    deepseekApiKey.value = ''
    deepseekMessage.value = '已清除'
  } catch (err: any) {
    deepseekMessage.value = err?.response?.data?.detail || '清除失败'
  } finally {
    deepseekSaving.value = false
  }
}

const fetchProfileStats = async () => {
  try {
    const res = await axios.get(`${API_BASE_URL}/recommend/manga-profiles/stats`)
    profileStats.value = res.data
  } catch (err) {
    console.error('Failed to fetch manga profile stats:', err)
  }
}

const fetchMetadataStats = async () => {
  try {
    const res = await axios.get(`${API_BASE_URL}/recommend/manga-metadata/stats`)
    metadataStats.value = res.data
  } catch (err) {
    console.error('Failed to fetch manga metadata stats:', err)
  }
}

const pollMetadataJob = async (jobId: string) => {
  try {
    const res = await axios.get(`${API_BASE_URL}/recommend/manga-metadata/jobs/${jobId}`)
    metadataJob.value = res.data
    if (['queued', 'running'].includes(res.data.status)) {
      window.setTimeout(() => pollMetadataJob(jobId), 1200)
    } else {
      metadataLoading.value = false
      fetchMetadataStats()
    }
  } catch (err) {
    metadataLoading.value = false
    console.error('Failed to poll metadata job:', err)
  }
}

const startMetadataAnalysis = async (force = false) => {
  if (metadataLoading.value) return
  metadataLoading.value = true
  try {
    const res = await axios.post(`${API_BASE_URL}/recommend/manga-metadata/analyze`, {
      limit: 200,
      force,
    })
    metadataJob.value = res.data
    pollMetadataJob(res.data.job_id)
  } catch (err) {
    metadataLoading.value = false
    console.error('Failed to start metadata analysis:', err)
  }
}

const pollProfileJob = async (jobId: string) => {
  try {
    const res = await axios.get(`${API_BASE_URL}/recommend/manga-profiles/jobs/${jobId}`)
    profileJob.value = res.data
    if (['queued', 'running'].includes(res.data.status)) {
      window.setTimeout(() => pollProfileJob(jobId), 1500)
    } else {
      profileLoading.value = false
      fetchProfileStats()
    }
  } catch (err) {
    profileLoading.value = false
    console.error('Failed to poll profile job:', err)
  }
}

const startProfileAnalysis = async (force = false) => {
  if (profileLoading.value) return
  profileLoading.value = true
  try {
    const res = await axios.post(`${API_BASE_URL}/recommend/manga-profiles/analyze`, {
      limit: 50,
      sample_count: 10,
      force,
    })
    profileJob.value = res.data
    pollProfileJob(res.data.job_id)
  } catch (err) {
    profileLoading.value = false
    console.error('Failed to start profile analysis:', err)
  }
}

const requestRecommendations = async () => {
  if (!query.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await axios.post(`${API_BASE_URL}/recommend/manga`, {
      query: query.value.trim(),
      limit: limit.value,
      avoid_tags: parseCsv(avoidInput.value),
      preferred_tags: parseCsv(preferredInput.value),
    })
    result.value = res.data
  } catch (err: any) {
    error.value = err?.response?.data?.detail || '推荐失败'
  } finally {
    loading.value = false
  }
}

const reroll = () => {
  requestRecommendations()
}

onMounted(() => {
  fetchStatus()
  fetchMetadataStats()
  fetchProfileStats()
})
</script>

<template>
  <div class="relative z-10 min-h-full">
    <header class="sticky top-0 z-30 bg-background/75 backdrop-blur-xl border-b border-white/10 px-6 md:px-8 py-5">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div class="min-w-0">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-xl bg-accent/15 border border-accent/25 flex items-center justify-center text-accent">
              <Sparkles :size="21" />
            </div>
            <div>
              <h1 class="text-2xl md:text-3xl font-black text-white tracking-tight">AI 漫画推荐</h1>
              <p class="text-xs text-white/45 mt-1">
                {{ aiStatus?.deepseek_configured ? `DeepSeek · ${aiStatus.model}` : '本地规则模式' }}
              </p>
            </div>
          </div>
        </div>

        <div class="relative flex items-center gap-3">
          <button
            type="button"
            @click="showDeepSeekPanel = !showDeepSeekPanel"
            :class="aiStatus?.deepseek_configured ? 'border-emerald-400/25 text-emerald-200 bg-emerald-400/10 hover:bg-emerald-400/15' : 'border-amber-300/25 text-amber-100 bg-amber-300/10 hover:bg-amber-300/15'"
            class="px-3 py-2 rounded-xl border text-xs font-bold flex items-center gap-2 transition-all"
          >
            <ShieldCheck v-if="aiStatus?.deepseek_configured" :size="15" />
            <AlertTriangle v-else :size="15" />
            <span>{{ statusLoading ? '检查中' : (aiStatus?.deepseek_configured ? '已配置' : '未配置 API Key') }}</span>
            <ChevronDown :size="14" class="transition-transform" :class="{ 'rotate-180': showDeepSeekPanel }" />
          </button>
          <button
            @click="fetchStatus"
            class="w-10 h-10 rounded-xl border border-white/10 bg-white/5 text-white/55 hover:text-white hover:bg-white/10 flex items-center justify-center transition-all"
            title="刷新状态"
          >
            <RefreshCw :size="17" />
          </button>

          <div
            v-if="showDeepSeekPanel"
            class="absolute right-0 top-12 z-50 w-[calc(100vw-3rem)] max-w-[430px] max-h-[calc(100vh-120px)] overflow-y-auto rounded-2xl border border-white/10 bg-background/95 p-4 shadow-2xl shadow-black/40 backdrop-blur-xl"
          >
            <div class="flex items-start justify-between gap-4 mb-4">
              <div class="flex items-center gap-3 min-w-0">
                <div class="w-10 h-10 rounded-xl bg-accent/15 border border-accent/20 flex items-center justify-center text-accent shrink-0">
                  <KeyRound :size="19" />
                </div>
                <div class="min-w-0">
                  <h2 class="text-lg font-black text-white">DeepSeek</h2>
                  <p class="text-xs text-white/40 mt-1">推荐模型配置</p>
                </div>
              </div>
              <div
                :class="aiStatus?.deepseek_configured ? 'border-emerald-400/25 text-emerald-200 bg-emerald-400/10' : 'border-white/10 text-white/45 bg-white/5'"
                class="px-3 py-2 rounded-xl border text-xs font-bold shrink-0"
              >
                {{ aiStatus?.deepseek_configured ? (aiStatus.env_key_present ? '环境变量' : '已配置') : '未配置' }}
              </div>
            </div>

            <label class="block text-xs font-bold text-white/55 mb-2">API Key</label>
            <input
              v-model="deepseekApiKey"
              type="password"
              class="w-full rounded-xl border border-white/10 bg-black/20 px-4 py-3 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
              :placeholder="aiStatus?.key_saved || aiStatus?.env_key_present ? '已配置，留空则保持不变' : '粘贴 DeepSeek API Key'"
              autocomplete="off"
            />

            <div class="grid grid-cols-1 gap-3 mt-4">
              <div>
                <label class="block text-xs font-bold text-white/55 mb-2">模型快捷选择</label>
                <div class="grid grid-cols-1 gap-2">
                  <button
                    v-for="option in deepseekModelOptions"
                    :key="option.id"
                    type="button"
                    @click="selectDeepSeekModel(option.id)"
                    :class="deepseekModel === option.id ? 'border-accent/70 bg-accent/15 text-white shadow-[0_0_0_1px_rgba(255,255,255,0.04)]' : 'border-white/10 bg-black/20 text-white/62 hover:border-white/18 hover:bg-white/[0.07] hover:text-white'"
                    class="group w-full rounded-xl border px-3 py-2.5 text-left transition-all"
                  >
                    <span class="flex items-center justify-between gap-3">
                      <span class="min-w-0">
                        <span class="block text-sm font-black leading-5 truncate">{{ option.label }}</span>
                        <span class="block text-[11px] leading-4 text-white/35">{{ option.id }}</span>
                      </span>
                      <span
                        :class="deepseekModel === option.id ? 'border-accent/35 bg-accent/20 text-accent' : 'border-white/10 bg-white/5 text-white/35 group-hover:text-white/65'"
                        class="shrink-0 rounded-lg border px-2 py-1 text-[11px] font-bold transition-all"
                      >
                        {{ option.description }}
                      </span>
                    </span>
                  </button>

                  <button
                    type="button"
                    @click="focusCustomModel"
                    :class="!isKnownDeepSeekModel(deepseekModel) ? 'border-accent/70 bg-accent/15 text-white' : 'border-white/10 bg-black/20 text-white/62 hover:border-white/18 hover:bg-white/[0.07] hover:text-white'"
                    class="w-full rounded-xl border px-3 py-2.5 text-left transition-all"
                  >
                    <span class="flex items-center justify-between gap-3">
                      <span class="min-w-0">
                        <span class="block text-sm font-black leading-5">自定义模型</span>
                        <span class="block text-[11px] leading-4 text-white/35 truncate">{{ deepseekModel || '手动输入 Model ID' }}</span>
                      </span>
                      <span class="shrink-0 rounded-lg border border-white/10 bg-white/5 px-2 py-1 text-[11px] font-bold text-white/35">
                        手动
                      </span>
                    </span>
                  </button>
                </div>
              </div>
              <div>
                <label class="block text-xs font-bold text-white/55 mb-2">Model ID</label>
                <input
                  ref="modelInputRef"
                  v-model="deepseekModel"
                  class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2.5 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
                  placeholder="deepseek-chat"
                />
                <p class="mt-2 text-[11px] leading-4 text-white/35">
                  官方模型：deepseek-chat（V3 通用）、deepseek-reasoner（推理）。其它 ID 可自行填入。
                </p>
              </div>
              <div>
                <label class="block text-xs font-bold text-white/55 mb-2">Base URL</label>
                <input
                  v-model="deepseekBaseUrl"
                  class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2.5 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
                  placeholder="https://api.deepseek.com"
                />
              </div>
            </div>

            <p class="mt-3 text-xs text-white/35">
              Key 保存在本机 <span class="font-mono">backend/instance/deepseek.json</span>。
            </p>

            <div class="mt-4 flex items-center justify-between gap-3">
              <span
                v-if="deepseekMessage"
                class="text-xs font-bold"
                :class="deepseekMessage.includes('失败') ? 'text-rose-300' : 'text-emerald-300'"
              >
                {{ deepseekMessage }}
              </span>
              <span v-else class="text-xs text-white/30">
                {{ aiStatus?.deepseek_configured ? `当前：${aiStatus.model}` : '未配置时会使用本地规则' }}
              </span>

              <div class="flex items-center gap-2 shrink-0">
                <button
                  v-if="aiStatus?.key_saved"
                  @click="clearDeepSeekKey"
                  :disabled="deepseekSaving"
                  class="h-10 px-3 rounded-xl border border-white/10 bg-white/5 text-xs font-bold text-white/60 hover:text-white hover:bg-white/10 disabled:opacity-45"
                >
                  清除
                </button>
                <button
                  @click="saveDeepSeekConfig"
                  :disabled="deepseekSaving"
                  class="h-10 px-4 rounded-xl bg-accent text-white text-sm font-black flex items-center justify-center gap-2 hover:brightness-110 active:scale-[0.99] disabled:opacity-45 transition-all"
                >
                  <Loader2 v-if="deepseekSaving" :size="16" class="animate-spin" />
                  <Save v-else :size="16" />
                  保存
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </header>

    <div class="px-6 md:px-8 py-7 grid grid-cols-1 xl:grid-cols-[390px_minmax(0,1fr)] gap-7">
      <section class="space-y-4">
        <div class="rounded-2xl border border-white/10 bg-white/[0.04] p-5 shadow-2xl shadow-black/10">
          <div class="flex items-center justify-between gap-3 mb-4">
            <div>
              <h2 class="text-lg font-black text-white">偏好</h2>
              <p class="text-xs text-white/40 mt-1">输入口味、雷点、篇幅或类似作品</p>
            </div>
          </div>

          <label class="block text-xs font-bold text-white/55 mb-2">想看什么</label>
          <textarea
            v-model="query"
            rows="5"
            class="w-full resize-none rounded-xl border border-white/10 bg-black/20 px-4 py-3 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
            placeholder="例如：短篇、画风精细、剧情轻松、不要太黑暗"
          ></textarea>

          <div class="grid grid-cols-2 gap-3 mt-4">
            <div>
              <label class="block text-xs font-bold text-white/55 mb-2">偏好标签</label>
              <input
                v-model="preferredInput"
                class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2.5 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
                placeholder="逗号分隔"
              />
            </div>
            <div>
              <label class="block text-xs font-bold text-white/55 mb-2">排除标签</label>
              <input
                v-model="avoidInput"
                class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2.5 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
                placeholder="逗号分隔"
              />
            </div>
          </div>

          <div class="mt-4">
            <label class="block text-xs font-bold text-white/55 mb-2">数量</label>
            <input
              v-model.number="limit"
              type="number"
              min="1"
              class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2.5 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
          </div>

          <button
            @click="requestRecommendations"
            :disabled="loading || !query.trim()"
            class="mt-5 w-full h-12 rounded-xl bg-accent text-white font-black flex items-center justify-center gap-2 hover:brightness-110 active:scale-[0.99] disabled:opacity-45 disabled:cursor-not-allowed transition-all"
          >
            <Loader2 v-if="loading" :size="18" class="animate-spin" />
            <Send v-else :size="18" />
            开始推荐
          </button>
        </div>

        <div v-if="result" class="rounded-2xl border border-white/10 bg-white/[0.04] p-5">
          <div class="grid grid-cols-2 gap-3 text-center">
            <div>
              <p class="text-xl font-black text-white">{{ result.candidate_count }}</p>
              <p class="text-[11px] text-white/40 mt-1">候选</p>
            </div>
            <div>
              <p class="text-xl font-black text-white">{{ result.recommendations.length }}</p>
              <p class="text-[11px] text-white/40 mt-1">结果</p>
            </div>
          </div>
          <p v-if="result.message" class="mt-4 text-xs leading-5 text-amber-100/75 bg-amber-300/10 border border-amber-300/20 rounded-xl p-3">
            {{ result.message }}
          </p>
        </div>

        <div class="rounded-2xl border border-white/10 bg-white/[0.04] p-5">
          <div class="flex items-start justify-between gap-4 mb-4">
            <div>
              <h2 class="text-lg font-black text-white">元数据画像</h2>
              <p class="text-xs text-white/40 mt-1">从标题、来源和站点条目提取标签</p>
            </div>
            <button
              @click="fetchMetadataStats"
              class="w-9 h-9 rounded-xl border border-white/10 bg-white/5 text-white/55 hover:text-white hover:bg-white/10 flex items-center justify-center"
              title="刷新元数据统计"
            >
              <RefreshCw :size="16" />
            </button>
          </div>

          <div class="grid grid-cols-3 gap-3 text-center">
            <div>
              <p class="text-xl font-black text-white">{{ metadataStats?.profiled ?? 0 }}</p>
              <p class="text-[11px] text-white/40 mt-1">已画像</p>
            </div>
            <div>
              <p class="text-xl font-black text-white">{{ metadataStats?.missing ?? 0 }}</p>
              <p class="text-[11px] text-white/40 mt-1">待分析</p>
            </div>
            <div>
              <p class="text-xl font-black text-white">{{ metadataStats?.stale ?? 0 }}</p>
              <p class="text-[11px] text-white/40 mt-1">需更新</p>
            </div>
          </div>

          <div v-if="metadataJob" class="mt-4 rounded-xl border border-white/10 bg-black/20 p-3">
            <div class="flex justify-between gap-3 text-xs text-white/60">
              <span class="font-bold text-white/80">{{ metadataJob.message || metadataJob.status }}</span>
              <span>{{ metadataJob.completed }} / {{ metadataJob.total }}</span>
            </div>
            <div class="mt-2 h-2 rounded-full bg-white/10 overflow-hidden">
              <div
                class="h-full bg-accent transition-all"
                :style="{ width: `${metadataJob.total ? Math.round((metadataJob.completed / metadataJob.total) * 100) : 0}%` }"
              ></div>
            </div>
            <p v-if="metadataJob.current_title" class="mt-2 text-xs text-white/35 line-clamp-1">{{ metadataJob.current_title }}</p>
          </div>

          <div class="mt-4 grid grid-cols-2 gap-3">
            <button
              @click="startMetadataAnalysis(false)"
              :disabled="metadataLoading"
              class="h-10 rounded-xl bg-white/5 border border-white/10 text-sm font-bold text-white/70 hover:text-white hover:bg-white/10 disabled:opacity-45 flex items-center justify-center gap-2"
            >
              <Loader2 v-if="metadataLoading" :size="16" class="animate-spin" />
              分析待处理
            </button>
            <button
              @click="startMetadataAnalysis(true)"
              :disabled="metadataLoading"
              class="h-10 rounded-xl bg-white/5 border border-white/10 text-sm font-bold text-white/70 hover:text-white hover:bg-white/10 disabled:opacity-45"
            >
              重新分析
            </button>
          </div>
        </div>

        <div class="rounded-2xl border border-white/10 bg-white/[0.04] p-5">
          <div class="flex items-start justify-between gap-4 mb-4">
            <div>
              <h2 class="text-lg font-black text-white">内容画像</h2>
              <p class="text-xs text-white/40 mt-1">可选：抽样页面后生成视觉特征</p>
            </div>
            <button
              @click="fetchProfileStats"
              class="w-9 h-9 rounded-xl border border-white/10 bg-white/5 text-white/55 hover:text-white hover:bg-white/10 flex items-center justify-center"
              title="刷新内容画像统计"
            >
              <RefreshCw :size="16" />
            </button>
          </div>

          <div class="grid grid-cols-3 gap-3 text-center">
            <div>
              <p class="text-xl font-black text-white">{{ profileStats?.profiled ?? 0 }}</p>
              <p class="text-[11px] text-white/40 mt-1">已画像</p>
            </div>
            <div>
              <p class="text-xl font-black text-white">{{ profileStats?.missing ?? 0 }}</p>
              <p class="text-[11px] text-white/40 mt-1">待分析</p>
            </div>
            <div>
              <p class="text-xl font-black text-white">{{ profileStats?.stale ?? 0 }}</p>
              <p class="text-[11px] text-white/40 mt-1">需更新</p>
            </div>
          </div>

          <div v-if="profileJob" class="mt-4 rounded-xl border border-white/10 bg-black/20 p-3">
            <div class="flex justify-between gap-3 text-xs text-white/60">
              <span class="font-bold text-white/80">{{ profileJob.message || profileJob.status }}</span>
              <span>{{ profileJob.completed }} / {{ profileJob.total }}</span>
            </div>
            <div class="mt-2 h-2 rounded-full bg-white/10 overflow-hidden">
              <div
                class="h-full bg-accent transition-all"
                :style="{ width: `${profileJob.total ? Math.round((profileJob.completed / profileJob.total) * 100) : 0}%` }"
              ></div>
            </div>
            <p v-if="profileJob.current_title" class="mt-2 text-xs text-white/35 line-clamp-1">{{ profileJob.current_title }}</p>
          </div>

          <div class="mt-4 grid grid-cols-2 gap-3">
            <button
              @click="startProfileAnalysis(false)"
              :disabled="profileLoading"
              class="h-10 rounded-xl bg-white/5 border border-white/10 text-sm font-bold text-white/70 hover:text-white hover:bg-white/10 disabled:opacity-45 flex items-center justify-center gap-2"
            >
              <Loader2 v-if="profileLoading" :size="16" class="animate-spin" />
              分析待处理
            </button>
            <button
              @click="startProfileAnalysis(true)"
              :disabled="profileLoading"
              class="h-10 rounded-xl bg-white/5 border border-white/10 text-sm font-bold text-white/70 hover:text-white hover:bg-white/10 disabled:opacity-45"
            >
              重新分析
            </button>
          </div>
        </div>
      </section>

      <section class="min-w-0">
        <div class="flex flex-wrap items-center justify-between gap-3 mb-5">
          <div>
            <h2 class="text-xl font-black text-white">推荐结果</h2>
            <p class="text-xs text-white/40 mt-1">点击卡片可打开详情</p>
          </div>
          <button
            v-if="result?.recommendations.length"
            @click="reroll"
            :disabled="loading"
            class="h-10 px-4 rounded-xl border border-white/10 bg-white/5 text-sm font-bold text-white/70 hover:text-white hover:bg-white/10 flex items-center gap-2 disabled:opacity-45"
          >
            <RefreshCw :size="16" :class="{ 'animate-spin': loading }" />
            换一批
          </button>
        </div>

        <div v-if="error" class="rounded-2xl border border-rose-400/20 bg-rose-400/10 p-5 text-rose-100 text-sm">
          {{ error }}
        </div>

        <div v-else-if="loading" class="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-5">
          <div v-for="i in limit" :key="i" class="aspect-[3/4.5] rounded-2xl border border-white/5 bg-white/5 animate-pulse"></div>
        </div>

        <div v-else-if="result?.recommendations.length" class="grid grid-cols-1 md:grid-cols-2 2xl:grid-cols-3 gap-5">
          <article
            v-for="item in result.recommendations"
            :key="item.media.id"
            class="rounded-2xl border border-white/10 bg-white/[0.04] p-4 hover:bg-white/[0.07] transition-all"
          >
            <div class="grid grid-cols-[124px_minmax(0,1fr)] gap-4">
              <MediaCard :media="item.media" @click="selectedMedia = item.media" />
              <div class="min-w-0">
                <button @click="selectedMedia = item.media" class="text-left w-full">
                  <h3 class="text-base font-black text-white leading-snug line-clamp-3">{{ item.media.title }}</h3>
                </button>
                <p class="text-sm leading-6 text-white/65 mt-3">{{ item.reason }}</p>
                <div class="mt-3 flex flex-wrap gap-2">
                  <span
                    v-for="tag in item.matched_tags.slice(0, 5)"
                    :key="tag"
                    class="px-2 py-1 rounded-lg bg-accent/12 border border-accent/20 text-[11px] font-bold text-accent"
                  >
                    {{ tag }}
                  </span>
                </div>
                <div class="mt-4 flex items-center gap-2 text-[11px] text-white/35">
                  <span>{{ item.media.page_count || '-' }} 页</span>
                  <span>·</span>
                  <span>{{ item.media.rating }} 星</span>
                  <span>·</span>
                  <span>score {{ item.score }}</span>
                </div>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="rounded-2xl border border-white/10 bg-white/[0.04] min-h-[420px] flex items-center justify-center text-center px-6">
          <div>
            <Sparkles class="mx-auto text-white/25 mb-4" :size="34" />
            <p class="text-lg font-black text-white/70">还没有推荐结果</p>
            <p class="text-sm text-white/35 mt-2">输入偏好后即可开始。</p>
          </div>
        </div>
      </section>
    </div>

    <MediaDetail
      v-if="selectedMedia"
      :initial-media="selectedMedia"
      :all-media="result?.recommendations.map(item => item.media) || []"
      @close="selectedMedia = null"
      @updated="selectedMedia = $event"
      @navigate="selectedMedia = $event"
    />
  </div>
</template>
