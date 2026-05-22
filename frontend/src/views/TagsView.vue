<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import axios from 'axios'
import { Tags as TagsIcon, RefreshCw, Pencil, GitMerge, Trash2, Check, X, Search } from 'lucide-vue-next'
import { API_BASE_URL } from '../config'
import type { Tag } from '../types'
import ThemeSelect from '../components/ThemeSelect.vue'

const NAMESPACES = [
  { value: 'general', label: '通用' },
  { value: 'artist', label: '作者' },
  { value: 'character', label: '角色' },
  { value: 'parody', label: '出处' },
  { value: 'source', label: '来源' },
]
const NS_ORDER = NAMESPACES.map(n => n.value)
const nsLabel = (ns: string) => NAMESPACES.find(n => n.value === ns)?.label ?? ns

const tags = ref<Tag[]>([])
const loading = ref(true)
const errorMessage = ref('')
const refreshSpinning = ref(false)
const search = ref('')
const collapsed = ref<Set<string>>(new Set())

// edit / merge state
const editId = ref<number | null>(null)
const editName = ref('')
const editNs = ref('general')
const rowError = ref<{ id: number; msg: string } | null>(null)
const mergeId = ref<number | null>(null)
// 0 = "no target chosen" sentinel (no tag has id 0; stays falsy for the
// existing !mergeTargetId guards) so the value fits ThemeSelect's typed model.
const mergeTargetId = ref<number>(0)
const busy = ref(false)

