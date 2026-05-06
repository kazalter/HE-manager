<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import axios from 'axios'
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  FolderOpen,
  Pause,
  Play,
  PlayCircle,
  RefreshCw,
  Twitter,
  Upload,
  X,
} from 'lucide-vue-next'
import { API_BASE_URL } from '../config'
import type { XPost } from '../types'
import { xImportStore } from '../stores/xImportStore'

const downloadRootPath = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadResult = ref<{ parsed: number; new_posts: number; existing_posts: number } | null>(null)
const failedPosts = ref<XPost[]>([])
const failedLoading = ref(false)

const source = xImportStore.source
const stats = xImportStore.stats
const job = xImportStore.job
const inProgress = xImportStore.inProgress
const errorMessage = xImportStore.errorMessage
const isPaused = xImportStore.isPaused
const postProgressPercent = xImportStore.postProgressPercent
const mediaProgressPercent = xImportStore.mediaProgressPercent

const statusLabel = computed(() => {
  if (!job.value) return ''
  const map: Record<string, string> = {
    queued: '排队中',
    preparing: '准备中',
    running: '导入中',
    paused: '已暂停',
    completed: '已完成',
    failed: '失败',
    canceled: '已取消',
  }
  return map[job.value.status] || job.value.status
})

const totalPostsRemaining = computed(() => {
  if (!stats.value) return 0
  return stats.value.pending_posts + stats.value.failed_posts
})

const formatTime = (value: string | null) => {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

const onPickFile = () => {
  fileInput.value?.click()
}

const onFileChange = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  uploading.value = true
  uploadResult.value = null
  try {
    const trimmed = downloadRootPath.value.trim()
    if (trimmed && (!source.value?.download_root_path || source.value.download_root_path !== trimmed)) {
      await xImportStore.updateSource({ download_root_path: trimmed })
    }

    const res = await xImportStore.uploadArchive(file, trimmed || undefined)
    uploadResult.value = {
      parsed: res.parsed,
      new_posts: res.new_posts,
      existing_posts: res.existing_posts,
    }
    if (res.source.download_root_path) {
      downloadRootPath.value = res.source.download_root_path
    }
  } catch (err) {
    console.error('Failed to upload archive:', err)
  } finally {
    uploading.value = false
    if (input) input.value = ''
  }
}

const saveDownloadRoot = async () => {
  const trimmed = downloadRootPath.value.trim()
  if (!trimmed) {
    xImportStore.setError('请填写下载位置')
    return
  }
  try {
    await xImportStore.updateSource({ download_root_path: trimmed })
    xImportStore.clearError()
  } catch (err: any) {
    xImportStore.setError(err.response?.data?.detail || '保存下载位置失败')
  }
}

const onStartImport = async () => {
  if (!source.value) return
  if (!source.value.download_root_path) {
    await saveDownloadRoot()
    if (!source.value.download_root_path) return
  }

  try {
    await xImportStore.startImport(false)
  } catch (err) {
    console.error('Failed to start import:', err)
  }
}

const onRetryFailed = async () => {
  if (!source.value) return
  try {
    await xImportStore.startImport(true)
  } catch (err) {
    console.error('Failed to retry failed posts:', err)
  }
}

const fetchFailedPosts = async () => {
  if (!source.value) return
  failedLoading.value = true
  try {
    const res = await axios.get<XPost[]>(`${API_BASE_URL}/x/sources/${source.value.id}/posts`, {
      params: { status: 'failed', limit: 100 },
    })
    failedPosts.value = res.data
  } catch (err) {
    console.error('Failed to fetch failed posts:', err)
  } finally {
    failedLoading.value = false
  }
}

let unsubscribeCompleted: (() => void) | null = null

onMounted(async () => {
  await xImportStore.ensureResumed()
  if (source.value?.download_root_path) {
    downloadRootPath.value = source.value.download_root_path
  }

  unsubscribeCompleted = xImportStore.onCompleted(async () => {
    if (source.value) await xImportStore.refreshStats(source.value.id)
    await fetchFailedPosts()
  })

  await fetchFailedPosts()
})

onUnmounted(() => {
  if (unsubscribeCompleted) {
    unsubscribeCompleted()
    unsubscribeCompleted = null
  }
})
</script>

