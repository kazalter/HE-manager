<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import axios from 'axios'
import { Clock, Play, RefreshCw, AlertTriangle, CheckCircle, XCircle, Loader2 } from 'lucide-vue-next'
import { API_BASE_URL } from '../../config'
import type { AutoSyncLogEntry } from '../../types'

const props = defineProps<{
  sourceType: 'wnacg' | 'x'
  sourceId: number | null
  enabled: boolean
  intervalHours: number
  lastRunAt: string | null
  nextRunAt: string | null
  lastStatus: string | null
  lastMessage: string | null
  canEnable: boolean        // cookie + download path both set
  disableReason?: string    // reason if canEnable is false
}>()

const emit = defineEmits<{
  (e: 'update', payload: { auto_sync_enabled?: boolean; auto_sync_interval_hours?: number }): void
}>()

const triggering = ref(false)
const triggerError = ref('')
const logs = ref<AutoSyncLogEntry[]>([])
const logsExpanded = ref(false)

const intervalOptions = [
  { value: 6, label: '每 6 小时' },
  { value: 12, label: '每 12 小时' },
  { value: 24, label: '每天' },
  { value: 48, label: '每 2 天' },
  { value: 72, label: '每 3 天' },
]

const statusIcon = computed(() => {
  switch (props.lastStatus) {
    case 'success': return CheckCircle
    case 'failed': return XCircle
    case 'partial': return AlertTriangle
    case 'running': return Loader2
    default: return Clock
  }
})

const statusColor = computed(() => {
  switch (props.lastStatus) {
    case 'success': return 'text-emerald-400'
    case 'failed': return 'text-red-400'
    case 'partial': return 'text-amber-400'
    case 'running': return 'text-blue-400'
    default: return 'text-white/40'
  }
})

const statusLabel = computed(() => {
  switch (props.lastStatus) {
    case 'success': return '成功'
    case 'failed': return '失败'
    case 'partial': return '部分成功'
    case 'running': return '运行中'
    default: return '未运行'
  }
})

const countdown = ref('')
let countdownTimer: ReturnType<typeof setInterval> | null = null

const updateCountdown = () => {
  if (!props.enabled || !props.nextRunAt) {
    countdown.value = ''
    return
  }
  const next = new Date(props.nextRunAt + (props.nextRunAt.endsWith('Z') ? '' : 'Z'))
  const diff = next.getTime() - Date.now()
  if (diff <= 0) {
    countdown.value = '即将执行'
    return
  }
  const hours = Math.floor(diff / 3600000)
  const minutes = Math.floor((diff % 3600000) / 60000)
  if (hours > 0) {
    countdown.value = `${hours}h ${minutes}m 后执行`
  } else {
    countdown.value = `${minutes}m 后执行`
  }
}

const formatTime = (value: string | null) => {
  if (!value) return '-'
  const d = new Date(value + (value.endsWith('Z') ? '' : 'Z'))
  return d.toLocaleString()
}

