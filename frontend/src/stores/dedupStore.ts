import { computed, reactive } from 'vue'
import axios from 'axios'
import { API_BASE_URL } from '../config'
import type { DedupSummary, DuplicateCandidatePair } from '../types'

interface DedupState {
  summary: DedupSummary | null
  pairs: DuplicateCandidatePair[]
  loading: boolean
  errorMessage: string
  filterLevel: '' | 'strong_duplicate' | 'suspected_duplicate' | 'weak_suspected'
  filterStatus: 'pending' | 'all' | 'merged' | 'kept_both' | 'ignored' | 'replaced'
  filterMediaType: '' | 'video' | 'manga' | 'image'
}

const state = reactive<DedupState>({
  summary: null,
  pairs: [],
  loading: false,
  errorMessage: '',
  filterLevel: '',
  filterStatus: 'pending',
  filterMediaType: '',
})

const fetchSummary = async () => {
  try {
    const res = await axios.get<DedupSummary>(`${API_BASE_URL}/dedup/summary`)
    state.summary = res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '读取去重统计失败'
  }
}

const fetchPairs = async () => {
  state.loading = true
  state.errorMessage = ''
  try {
    const res = await axios.get<DuplicateCandidatePair[]>(`${API_BASE_URL}/dedup/candidates`, {
      params: {
        level: state.filterLevel || undefined,
        status: state.filterStatus,
        media_type: state.filterMediaType || undefined,
        limit: 200,
      },
    })
    state.pairs = res.data
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '读取重复列表失败'
  } finally {
    state.loading = false
  }
}

const refresh = async () => {
  await Promise.all([fetchSummary(), fetchPairs()])
}

const resolvePair = async (
  pairId: number,
  action: 'keep_existing' | 'replace_path' | 'keep_both' | 'ignore',
  note?: string,
) => {
  try {
    await axios.post<DuplicateCandidatePair>(`${API_BASE_URL}/dedup/candidates/${pairId}/resolve`, {
      action,
      note: note || undefined,
    })
    state.pairs = state.pairs.filter(pair => pair.id !== pairId || state.filterStatus !== 'pending')
    await refresh()
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '处理失败'
    throw err
  }
}

const recheckMedia = async (mediaId: number) => {
  try {
    await axios.post(`${API_BASE_URL}/dedup/media/${mediaId}/recheck`)
    await refresh()
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '重新检测失败'
  }
}

const deleteMediaFile = async (mediaId: number) => {
  try {
    await axios.delete(`${API_BASE_URL}/dedup/media/${mediaId}/file`, {
      data: { confirm: true },
    })
    await refresh()
  } catch (err: any) {
    state.errorMessage = err.response?.data?.detail || '删除文件失败'
    throw err
  }
}

export const dedupStore = {
  state,
  summary: computed(() => state.summary),
  pairs: computed(() => state.pairs),
  loading: computed(() => state.loading),
  errorMessage: computed(() => state.errorMessage),
  fetchSummary,
  fetchPairs,
  refresh,
  resolvePair,
  recheckMedia,
  deleteMediaFile,
  setFilters(filters: { level?: DedupState['filterLevel']; status?: DedupState['filterStatus']; mediaType?: DedupState['filterMediaType'] }) {
    if (filters.level !== undefined) state.filterLevel = filters.level
    if (filters.status !== undefined) state.filterStatus = filters.status
    if (filters.mediaType !== undefined) state.filterMediaType = filters.mediaType
  },
  clearError() { state.errorMessage = '' },
}