const fetchTags = async () => {
  loading.value = tags.value.length === 0
  errorMessage.value = ''
  try {
    const res = await axios.get<Tag[]>(`${API_BASE_URL}/tags`)
    tags.value = res.data
  } catch (err: any) {
    errorMessage.value = err?.response?.data?.detail || '加载标签失败，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
}

const refresh = async () => {
  if (refreshSpinning.value) return
  refreshSpinning.value = true
  await fetchTags()
  setTimeout(() => (refreshSpinning.value = false), 400)
}

onMounted(fetchTags)

const grouped = computed(() => {
  const q = search.value.trim().toLowerCase()
  const groups: Record<string, Tag[]> = {}
  for (const t of tags.value) {
    if (q && !t.name.toLowerCase().includes(q)) continue
    const ns = t.namespace || 'general'
    ;(groups[ns] ??= []).push(t)
  }
  const known = NS_ORDER.filter(n => groups[n])
  const extra = Object.keys(groups).filter(n => !NS_ORDER.includes(n)).sort()
  return [...known, ...extra].map(ns => ({
    ns,
    label: nsLabel(ns),
    items: groups[ns].sort((a, b) => a.name.localeCompare(b.name)),
  }))
})

const totalShown = computed(() => grouped.value.reduce((s, g) => s + g.items.length, 0))

const toggleGroup = (ns: string) => {
  const s = new Set(collapsed.value)
  s.has(ns) ? s.delete(ns) : s.add(ns)
  collapsed.value = s
}

const startEdit = (t: Tag) => {
  editId.value = t.id
  editName.value = t.name
  editNs.value = t.namespace
  rowError.value = null
  mergeId.value = null
}
const cancelEdit = () => {
  editId.value = null
  rowError.value = null
}
const saveEdit = async (t: Tag) => {
  const name = editName.value.trim()
  if (!name || busy.value) return
  busy.value = true
  rowError.value = null
  try {
    await axios.patch(`${API_BASE_URL}/tags/${t.id}`, { name, namespace: editNs.value })
    editId.value = null
    await fetchTags()
  } catch (err: any) {
    rowError.value = { id: t.id, msg: err?.response?.data?.detail || '重命名失败' }
  } finally {
    busy.value = false
  }
}

const startMerge = (t: Tag) => {
  mergeId.value = t.id
  mergeTargetId.value = 0
  rowError.value = null
  editId.value = null
}
const mergeCandidates = computed(() =>
  tags.value
    .filter(t => t.id !== mergeId.value)
    .sort((a, b) => (a.namespace + a.name).localeCompare(b.namespace + b.name)),
)
const mergeOptions = computed(() => [
  { value: 0, label: '选择目标标签…' },
  ...mergeCandidates.value.map(c => ({
    value: c.id,
    label: `${nsLabel(c.namespace)}:${c.name} (${c.count ?? 0})`,
  })),
])
const confirmMerge = async (t: Tag) => {
  if (!mergeTargetId.value || busy.value) return
  const target = tags.value.find(x => x.id === mergeTargetId.value)
  if (!target) return
  if (!confirm(`把「${nsLabel(t.namespace)}:${t.name}」(${t.count ?? 0}) 合并进「${nsLabel(target.namespace)}:${target.name}」？此标签将被删除。`)) return
  busy.value = true
  rowError.value = null
  try {
    await axios.post(`${API_BASE_URL}/tags/${t.id}/merge`, { target_id: mergeTargetId.value })
    mergeId.value = null
    await fetchTags()
  } catch (err: any) {
    rowError.value = { id: t.id, msg: err?.response?.data?.detail || '合并失败' }
  } finally {
    busy.value = false
  }
}

const removeTag = async (t: Tag) => {
  if (busy.value) return
  if (!confirm(`删除标签「${nsLabel(t.namespace)}:${t.name}」？将从 ${t.count ?? 0} 个媒体上移除（媒体本身不受影响）。`)) return
  busy.value = true
  try {
    await axios.delete(`${API_BASE_URL}/tags/${t.id}`)
    await fetchTags()
  } catch (err: any) {
    rowError.value = { id: t.id, msg: err?.response?.data?.detail || '删除失败' }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="p-6 md:p-8 max-w-5xl mx-auto">
    <div class="flex items-center justify-between mb-8">
      <div>
        <h1 class="text-2xl font-black text-white flex items-center gap-3">
          <TagsIcon :size="26" class="text-accent" />
          标签管理
        </h1>
        <p class="text-white/45 text-sm mt-1">重命名、合并、清理标签 · 共 {{ tags.length }} 个</p>
      </div>
      <button
        @click="refresh"
        class="p-2.5 rounded-xl bg-white/5 border border-white/10 text-white/60 hover:text-white hover:bg-white/10 transition-all"
        title="刷新"
      >
        <RefreshCw :size="18" :class="{ 'animate-spin': refreshSpinning }" />
      </button>
    </div>

    <div class="relative mb-6">
      <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-white/30" />
      <input
        v-model="search"
        class="w-full rounded-xl bg-white/5 border border-white/10 pl-10 pr-3 py-2.5 text-sm text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent/50"
        placeholder="搜索标签名…"
      />
    </div>

    <div v-if="loading" class="text-white/40 py-24 text-center">正在加载标签…</div>
    <div
      v-else-if="errorMessage"
      class="py-16 text-center text-red-200 bg-red-400/10 border border-red-400/20 rounded-2xl"
    >
      {{ errorMessage }}
    </div>
    <div v-else-if="totalShown === 0" class="text-white/40 py-24 text-center">没有匹配的标签。</div>

    <div v-else class="space-y-5">
      <section
        v-for="group in grouped"
        :key="group.ns"
        class="rounded-2xl bg-white/[0.03] border border-white/10 overflow-hidden"
      >
        <button
          @click="toggleGroup(group.ns)"
          class="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-white/[0.04] transition-colors"
        >
          <span class="text-sm font-bold text-white/80 flex items-center gap-2">
            {{ group.label }}
            <span class="text-xs font-medium text-white/35">{{ group.items.length }}</span>
          </span>
          <span class="text-xs text-white/35">{{ collapsed.has(group.ns) ? '展开' : '收起' }}</span>
        </button>

        <div v-if="!collapsed.has(group.ns)" class="divide-y divide-white/[0.06]">
          <div v-for="t in group.items" :key="t.id" class="px-4 py-2.5">
            <!-- view row -->
            <div v-if="editId !== t.id && mergeId !== t.id" class="flex items-center gap-3">
              <span class="flex-1 min-w-0 truncate text-sm text-white">{{ t.name }}</span>
              <span class="shrink-0 text-xs text-white/40 tabular-nums">{{ t.count ?? 0 }}</span>
              <div class="shrink-0 flex items-center gap-1">
                <button @click="startEdit(t)" class="p-1.5 rounded-lg text-white/40 hover:text-white hover:bg-white/8" title="重命名 / 改类别">
                  <Pencil :size="15" />
                </button>
                <button @click="startMerge(t)" class="p-1.5 rounded-lg text-white/40 hover:text-white hover:bg-white/8" title="合并到另一个标签">
                  <GitMerge :size="15" />
                </button>
                <button @click="removeTag(t)" class="p-1.5 rounded-lg text-white/40 hover:text-red-300 hover:bg-white/8" title="删除标签">
                  <Trash2 :size="15" />
                </button>
              </div>
            </div>

            <!-- edit row -->
            <div v-else-if="editId === t.id" class="flex items-center gap-2">
              <input
                v-model="editName"
                @keydown.enter="saveEdit(t)"
                @keydown.esc="cancelEdit"
                class="min-w-0 flex-1 rounded-lg bg-white/5 border border-white/10 px-3 py-1.5 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50"
              />
              <ThemeSelect v-model="editNs" :options="NAMESPACES" class="w-28 shrink-0" />
              <button @click="saveEdit(t)" :disabled="busy" class="p-1.5 rounded-lg bg-accent/80 text-white hover:bg-accent disabled:opacity-50" title="保存">
                <Check :size="15" />
              </button>
              <button @click="cancelEdit" class="p-1.5 rounded-lg text-white/40 hover:text-white hover:bg-white/8" title="取消">
                <X :size="15" />
              </button>
            </div>

            <!-- merge row -->
            <div v-else class="flex items-center gap-2">
              <span class="shrink-0 text-sm text-white/70 truncate max-w-[30%]">合并「{{ t.name }}」→</span>
              <ThemeSelect v-model="mergeTargetId" :options="mergeOptions" class="min-w-0 flex-1" />
              <button @click="confirmMerge(t)" :disabled="busy || !mergeTargetId" class="p-1.5 rounded-lg bg-accent/80 text-white hover:bg-accent disabled:opacity-40" title="确认合并">
                <Check :size="15" />
              </button>
              <button @click="mergeId = null" class="p-1.5 rounded-lg text-white/40 hover:text-white hover:bg-white/8" title="取消">
                <X :size="15" />
              </button>
            </div>

            <p v-if="rowError && rowError.id === t.id" class="mt-1.5 text-xs text-red-300">{{ rowError.msg }}</p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
