<script setup lang="ts">
import { computed } from 'vue'
import { X } from 'lucide-vue-next'
import { externalDownloadStore } from '../stores/externalDownloadStore'

const job = externalDownloadStore.job
const inProgress = externalDownloadStore.inProgress
const totalBooks = externalDownloadStore.totalBooks
const successBooks = externalDownloadStore.successBooks
const failedBooks = externalDownloadStore.failedBooks
const totalPages = externalDownloadStore.totalPages
const downloadedPages = externalDownloadStore.downloadedPages
const currentBookTitle = externalDownloadStore.currentBookTitle
const currentBookTotalPages = externalDownloadStore.currentBookTotalPages
const currentBookDownloadedPages = externalDownloadStore.currentBookDownloadedPages
const progressPercent = externalDownloadStore.progressPercent
const canCancel = externalDownloadStore.canCancel
const tasks = externalDownloadStore.tasks

const statusLabel = computed(() => {
  if (!job.value) return ''
  const map: Record<string, string> = {
    running: '下载中',
    preparing: '准备中',
    completed: '已完成',
    failed: '失败',
    canceled: '已取消',
    canceling: '取消中',
  }
  return map[job.value.status] || job.value.status
})

const statusBookText = computed(() => {
  if (!job.value) return ''
  return `${successBooks.value}/${totalBooks.value} 本完成 · ${failedBooks.value} 失败`
})

const cancelText = computed(() => {
  if (!job.value) return '取消下载'
  return job.value.status === 'canceling' || job.value.cancel_requested ? '取消中' : '取消下载'
})

const onCancel = () => externalDownloadStore.cancelDownload()
</script>

<template>
  <div v-if="job" class="px-5 py-3 border-b border-white/10 bg-black/15 space-y-2">
    <div class="flex items-center justify-between gap-3">
      <div class="min-w-0">
        <p class="text-xs font-bold text-white/70">
          {{ statusLabel }} · {{ statusBookText }}
        </p>
        <p class="text-[11px] text-white/50 mt-0.5">
          {{ downloadedPages }} / {{ totalPages }} 页 · {{ progressPercent }}%
        </p>
      </div>
      <button
        v-if="inProgress"
        @click="onCancel"
        :disabled="!canCancel"
        class="h-8 px-3 rounded-lg bg-red-500/15 border border-red-400/25 text-red-200 disabled:opacity-45 disabled:cursor-not-allowed flex items-center gap-1 text-[11px] font-black transition-all"
      >
        <X :size="13" />
        {{ cancelText }}
      </button>
    </div>

    <div class="h-2 rounded-full bg-white/10 overflow-hidden">
      <div
        class="h-full bg-accent transition-all"
        :style="{ width: `${progressPercent}%` }"
      ></div>
    </div>

    <div v-if="currentBookTitle" class="text-[11px] text-white/55 space-y-0.5">
      <p class="truncate">
        正在下载：<span class="text-white/80 font-bold">{{ currentBookTitle }}</span>
      </p>
      <p>
        当前漫画 {{ currentBookDownloadedPages }} / {{ currentBookTotalPages }} 页
      </p>
    </div>

    <div v-if="tasks.length" class="max-h-32 overflow-y-auto space-y-1 pr-1">
      <p
        v-for="task in tasks"
        :key="task.id"
        class="text-[11px] truncate flex items-center gap-2"
        :class="{
          'text-emerald-300': task.status === 'success',
          'text-red-300': task.status === 'failed',
          'text-accent': task.status === 'downloading',
          'text-white/40': task.status === 'pending',
        }"
      >
        <span class="shrink-0 w-12 text-[10px] uppercase font-black tracking-wider">
          {{ task.status === 'success' ? '成功' : task.status === 'failed' ? '失败' : task.status === 'downloading' ? '下载' : '等待' }}
        </span>
        <span class="truncate">{{ task.title || `#${task.item_id}` }}</span>
        <span v-if="task.total_pages" class="shrink-0 text-white/40">
          {{ task.downloaded_pages }}/{{ task.total_pages }}
        </span>
        <span v-if="task.error" class="shrink-0 text-red-300/70 truncate">· {{ task.error }}</span>
      </p>
    </div>
  </div>
</template>
