import { computed, reactive } from 'vue'
import axios from 'axios'
import { API_BASE_URL } from '../config'
import type { ExternalDownloadJob } from '../types'

// Parallel to externalDownloadStore (WNACG). Same generic job poll/cancel
// endpoints (`/external/downloads/{id}`), only the start endpoint differs
// (`/external/asmr/downloads`). "pages" in the shared job schema = audio
// tracks here.

const STORAGE_KEY = 'he-manager:asmr-download-job-id'
const POLL_INTERVAL_MS = 1200

interface DownloadStoreState {
  job: ExternalDownloadJob | null
  inProgress: boolean
  errorMessage: string
  onCompletedCallbacks: Array<(job: ExternalDownloadJob) => void>
}

const state = reactive<DownloadStoreState>({
  job: null,
  inProgress: false,
  errorMessage: '',
  onCompletedCallbacks: [],
})

let pollTimer: number | null = null

const isFinalStatus = (status: string) => ['completed', 'failed', 'canceled'].includes(status)

const persistJobId = (jobId: string | null) => {
  try {
    if (jobId) localStorage.setItem(STORAGE_KEY, jobId)
    else localStorage.removeItem(STORAGE_KEY)
  } catch { /* ignore */ }
}

const readPersistedJobId = (): string | null => {
  try { return localStorage.getItem(STORAGE_KEY) } catch { return null }
}

const stopPolling = () => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

const fireCompleted = (job: ExternalDownloadJob) => {
  for (const cb of state.onCompletedCallbacks) {
    try { cb(job) } catch (err) { console.error(err) }
  }
}

const pollOnce = async (jobId: string) => {
  try {
    const res = await axios.get<ExternalDownloadJob>(`${API_BASE_URL}/external/downloads/${jobId}`)
    state.job = res.data
    if (isFinalStatus(res.data.status)) {
      stopPolling()
      state.inProgress = false
      persistJobId(null)
      fireCompleted(res.data)
    }
  } catch (err: any) {
    console.error('Failed to poll ASMR download job:', err)
    if (err.response?.status === 404) {
      stopPolling()
      state.inProgress = false
      state.job = null
      persistJobId(null)
      return
    }
    state.errorMessage = err.response?.data?.detail || '读取下载进度失败'
  }
}

const startPolling = (jobId: string) => {
  stopPolling()
  pollOnce(jobId)
  pollTimer = window.setInterval(() => pollOnce(jobId), POLL_INTERVAL_MS)
}

const startDownload = async (itemIds: number[], downloadRootPath: string) => {
  if (state.inProgress) return null
  state.errorMessage = ''
  state.inProgress = true
  try {
    const res = await axios.post<ExternalDownloadJob>(`${API_BASE_URL}/external/asmr/downloads`, {
      item_ids: itemIds,
      download_root_path: downloadRootPath,
    })
    state.job = res.data
    persistJobId(res.data.job_id)
    startPolling(res.data.job_id)
    return res.data
  } catch (err: any) {
    state.inProgress = false
    state.errorMessage = err.response?.data?.detail || '启动下载失败'
    throw err
  }
}

const cancelDownload = async () => {
  if (!state.job?.job_id || !state.inProgress) return
  try {
    const res = await axios.post<ExternalDownloadJob>(`${API_BASE_URL}/external/downloads/${state.job.job_id}/cancel`)
    state.job = res.data
  } catch (err: any) {
    console.error('Failed to cancel ASMR download job:', err)
    state.errorMessage = err.response?.data?.detail || '取消下载失败'
  }
}

const dismissJob = () => {
  if (state.inProgress) return
  state.job = null
  persistJobId(null)
}

const onCompleted = (cb: (job: ExternalDownloadJob) => void) => {
  state.onCompletedCallbacks.push(cb)
  return () => {
    state.onCompletedCallbacks = state.onCompletedCallbacks.filter(item => item !== cb)
  }
}

const tryResume = async () => {
  const jobId = readPersistedJobId()
  if (!jobId) return
  try {
    const res = await axios.get<ExternalDownloadJob>(`${API_BASE_URL}/external/downloads/${jobId}`)
    state.job = res.data
    if (isFinalStatus(res.data.status)) {
      persistJobId(null)
      state.inProgress = false
    } else {
      state.inProgress = true
      startPolling(jobId)
    }
  } catch {
    persistJobId(null)
  }
}

let resumed = false
const ensureResumed = () => {
  if (resumed) return
  resumed = true
  tryResume()
}

export const asmrDownloadStore = {
  state,
  job: computed(() => state.job),
  inProgress: computed(() => state.inProgress),
  errorMessage: computed(() => state.errorMessage),
  totalWorks: computed(() => state.job?.total ?? 0),
  totalTracks: computed(() => state.job?.pages_total ?? 0),
  downloadedTracks: computed(() => state.job?.pages_done ?? 0),
  currentTitle: computed(() => state.job?.current_book_title ?? ''),
  progressPercent: computed(() => {
    const total = state.job?.pages_total ?? 0
    const done = state.job?.pages_done ?? 0
    return total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 0
  }),
  canCancel: computed(() => {
    const job = state.job
    if (!job || !state.inProgress) return false
    return !job.cancel_requested && !['completed', 'failed', 'canceled', 'canceling'].includes(job.status)
  }),
  startDownload,
  cancelDownload,
  dismissJob,
  onCompleted,
  ensureResumed,
  clearError() { state.errorMessage = '' },
}