const formatDuration = (seconds: number | null) => {
  if (!seconds) return '-'
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}m ${s}s`
}

const toggleEnabled = () => {
  if (!props.canEnable && !props.enabled) return
  emit('update', { auto_sync_enabled: !props.enabled })
}

const changeInterval = (event: Event) => {
  const value = parseInt((event.target as HTMLSelectElement).value, 10)
  emit('update', { auto_sync_interval_hours: value })
}

const triggerNow = async () => {
  if (!props.sourceId || triggering.value) return
  triggering.value = true
  triggerError.value = ''
  try {
    await axios.post(`${API_BASE_URL}/auto-sync/${props.sourceType}/${props.sourceId}/trigger`)
  } catch (err: any) {
    triggerError.value = err.response?.data?.detail || '触发失败'
  } finally {
    triggering.value = false
  }
}

const fetchLogs = async () => {
  if (!props.sourceId) return
  try {
    const res = await axios.get(`${API_BASE_URL}/auto-sync/logs`, {
      params: { source_type: props.sourceType, source_id: props.sourceId, limit: 10 },
    })
    logs.value = res.data
  } catch (err) {
    console.error('Failed to fetch auto-sync logs:', err)
  }
}

const toggleLogs = () => {
  logsExpanded.value = !logsExpanded.value
  if (logsExpanded.value && logs.value.length === 0) {
    fetchLogs()
  }
}

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  updateCountdown()
  countdownTimer = setInterval(updateCountdown, 30000)
  // Poll status every 30s when running
  pollTimer = setInterval(() => {
    if (props.lastStatus === 'running' && logsExpanded.value) {
      fetchLogs()
    }
  }, 30000)
})

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
  if (pollTimer) clearInterval(pollTimer)
})

watch(() => props.nextRunAt, updateCountdown)
watch(() => props.sourceId, () => {
  logs.value = []
  logsExpanded.value = false
})
</script>

<template>
  <div class="border-t border-white/8 pt-4 mt-1 space-y-3">
    <!-- Header -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2">
        <Clock :size="14" class="text-white/45" />
        <span class="text-xs font-bold text-white/55">自动同步+下载</span>
      </div>
      <!-- Toggle -->
      <button
        @click="toggleEnabled"
        :disabled="!canEnable && !enabled"
        :title="!canEnable && !enabled ? (disableReason || '请先配置 Cookie 和下载路径') : (enabled ? '关闭' : '开启')"
        :class="[
          'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors duration-200 ease-in-out focus:outline-none',
          enabled ? 'bg-accent' : 'bg-white/15',
          (!canEnable && !enabled) ? 'opacity-40 cursor-not-allowed' : '',
        ]"
      >
        <span
          :class="[
            'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow transition duration-200 ease-in-out',
            enabled ? 'translate-x-[22px]' : 'translate-x-0.5',
            'mt-0.5',
          ]"
        />
      </button>
    </div>

    <!-- Config (when enabled or has history) -->
    <template v-if="enabled || lastStatus">
      <!-- Interval selector -->
      <div v-if="enabled" class="flex items-center gap-3">
        <span class="text-xs text-white/45 shrink-0">间隔</span>
        <select
          :value="intervalHours"
          @change="changeInterval"
          class="flex-1 bg-black/20 border border-white/10 rounded-lg px-2.5 py-1.5 text-xs text-white focus:outline-none focus:ring-1 focus:ring-accent/50 appearance-none"
        >
          <option v-for="opt in intervalOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>

      <!-- Status display -->
      <div class="bg-black/15 rounded-xl px-3 py-2.5 space-y-1.5">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-1.5">
            <component :is="statusIcon" :size="13" :class="[statusColor, lastStatus === 'running' ? 'animate-spin' : '']" />
            <span class="text-xs font-semibold" :class="statusColor">{{ statusLabel }}</span>
          </div>
          <span v-if="lastRunAt" class="text-[10px] text-white/35">{{ formatTime(lastRunAt) }}</span>
        </div>
        <p v-if="lastMessage" class="text-[11px] text-white/50 leading-relaxed">{{ lastMessage }}</p>
        <p v-if="enabled && countdown" class="text-[11px] text-accent/70 font-medium">⏱ {{ countdown }}</p>
      </div>

      <!-- Actions -->
      <div class="flex gap-2">
        <button
          @click="triggerNow"
          :disabled="triggering || lastStatus === 'running' || !sourceId"
          class="flex-1 h-9 rounded-xl bg-white/[0.06] border border-white/10 text-white/70 text-xs font-bold flex items-center justify-center gap-1.5 hover:bg-white/10 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition-all"
        >
          <Play v-if="!triggering" :size="13" />
          <Loader2 v-else :size="13" class="animate-spin" />
          {{ triggering ? '触发中' : '立即执行' }}
        </button>
        <button
          @click="toggleLogs"
          class="h-9 px-3 rounded-xl bg-white/[0.06] border border-white/10 text-white/50 text-xs font-bold flex items-center justify-center gap-1.5 hover:bg-white/10 hover:text-white/70 transition-all"
        >
          <RefreshCw :size="12" />
          日志
        </button>
      </div>

      <p v-if="triggerError" class="text-[11px] text-red-300 bg-red-400/10 border border-red-400/15 rounded-lg px-2.5 py-1.5">
        {{ triggerError }}
      </p>

      <!-- Log panel -->
      <div v-if="logsExpanded" class="space-y-1.5">
        <div class="flex items-center justify-between">
          <span class="text-[10px] text-white/35 font-bold uppercase tracking-wider">执行历史</span>
          <button @click="fetchLogs" class="text-[10px] text-white/30 hover:text-white/50 transition-colors">刷新</button>
        </div>
        <div v-if="logs.length === 0" class="text-[11px] text-white/30 text-center py-3">暂无执行记录</div>
        <div
          v-for="log in logs"
          :key="log.id"
          class="bg-black/10 rounded-lg px-2.5 py-2 text-[11px] space-y-0.5"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-1.5">
              <span
                :class="[
                  'inline-block w-1.5 h-1.5 rounded-full',
                  log.status === 'success' ? 'bg-emerald-400' :
                  log.status === 'failed' ? 'bg-red-400' : 'bg-amber-400',
                ]"
              />
              <span class="text-white/60">{{ formatTime(log.started_at) }}</span>
            </div>
            <span class="text-white/35">{{ formatDuration(log.duration_seconds) }}</span>
          </div>
          <div class="flex gap-3 text-white/45">
            <span v-if="log.synced_count">同步 {{ log.synced_count }}</span>
            <span v-if="log.downloaded_count">下载 {{ log.downloaded_count }}</span>
            <span v-if="log.failed_count" class="text-red-400/70">失败 {{ log.failed_count }}</span>
          </div>
          <p v-if="log.message" class="text-white/35 truncate">{{ log.message }}</p>
        </div>
      </div>
    </template>

    <!-- Disabled hint -->
    <p v-else-if="!canEnable" class="text-[11px] text-white/30">
      {{ disableReason || '请先设置 Cookie 和下载路径' }}
    </p>
  </div>
</template>
