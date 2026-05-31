import { computed, reactive } from 'vue'
import axios from 'axios'
import { API_BASE_URL } from '../config'
import type { ExternalDownloadJob } from '../types'

const STORAGE_KEY = 'he-manager:external-download-job-id'
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
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
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
      // Keep the job_id persisted so a page reload restores the finished
      // panel (incl. the failure list) instead of silently losing it — it's
      // only cleared when the user explicitly dismisses, or when the backend
      // no longer has the job (handled in tryResume / the 404 branch below).
      fireCompleted(res.data)
    }
  } catch (err: any) {
    console.error('Failed to poll download job:', err)
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
    const res = await axios.post<ExternalDownloadJob>(`${API_BASE_URL}/external/wnacg/downloads`, {
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

const failedItemIds = (): number[] => {
  const tasks = state.job?.tasks ?? []
  return tasks.filter(task => task.status === 'failed').map(task => task.item_id)
}

const retryFailed = async (downloadRootPath: string) => {
  if (state.inProgress) return null
  const ids = failedItemIds()
  if (ids.length === 0) return null
  return startDownload(ids, downloadRootPath)
}

const cancelDownload = async () => {
  if (!state.job?.job_id || !state.inProgress) return
  try {
    const res = await axios.post<ExternalDownloadJob>(`${API_BASE_URL}/external/downloads/${state.job.job_id}/cancel`)
    state.job = res.data
  } catch (err: any) {
    console.error('Failed to cancel download job:', err)
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
      // Restore the finished panel (with its failure list) but don't resume
      // polling; the user dismisses it when done.
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

export const externalDownloadStore = {
  state,
  job: computed(() => state.job),
  inProgress: computed(() => state.inProgress),
  errorMessage: computed(() => state.errorMessage),
  totalBooks: computed(() => state.job?.total ?? 0),
  successBooks: computed(() => state.job?.completed ?? 0),
  failedBooks: computed(() => state.job?.failed ?? 0),
  totalPages: computed(() => state.job?.pages_total ?? 0),
  downloadedPages: computed(() => state.job?.pages_done ?? 0),
  currentBookTitle: computed(() => state.job?.current_book_title ?? ''),
  currentBookTotalPages: computed(() => state.job?.current_book_total_pages ?? 0),
  currentBookDownloadedPages: computed(() => state.job?.current_book_downloaded_pages ?? 0),
  tasks: computed(() => state.job?.tasks ?? []),
  failedTasks: computed(() => (state.job?.tasks ?? []).filter(task => task.status === 'failed')),
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
  retryFailed,
  cancelDownload,
  dismissJob,
  onCompleted,
  ensureResumed,
  clearError() { state.errorMessage = '' },
  setError(msg: string) { state.errorMessage = msg },
}
