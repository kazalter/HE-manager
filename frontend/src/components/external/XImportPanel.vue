<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import axios from 'axios'
import {
  AlertTriangle,
  AtSign,
  CheckCircle2,
  ExternalLink,
  FolderOpen,
  Pause,
  Play,
  PlayCircle,
  RefreshCw,
  Upload,
  X as XIcon,
} from 'lucide-vue-next'
import { API_BASE_URL } from '../../config'
import { xImportStore } from '../../stores/xImportStore'
import type { XPost } from '../../types'

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
const postProgress = xImportStore.postProgressPercent
const mediaProgress = xImportStore.mediaProgressPercent

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

const formatTime = (value?: string | null) => {
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

const onStart = async () => {
  if (!source.value) return
  if (!source.value.download_root_path) {
    await saveDownloadRoot()
    if (!source.value.download_root_path) return
  }
  try {
    await xImportStore.startImport(false)
  } catch (err) {
    console.error('Failed to start X import:', err)
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

let unsubscribe: (() => void) | null = null

onMounted(async () => {
  await xImportStore.fetchSource()
  if (source.value?.download_root_path) {
    downloadRootPath.value = source.value.download_root_path
  }
  await xImportStore.ensureResumed()
  unsubscribe = xImportStore.onCompleted(async () => {
    if (source.value) await xImportStore.refreshStats(source.value.id)
    await fetchFailedPosts()
  })
  await fetchFailedPosts()
})

onUnmounted(() => {
  if (unsubscribe) {
    unsubscribe()
    unsubscribe = null
  }
})
</script>

<template>
  <section class="space-y-6">
    <div v-if="errorMessage" class="bg-red-400/10 border border-red-400/20 text-red-200 rounded-xl px-4 py-3 text-sm flex items-center justify-between gap-3">
      <span>{{ errorMessage }}</span>
      <button @click="xImportStore.clearError()" class="text-white/55 hover:text-white">
        <XIcon :size="16" />
      </button>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-[minmax(360px,460px),1fr] gap-6">
      <!-- Config column -->
      <div class="bg-white/[0.04] border border-white/10 rounded-2xl p-5 space-y-5">
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-center gap-3 text-white">
            <div class="w-10 h-10 rounded-xl bg-accent/15 text-accent flex items-center justify-center border border-accent/20">
              <AtSign :size="20" />
            </div>
            <div>
              <h2 class="text-base font-black">X (Twitter)</h2>
              <p class="text-xs text-white/45">
                {{ source?.last_archive_imported_at ? `归档：${formatTime(source?.last_archive_imported_at)}` : '尚未上传归档' }}
              </p>
            </div>
          </div>
          <div class="flex items-center gap-1.5 text-[11px] text-emerald-300 bg-emerald-400/10 border border-emerald-400/15 rounded-full px-2 py-1">
            归档导入
          </div>
        </div>

        <div class="rounded-xl border border-white/10 bg-black/15 p-3 text-[11px] text-white/55 leading-relaxed">
          第一版用 X 数据归档（免费、稳定）。在 X 网页端「设置 → 你的账号 → 下载你的数据归档」申请并下载 zip 后上传这里。
          读取 <code class="text-accent/85">data/like.js</code>，用公开 syndication 接口拉取每条 Post 的媒体，无需登录态。
        </div>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/65">下载位置 <span class="text-red-300">*</span></span>
          <div class="flex gap-2">
            <input
              v-model="downloadRootPath"
              type="text"
              placeholder="例如 D:\HE manager\external_downloads"
              class="flex-1 bg-black/20 border border-white/10 rounded-xl px-3 py-3 text-sm text-white placeholder-white/35 focus:outline-none focus:ring-2 focus:ring-accent/50"
            />
            <button
              @click="saveDownloadRoot"
              class="h-12 px-3 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white text-xs font-bold flex items-center gap-1.5"
              title="保存"
            >
              <FolderOpen :size="14" /> 保存
            </button>
          </div>
          <p class="text-[11px] text-white/35">
            文件结构：<code>{root}/x/&lt;作者&gt;/&lt;tweet_id&gt;/</code>，每个 Post 目录写入 info.json。
          </p>
        </label>

        <label class="block space-y-2">
          <span class="text-xs font-bold text-white/65">X 数据归档 (.zip)</span>
          <input ref="fileInput" type="file" accept=".zip" class="hidden" @change="onFileChange" />
          <button
            @click="onPickFile"
            :disabled="uploading"
            class="w-full h-12 rounded-xl bg-white/5 border border-dashed border-white/15 hover:border-accent/40 text-white/70 hover:text-white flex items-center justify-center gap-2 text-sm font-bold transition-all disabled:opacity-60"
          >
            <Upload :size="16" :class="uploading ? 'animate-pulse' : ''" />
            {{ uploading ? '正在解析归档…' : (source?.last_archive_name ? `重新上传归档 (${source?.last_archive_name})` : '选择 X 数据归档 zip') }}
          </button>
          <p v-if="uploadResult" class="text-[11px] text-emerald-200 bg-emerald-400/10 border border-emerald-400/15 rounded-lg px-2.5 py-1.5">
            解析 {{ uploadResult.parsed }} 条喜欢，新增 {{ uploadResult.new_posts }}，已存在 {{ uploadResult.existing_posts }}。
          </p>
        </label>

        <div class="rounded-xl border border-white/10 bg-black/15 p-3 space-y-1.5 text-[11px] text-white/45 leading-relaxed">
          <p>· 归档由 X 异步生成，通常 24-72 小时后可下载。</p>
          <p>· 增量同步同样靠归档：再次上传新归档时，只处理新增和失败的 Post。</p>
          <p>· 媒体地址用公开 syndication 接口拉取，对你的账号不做任何写操作（不点赞、不关注、不发推）。</p>
        </div>
      </div>

      <!-- Action / progress -->
      <div class="space-y-5">
        <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
            <p class="text-[11px] text-white/45 font-bold uppercase tracking-widest">已发现</p>
            <p class="text-2xl font-black text-white mt-1">{{ stats?.total_posts ?? 0 }}</p>
            <p class="text-[11px] text-white/40 mt-1">条 Post</p>
          </div>
          <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
            <p class="text-[11px] text-emerald-300 font-bold uppercase tracking-widest">已完成</p>
            <p class="text-2xl font-black text-white mt-1">{{ stats?.completed_posts ?? 0 }}</p>
            <p class="text-[11px] text-white/40 mt-1">{{ stats?.downloaded_media ?? 0 }} 个媒体</p>
          </div>
          <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
            <p class="text-[11px] text-amber-300 font-bold uppercase tracking-widest">失败</p>
            <p class="text-2xl font-black text-white mt-1">{{ stats?.failed_posts ?? 0 }}</p>
            <p class="text-[11px] text-white/40 mt-1">可重试</p>
          </div>
          <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-4">
            <p class="text-[11px] text-white/45 font-bold uppercase tracking-widest">待处理</p>
            <p class="text-2xl font-black text-white mt-1">{{ totalPostsRemaining }}</p>
            <p class="text-[11px] text-white/40 mt-1">下次导入</p>
          </div>
        </div>

        <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-5 space-y-4">
          <div class="flex flex-wrap items-center gap-2.5">
            <button
              @click="onStart"
              :disabled="inProgress || !source?.download_root_path || !stats?.total_posts"
              class="h-12 px-5 rounded-xl bg-accent text-white font-black flex items-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed transition-all hover:brightness-110"
            >
              <PlayCircle :size="18" />
              开始导入
            </button>
            <button
              v-if="job && inProgress && !isPaused"
              @click="xImportStore.pauseImport()"
              class="h-12 px-4 rounded-xl bg-white/10 border border-white/15 text-white font-bold flex items-center gap-2 hover:bg-white/15"
            >
              <Pause :size="16" /> 暂停
            </button>
            <button
              v-if="job && isPaused"
              @click="xImportStore.resumeImport()"
              class="h-12 px-4 rounded-xl bg-white/10 border border-white/15 text-white font-bold flex items-center gap-2 hover:bg-white/15"
            >
              <Play :size="16" /> 继续
            </button>
            <button
              v-if="job && inProgress"
              @click="xImportStore.cancelImport()"
              :disabled="job?.cancel_requested"
              class="h-12 px-4 rounded-xl bg-red-500/10 border border-red-400/20 text-red-200 font-bold flex items-center gap-2 hover:bg-red-500/20 disabled:opacity-50"
            >
              <XIcon :size="16" /> 取消
            </button>
            <button
              @click="onRetryFailed"
              :disabled="inProgress || !stats?.failed_posts"
              class="h-12 px-4 rounded-xl bg-white/5 border border-white/10 text-white/80 font-bold flex items-center gap-2 hover:text-white hover:bg-white/10 disabled:opacity-40"
            >
              <RefreshCw :size="16" /> 重试失败 ({{ stats?.failed_posts ?? 0 }})
            </button>
          </div>

          <div v-if="job" class="space-y-3">
            <div class="flex items-center justify-between gap-2">
              <p class="text-sm font-bold text-white">{{ statusLabel }}</p>
              <p class="text-xs text-white/50 truncate max-w-[60%]">{{ job.message }}</p>
            </div>

            <div class="space-y-1">
              <div class="flex items-center justify-between text-[11px] text-white/55">
                <span>Post 进度 {{ job.completed_posts + job.skipped_posts + job.failed_posts }} / {{ job.total_posts }}</span>
                <span>{{ postProgress }}%</span>
              </div>
              <div class="h-2 rounded-full bg-white/5 overflow-hidden">
                <div class="h-full bg-accent transition-all" :style="{ width: `${postProgress}%` }"></div>
              </div>
            </div>

            <div class="space-y-1">
              <div class="flex items-center justify-between text-[11px] text-white/55">
                <span>媒体下载 {{ job.media_downloaded }} / {{ job.media_total }}</span>
                <span>{{ mediaProgress }}%</span>
              </div>
              <div class="h-2 rounded-full bg-white/5 overflow-hidden">
                <div class="h-full bg-emerald-400 transition-all" :style="{ width: `${mediaProgress}%` }"></div>
              </div>
            </div>

            <div class="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px]">
              <div class="rounded-lg bg-black/20 px-2.5 py-2 border border-white/5">
                <p class="text-white/40">含媒体 Post</p>
                <p class="text-white font-bold mt-0.5">{{ job.media_posts }}</p>
              </div>
              <div class="rounded-lg bg-black/20 px-2.5 py-2 border border-white/5">
                <p class="text-white/40">跳过</p>
                <p class="text-white font-bold mt-0.5">{{ job.skipped_posts }}</p>
              </div>
              <div class="rounded-lg bg-black/20 px-2.5 py-2 border border-white/5">
                <p class="text-white/40">失败</p>
                <p class="text-white font-bold mt-0.5">{{ job.failed_posts }}</p>
              </div>
              <div class="rounded-lg bg-black/20 px-2.5 py-2 border border-white/5">
                <p class="text-white/40">媒体失败</p>
                <p class="text-white font-bold mt-0.5">{{ job.media_failed }}</p>
              </div>
            </div>

            <div v-if="job.current_post_id || job.current_author" class="text-[11px] text-white/55 truncate">
              当前：@{{ job.current_author || '?' }} / {{ job.current_post_id }}
              <span v-if="job.current_file" class="text-white/40">· {{ job.current_file }}</span>
            </div>

            <div v-if="['completed', 'canceled', 'failed'].includes(job.status)" class="rounded-xl bg-black/20 border border-white/5 p-3 space-y-1">
              <p class="text-xs font-bold text-white flex items-center gap-2">
                <CheckCircle2 v-if="job.status === 'completed'" :size="14" class="text-emerald-300" />
                <AlertTriangle v-else :size="14" class="text-amber-300" />
                导入结果报告
              </p>
              <p class="text-[11px] text-white/55">
                扫描 {{ job.scanned_posts }} 个 Post · 含媒体 {{ job.media_posts }} ·
                成功 {{ job.completed_posts }} · 跳过 {{ job.skipped_posts }} · 失败 {{ job.failed_posts }}
              </p>
              <p class="text-[11px] text-white/45">媒体下载 {{ job.media_downloaded }} / {{ job.media_total }}（失败 {{ job.media_failed }}）</p>
              <button
                v-if="!inProgress"
                @click="xImportStore.dismissJob()"
                class="mt-1 text-[11px] text-white/45 hover:text-white"
              >
                关闭报告
              </button>
            </div>
          </div>

          <p v-else class="text-xs text-white/45">
            上传归档并设置好下载位置后，点击「开始导入」。任务在后台运行，切换页面也会继续。
          </p>
        </div>

        <div class="rounded-2xl bg-white/[0.04] border border-white/10 p-5 space-y-3">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-black text-white">失败项</h3>
            <button
              @click="fetchFailedPosts"
              class="text-[11px] text-white/55 hover:text-white flex items-center gap-1"
              :disabled="failedLoading"
            >
              <RefreshCw :size="12" :class="failedLoading ? 'animate-spin' : ''" /> 刷新
            </button>
          </div>
          <div v-if="failedPosts.length === 0" class="text-xs text-white/40">没有失败项。</div>
          <ul v-else class="space-y-2 max-h-72 overflow-y-auto pr-1">
            <li
              v-for="post in failedPosts"
              :key="post.id"
              class="text-[11px] rounded-lg bg-black/20 border border-white/5 px-3 py-2 flex items-start justify-between gap-2"
            >
              <div class="min-w-0">
                <p class="text-white truncate">@{{ post.author_screen_name || '?' }} · {{ post.tweet_id }}</p>
                <p class="text-red-300/80 truncate">{{ post.error_message || '未知错误' }}</p>
              </div>
              <a :href="post.url" target="_blank" rel="noreferrer" class="shrink-0 text-white/45 hover:text-accent">
                <ExternalLink :size="14" />
              </a>
            </li>
          </ul>
        </div>

        <div v-if="job?.errors?.length" class="rounded-2xl bg-white/[0.04] border border-white/10 p-5 space-y-2">
          <h3 class="text-sm font-black text-white">错误日志</h3>
          <ul class="space-y-1 max-h-48 overflow-y-auto pr-1 text-[11px] text-white/55">
            <li v-for="err in job.errors.slice().reverse()" :key="`${err.tweet_id}-${err.at}`" class="truncate">
              <span class="text-white/35">{{ err.at.slice(11, 19) }}</span>
              <span v-if="err.tweet_id"> · {{ err.tweet_id }}</span>
              · <span class="text-amber-200">{{ err.message }}</span>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </section>
</template>
