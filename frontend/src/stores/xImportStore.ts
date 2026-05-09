import { computed, reactive } from 'vue'
import axios from 'axios'
import { API_BASE_URL } from '../config'
import type { XImportJob, XImportSource, XImportStats, XSyncJob } from '../types'

const STORAGE_KEY = 'he-manager:x-import-job'
const SYNC_STORAGE_KEY = 'he-manager:x-sync-job'
const POLL_INTERVAL_MS = 1500
const SYNC_POLL_INTERVAL_MS = 1500

interface PersistedJob {
  jobId: string
  sourceId: number
}

interface XImportStoreState {
  source: XImportSource | null
  stats: XImportStats | null
  job: XImportJob | null
  inProgress: boolean
  errorMessage: string
  onCompletedCallbacks: Array<(job: XImportJob) => void>
  syncJob: XSyncJob | null
  syncInProgress: boolean
}

const state = reactive<XImportStoreState>({
  source: null,
  stats: null,
  job: null,
  inProgress: false,
  errorMessage: '',
  onCompletedCallbacks: [],
  syncJob: null,
  syncInProgress: false,
})

let pollTimer: number | null = null
let syncPollTimer: number | null = null

const isFinalStatus = (status: string) => ['completed', 'failed', 'canceled'].includes(status)

const persistJob = (jobId: string | null, sourceId: number | null) => {
  try {
    if (jobId && sourceId !== null) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ jobId, sourceId } satisfies PersistedJob))
    } else {
      localStorage.removeItem(STORAGE_KEY)
    }
  } catch { /* ignore */ }
}

const readPersistedJob = (): PersistedJob | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as PersistedJob
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

const fireCompleted = (job: XImportJob) => {
  for (const cb of state.onCompletedCallbacks) {
    try { cb(job) } catch (err) { console.error(err) }
  }
}

const refreshStats = async (sourceId: number) => {
  try {
    const res = await axios.get<XImportStats>(`${API_BASE_URL}/x/sources/${sourceId}/stats`)
    state.stats = res.data
  } catch (err) {
    console.error('Failed to refresh X import stats:', err)
  }
}

const pollOnce = async (jobId: string) => {
  try {
    const res = await axios.get<XImportJob>(`${API_BASE_URL}/x/imports/${jobId}`)
    state.job = res.data
    state.inProgress = !isFinalStatus(res.data.status)
    if (isFinalStatus(res.data.status)) {
      stopPolling()
      persistJob(null, null)
      await refreshStats(res.data.source_id)
      fireCompleted(res.data)
    }
  } catch (err: any) {
    console.error('Failed to poll X import job:', err)
    if (err.response?.status === 404) {
      stopPolling()
      state.inProgress = false
      state.job = null
      persistJob(null, null)
      return
    }
    state.errorMessage = err.response?.data?.detail || '读取导入进度失败'
  }
}

const startPolling = (jobId: string) => {
  stopPolling()
  pollOnce(jobId)
  pollTimer = window.setInterval(() => pollOnce(jobId), POLL_INTERVAL_MS)
}

const fetchSource = async (): Promise<XImportSource> => {
  const res = await axios.get<XImportSource[]>(`${API_BASE_URL}/x/sources`)
  const source = res.data[0]
  state.source = source
  if (source) await refreshStats(source.id)
  return source
}

const updateSource = async (patch: { name?: string; download_root_path?: string; cookie?: string }) => {
  if (!state.source) throw new Error('No X source loaded')
  const res = await axios.patch<XImportSource>(`${API_BASE_URL}/x/sources/${state.source.id}`, patch)
  state.source = res.data
  return res.data
}