<template>
  <div class="z-10 relative min-h-screen">
    <header
      class="sticky top-0 z-40 bg-background/75 backdrop-blur-xl border-b border-white/10 px-6 md:px-8 py-5 mb-6"
    >
      <div class="flex flex-wrap items-center justify-between gap-5">
        <div class="flex items-center gap-4">
          <div
            class="w-12 h-12 rounded-2xl bg-accent shadow-lg shadow-accent/20 flex items-center justify-center text-white"
          >
            <Twitter :size="28" />
          </div>
          <div>
            <h1 class="text-2xl md:text-3xl font-black text-white tracking-tight">X 一键导入</h1>
            <div class="flex items-center gap-3 mt-1 text-xs text-white/45">
              <span v-if="source?.last_archive_imported_at">
                上次归档：{{ formatTime(source?.last_archive_imported_at) }}
              </span>
              <span v-else>未上传归档</span>
            </div>
          </div>
        </div>
      </div>
    </header>

    <main class="px-6 md:px-8 pb-12 space-y-6">
      <section class="grid grid-cols-1 lg:grid-cols-[400px,1fr] gap-6">
        <!-- Sidebar: Config & Stats -->
        <div class="space-y-6">
          <div class="bg-white/[0.04] border border-white/10 rounded-2xl p-6 space-y-5">
            <h2 class="text-lg font-black text-white flex items-center gap-2">配置与归档</h2>

            <div
              v-if="errorMessage"
              class="bg-red-400/10 border border-red-400/20 text-red-200 rounded-xl px-4 py-3 text-sm flex items-center justify-between gap-3"
            >
              <span>{{ errorMessage }}</span>
              <button @click="xImportStore.clearError()" class="text-white/55 hover:text-white">
                <X :size="16" />
              </button>
            </div>

            <label class="block space-y-2">
              <span class="text-xs font-bold text-white/65">下载位置 <span class="text-red-300">*</span></span>
              <div class="flex gap-2">
                <input
                  v-model="downloadRootPath"
                  type="text"
                  placeholder="例如 D:\HE manager\external_downloads"
                  class="flex-1 bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
                />
                <button
                  @click="saveDownloadRoot"
                  class="h-10 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white text-xs font-bold flex items-center gap-1.5 transition-all"
                  title="保存位置"
                >
                  <FolderOpen :size="14" />
                  保存
                </button>
              </div>
            </label>

            <label class="block space-y-2">
              <span class="text-xs font-bold text-white/65">X 数据归档 (.zip)</span>
              <input ref="fileInput" type="file" accept=".zip" class="hidden" @change="onFileChange" />
              <button
                @click="onPickFile"
                :disabled="uploading"
                class="w-full h-16 rounded-2xl bg-white/5 border border-dashed border-white/15 hover:border-accent/40 hover:bg-white/[0.07] text-white/70 hover:text-white flex flex-col items-center justify-center gap-1 transition-all disabled:opacity-60"
              >
                <Upload :size="20" :class="uploading ? 'animate-pulse' : ''" />
                <span class="text-xs font-bold">{{
                  uploading ? '正在解析归档...' : source?.last_archive_name ? `更新归档: ${source.last_archive_name}` : '点击上传数据归档 zip'
                }}</span>
              </button>
              <div
                v-if="uploadResult"
                class="bg-emerald-400/10 border border-emerald-400/20 text-emerald-200 rounded-xl px-4 py-2 text-[11px] font-bold"
              >
                解析成功：共 {{ uploadResult.parsed }} 条，新增 {{ uploadResult.new_posts }} 条
              </div>
            </label>

            <div
              class="rounded-2xl border border-white/10 bg-black/20 p-4 space-y-2 text-[11px] text-white/45 leading-relaxed"
            >
              <p>· 归档文件由 X 生成，通常在请求后 24-72 小时内可下载。</p>
              <p>· 我们读取归档中的 data/like.js，并尝试拉取 Post 中的媒体内容。</p>
              <p>· 重复上传归档不会丢失进度，系统会自动去重并补充新喜欢的项目。</p>
            </div>
          </div>

          <!-- Quick Stats -->
          <div class="bg-white/[0.04] border border-white/10 rounded-2xl p-6">
            <h2 class="text-lg font-black text-white mb-4">导入统计</h2>
            <div class="grid grid-cols-2 gap-4">
              <div class="bg-black/20 rounded-xl p-3 border border-white/5">
                <p class="text-[10px] text-white/40 font-bold uppercase tracking-wider">总计发现</p>
                <p class="text-2xl font-black text-white">{{ stats?.total_posts ?? 0 }}</p>
              </div>
              <div class="bg-black/20 rounded-xl p-3 border border-white/5">
                <p class="text-[10px] text-emerald-400/60 font-bold uppercase tracking-wider">已完成</p>
                <p class="text-2xl font-black text-white">{{ stats?.completed_posts ?? 0 }}</p>
              </div>
              <div class="bg-black/20 rounded-xl p-3 border border-white/5">
                <p class="text-[10px] text-amber-400/60 font-bold uppercase tracking-wider">失败记录</p>
                <p class="text-2xl font-black text-white">{{ stats?.failed_posts ?? 0 }}</p>
              </div>
              <div class="bg-black/20 rounded-xl p-3 border border-white/5">
                <p class="text-[10px] text-white/40 font-bold uppercase tracking-wider">待处理</p>
                <p class="text-2xl font-black text-white">{{ totalPostsRemaining }}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Main Content: Task & Progress -->
        <div class="space-y-6">
          <div class="bg-white/[0.04] border border-white/10 rounded-2xl p-6 min-h-[400px] flex flex-col">
            <div class="flex items-center justify-between gap-4 mb-8">
              <h2 class="text-xl font-black text-white">当前任务状态</h2>
              <div class="flex items-center gap-3">
                <button
                  @click="onRetryFailed"
                  :disabled="inProgress || !stats?.failed_posts"
                  class="h-10 px-4 rounded-xl bg-white/5 border border-white/10 text-white/80 font-bold flex items-center gap-2 hover:text-white hover:bg-white/10 disabled:opacity-40 transition-all text-sm"
                >
                  <RefreshCw :size="16" />
                  重试失败项
                </button>
                <button
                  @click="onStartImport"
                  :disabled="inProgress || !source?.download_root_path || !stats?.total_posts"
                  class="h-10 px-6 rounded-xl bg-accent text-white font-black flex items-center gap-2 hover:brightness-110 disabled:opacity-40 disabled:cursor-not-allowed transition-all shadow-lg shadow-accent/20"
                >
                  <PlayCircle :size="18" />
                  开始导入
                </button>
              </div>
            </div>

            <div v-if="job" class="flex-1 space-y-8">
              <div class="bg-black/30 rounded-2xl p-6 border border-white/5 space-y-6">
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-3">
                    <div
                      class="w-10 h-10 rounded-xl bg-accent/20 text-accent flex items-center justify-center animate-pulse"
                    >
                      <RefreshCw :size="20" />
                    </div>
                    <div>
                      <p class="text-sm font-black text-white">{{ statusLabel }}</p>
                      <p class="text-xs text-white/45 mt-0.5">{{ job.message }}</p>
                    </div>
                  </div>

                  <div class="flex items-center gap-2">
                    <button
                      v-if="inProgress && !isPaused"
                      @click="xImportStore.pauseImport()"
                      class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white flex items-center justify-center transition-all"
                      title="暂停"
                    >
                      <Pause :size="18" />
                    </button>
                    <button
                      v-if="isPaused"
                      @click="xImportStore.resumeImport()"
                      class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white flex items-center justify-center transition-all"
                      title="继续"
                    >
                      <Play :size="18" />
                    </button>
                    <button
                      @click="xImportStore.cancelImport()"
                      :disabled="job.cancel_requested"
                      class="w-10 h-10 rounded-xl bg-red-500/10 border border-red-400/20 text-red-200 hover:bg-red-500/20 flex items-center justify-center transition-all disabled:opacity-50"
                      title="取消任务"
                    >
                      <X :size="18" />
                    </button>
                  </div>
                </div>

                <div class="space-y-4">
                  <div class="space-y-2">
                    <div
                      class="flex items-center justify-between text-[11px] text-white/55 font-bold uppercase tracking-wider"
                    >
                      <span>Post 扫描进度 ({{ job.completed_posts + job.skipped_posts + job.failed_posts }} / {{ job.total_posts }})</span>
                      <span class="text-accent">{{ postProgressPercent }}%</span>
                    </div>
                    <div class="h-2 rounded-full bg-white/5 overflow-hidden">
                      <div
                        class="h-full bg-accent transition-all duration-500"
                        :style="{ width: `${postProgressPercent}%` }"
                      ></div>
                    </div>
                  </div>

                  <div class="space-y-2">
                    <div
                      class="flex items-center justify-between text-[11px] text-white/55 font-bold uppercase tracking-wider"
                    >
                      <span>媒体下载进度 ({{ job.media_downloaded }} / {{ job.media_total }})</span>
                      <span class="text-emerald-400">{{ mediaProgressPercent }}%</span>
                    </div>
                    <div class="h-2 rounded-full bg-white/5 overflow-hidden">
                      <div
                        class="h-full bg-emerald-400 transition-all duration-500"
                        :style="{ width: `${mediaProgressPercent}%` }"
                      ></div>
                    </div>
                  </div>
                </div>

                <div
                  v-if="job.current_author || job.current_post_id"
                  class="text-[11px] text-white/35 truncate bg-black/20 px-4 py-3 rounded-xl border border-white/5 flex items-center gap-3"
                >
                  <span class="font-bold text-white/50">正在处理:</span>
                  <span class="text-white/70">@{{ job.current_author || '未知用户' }}</span>
                  <span class="w-px h-3 bg-white/10"></span>
                  <span class="text-white/70">{{ job.current_post_id }}</span>
                  <span v-if="job.current_file" class="text-white/30 truncate">· {{ job.current_file }}</span>
                </div>

                <div
                  v-if="['completed', 'canceled', 'failed'].includes(job.status)"
                  class="rounded-2xl bg-black/40 border border-white/5 p-5 space-y-3"
                >
                  <div class="flex items-center gap-2">
                    <CheckCircle2 v-if="job.status === 'completed'" :size="18" class="text-emerald-400" />
                    <AlertTriangle v-else :size="18" class="text-amber-400" />
                    <h3 class="font-black text-white">导入任务完成报告</h3>
                  </div>
                  <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
                    <div>
                      <p class="text-white/35">扫描 Post</p>
                      <p class="font-bold text-white mt-0.5">{{ job.scanned_posts }}</p>
                    </div>
                    <div>
                      <p class="text-white/35">成功下载</p>
                      <p class="font-bold text-emerald-400 mt-0.5">{{ job.completed_posts }}</p>
                    </div>
                    <div>
                      <p class="text-white/35">失败记录</p>
                      <p class="font-bold text-red-400 mt-0.5">{{ job.failed_posts }}</p>
                    </div>
                    <div>
                      <p class="text-white/35">下载媒体</p>
                      <p class="font-bold text-white mt-0.5">{{ job.media_downloaded }}</p>
                    </div>
                  </div>
                  <div class="pt-2">
                    <button
                      @click="xImportStore.dismissJob()"
                      class="text-[11px] font-bold text-accent hover:underline"
                    >
                      关闭并返回
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <div v-else class="flex-1 flex flex-col items-center justify-center text-center p-12 space-y-4">
              <div class="w-20 h-20 rounded-full bg-white/5 flex items-center justify-center text-white/10 mb-2">
                <Twitter :size="48" />
              </div>
              <div class="max-w-sm">
                <h3 class="text-lg font-black text-white/60">等待开始任务</h3>
                <p class="text-sm text-white/30 mt-2">
                  上传 X 数据归档文件并配置下载位置后，点击「开始导入」按钮来同步您的喜欢内容。
                </p>
              </div>
            </div>

            <!-- Failures List -->
            <div v-if="failedPosts.length > 0" class="mt-8 pt-8 border-t border-white/5 space-y-4">
              <div class="flex items-center justify-between">
                <h3 class="text-sm font-black text-white uppercase tracking-wider opacity-60">最近失败项</h3>
                <button
                  @click="fetchFailedPosts"
                  class="text-[10px] font-bold text-white/30 hover:text-white transition-colors"
                >
                  刷新列表
                </button>
              </div>

              <div class="max-h-60 overflow-y-auto pr-2 custom-scrollbar space-y-2">
                <div
                  v-for="post in failedPosts"
                  :key="post.id"
                  class="group flex items-center justify-between gap-4 p-4 rounded-xl bg-black/20 border border-white/5 hover:border-white/10 transition-all"
                >
                  <div class="min-w-0">
                    <div class="flex items-center gap-2">
                      <span class="text-xs font-bold text-white">@{{ post.author_screen_name || '?' }}</span>
                      <span class="text-[10px] text-white/30">{{ post.tweet_id }}</span>
                    </div>
                    <p class="text-[10px] text-red-400/70 mt-1 line-clamp-1">
                      {{ post.error_message || '未知错误' }}
                    </p>
                  </div>
                  <a
                    :href="post.url"
                    target="_blank"
                    class="shrink-0 w-8 h-8 rounded-lg bg-white/5 flex items-center justify-center text-white/30 hover:text-accent hover:bg-accent/10 transition-all"
                  >
                    <ExternalLink :size="14" />
                  </a>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 10px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.2);
}
</style>