const uploadArchive = async (file: File, downloadRootPath?: string) => {
  if (!state.source) await fetchSource()
  if (!state.source) throw new Error('No X source loaded')
  const form = new FormData()
  form.append('file', file)
  if (downloadRootPath) form.append('download_root_path', downloadRootPath)
  state.errorMessage = ''
  try {
    const res = await axios.post<{
      source: XImportSource
      archive_name: string
      parsed: number
      new_posts: number
      existing_posts: number
      stats: XImportStats
    }>(`${API_BASE_URL}/x/sources/${state.source.id}/archive`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    state.source = res.data.source
    state.stats = res.data.stats
    return res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '上传归档失败'
    throw err
  }
}

const startImport = async (opts: { retryFailedOnly?: boolean; retrySkippedOnly?: boolean } = {}) => {
  if (!state.source) throw new Error('No X source loaded')
  if (state.inProgress) return state.job
  state.errorMessage = ''
  try {
    const res = await axios.post<XImportJob>(`${API_BASE_URL}/x/imports`, {
      source_id: state.source.id,
      retry_failed_only: opts.retryFailedOnly ?? false,
      retry_skipped_only: opts.retrySkippedOnly ?? false,
    })
    state.job = res.data
    state.inProgress = true
    persistJob(res.data.job_id, res.data.source_id)
    startPolling(res.data.job_id)
    return res.data
  } catch (err: any) {
    state.inProgress = false
    state.errorMessage = err.response?.data?.detail || '启动导入失败'
    throw err
  }
}

const pauseImport = async () => {
  if (!state.job) return
  try {
    const res = await axios.post<XImportJob>(`${API_BASE_URL}/x/imports/${state.job.job_id}/pause`)
    state.job = res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '暂停失败'
  }
}

const resumeImport = async () => {
  if (!state.job) return
  try {
    const res = await axios.post<XImportJob>(`${API_BASE_URL}/x/imports/${state.job.job_id}/resume`)
    state.job = res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '继续失败'
  }
}

const cancelImport = async () => {
  if (!state.job) return
  try {
    const res = await axios.post<XImportJob>(`${API_BASE_URL}/x/imports/${state.job.job_id}/cancel`)
    state.job = res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '取消失败'
  }
}

const dismissJob = () => {
  if (state.inProgress) return
  state.job = null
  persistJob(null, null)
}

const persistSyncJob = (jobId: string | null, sourceId: number | null) => {
  try {
    if (jobId && sourceId !== null) {
      localStorage.setItem(SYNC_STORAGE_KEY, JSON.stringify({ jobId, sourceId } satisfies PersistedJob))
    } else {
      localStorage.removeItem(SYNC_STORAGE_KEY)
    }
  } catch { /* ignore */ }
}

const stopSyncPolling = () => {
  if (syncPollTimer !== null) {
    window.clearInterval(syncPollTimer)
    syncPollTimer = null
  }
}

const pollSyncOnce = async (jobId: string) => {
  try {
    const res = await axios.get<XSyncJob>(`${API_BASE_URL}/x/syncs/${jobId}`)
    state.syncJob = res.data
    state.syncInProgress = !isFinalStatus(res.data.status)
    if (isFinalStatus(res.data.status)) {
      stopSyncPolling()
      persistSyncJob(null, null)
      if (state.source) await refreshStats(state.source.id)
    }
  } catch (err: any) {
    if (err.response?.status === 404) {
      stopSyncPolling()
      state.syncInProgress = false
      state.syncJob = null
      persistSyncJob(null, null)
      return
    }
    state.errorMessage = err.response?.data?.detail || '读取同步进度失败'
  }
}

const startSyncPolling = (jobId: string) => {
  stopSyncPolling()
  pollSyncOnce(jobId)
  syncPollTimer = window.setInterval(() => pollSyncOnce(jobId), SYNC_POLL_INTERVAL_MS)
}

const startSync = async () => {
  if (!state.source) throw new Error('No X source loaded')
  if (state.syncInProgress) return state.syncJob
  state.errorMessage = ''
  try {
    const res = await axios.post<XSyncJob>(`${API_BASE_URL}/x/sources/${state.source.id}/sync`)
    state.syncJob = res.data
    state.syncInProgress = !isFinalStatus(res.data.status)
    persistSyncJob(res.data.job_id, res.data.source_id)
    startSyncPolling(res.data.job_id)
    return res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '启动同步失败'
    throw err
  }
}

const cancelSync = async () => {
  if (!state.syncJob) return
  try {
    const res = await axios.post<XSyncJob>(`${API_BASE_URL}/x/syncs/${state.syncJob.job_id}/cancel`)
    state.syncJob = res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '取消同步失败'
  }
}

const dismissSyncJob = () => {
  if (state.syncInProgress) return
  state.syncJob = null
  persistSyncJob(null, null)
}

const onCompleted = (cb: (job: XImportJob) => void) => {
  state.onCompletedCallbacks.push(cb)
  return () => {
    state.onCompletedCallbacks = state.onCompletedCallbacks.filter(item => item !== cb)
  }
}

const tryResume = async () => {
  const persisted = readPersistedJob()
  if (!persisted) {
    if (state.source) {
      try {
        const res = await axios.get<XImportJob | null>(`${API_BASE_URL}/x/sources/${state.source.id}/active-job`)
        if (res.data && !isFinalStatus(res.data.status)) {
          state.job = res.data
          state.inProgress = true
          persistJob(res.data.job_id, res.data.source_id)
          startPolling(res.data.job_id)
        }
      } catch { /* ignore */ }
    }
    return
  }
  try {
    const res = await axios.get<XImportJob>(`${API_BASE_URL}/x/imports/${persisted.jobId}`)
    state.job = res.data
    if (isFinalStatus(res.data.status)) {
      persistJob(null, null)
      state.inProgress = false
    } else {
      state.inProgress = true
      startPolling(persisted.jobId)
    }
  } catch {
    persistJob(null, null)
  }
}

const trySyncResume = async () => {
  let persisted: PersistedJob | null = null
  try {
    const raw = localStorage.getItem(SYNC_STORAGE_KEY)
    if (raw) persisted = JSON.parse(raw) as PersistedJob
  } catch { /* ignore */ }

  if (!persisted) {
    if (state.source) {
      try {
        const res = await axios.get<XSyncJob | null>(`${API_BASE_URL}/x/sources/${state.source.id}/active-sync`)
        if (res.data && !isFinalStatus(res.data.status)) {
          state.syncJob = res.data
          state.syncInProgress = true
          persistSyncJob(res.data.job_id, res.data.source_id)
          startSyncPolling(res.data.job_id)
        }
      } catch { /* ignore */ }
    }
    return
  }
  try {
    const res = await axios.get<XSyncJob>(`${API_BASE_URL}/x/syncs/${persisted.jobId}`)
    state.syncJob = res.data
    if (isFinalStatus(res.data.status)) {
      persistSyncJob(null, null)
      state.syncInProgress = false
    } else {
      state.syncInProgress = true
      startSyncPolling(persisted.jobId)
    }
  } catch {
    persistSyncJob(null, null)
  }
}

let resumed = false
const ensureResumed = async () => {
  if (resumed) return
  resumed = true
  if (!state.source) await fetchSource()
  await tryResume()
  await trySyncResume()
}

export const xImportStore = {
  state,
  source: computed(() => state.source),
  stats: computed(() => state.stats),
  job: computed(() => state.job),
  inProgress: computed(() => state.inProgress),
  errorMessage: computed(() => state.errorMessage),
  isPaused: computed(() => state.job?.status === 'paused'),
  postProgressPercent: computed(() => {
    const total = state.job?.total_posts ?? 0
    const done = (state.job?.completed_posts ?? 0) + (state.job?.skipped_posts ?? 0) + (state.job?.failed_posts ?? 0)
    return total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 0
  }),
  mediaProgressPercent: computed(() => {
    const total = state.job?.media_total ?? 0
    const done = state.job?.media_downloaded ?? 0
    return total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 0
  }),
  syncJob: computed(() => state.syncJob),
  syncInProgress: computed(() => state.syncInProgress),
  fetchSource,
  refreshStats,
  updateSource,
  uploadArchive,
  startImport,
  pauseImport,
  resumeImport,
  cancelImport,
  dismissJob,
  startSync,
  cancelSync,
  dismissSyncJob,
  onCompleted,
  ensureResumed,
  clearError() { state.errorMessage = '' },
  setError(msg: string) { state.errorMessage = msg },
}
