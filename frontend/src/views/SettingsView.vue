<script setup lang="ts">
import { onMounted, ref } from 'vue'
import axios from 'axios'
import { Check, FolderOpen, FolderPlus, HardDrive, Image as ImageIcon, Palette, RefreshCw, Timer, Trash2, X } from 'lucide-vue-next'
import { API_BASE_URL } from '../config'
import { applyTheme, getStoredTheme, themes } from '../theme'
import type { Folder } from '../types'

const folders = ref<Folder[]>([])
const newPath = ref('')
const scanMode = ref<Folder['scan_mode']>('auto')
const thumbnailEnabled = ref(true)
const thumbnailInterval = ref(1)
const loading = ref(false)
const showAddModal = ref(false)
const selectedTheme = ref<string>(getStoredTheme())

const modes: Array<{ id: Folder['scan_mode']; label: string; description: string }> = [
  { id: 'auto', label: '自动', description: '自动识别视频、漫画压缩包、单张图片、单文件音频' },
  { id: 'video', label: '视频', description: '只递归扫描视频文件' },
  { id: 'image', label: '杂图', description: '只递归扫描单张图片' },
  { id: 'manga', label: '漫画', description: '把包含图片的文件夹识别为一本漫画' },
  { id: 'audio', label: '音频（单文件）', description: '每个 .mp3/.wav/.flac 单独入库（散装音乐收藏）' },
  { id: 'audio_work', label: '音频作品', description: '把每个文件夹识别为一个音频作品（ASMR 等，含 tracks.json 时优先读取）' },
]

const selectTheme = (themeId: string) => {
  selectedTheme.value = themeId
  applyTheme(themeId)
}

const fetchFolders = async () => {
  try {
    const res = await axios.get(`${API_BASE_URL}/folders`)
    folders.value = res.data
    if (folders.value.some(f => f.status === 'scanning')) {
      window.setTimeout(fetchFolders, 2000)
    }
  } catch (err) {
    console.error('无法连接到后端服务，请检查后端是否启动。', err)
  }
}

const openAddModal = () => {
  showAddModal.value = true
}

const closeAddModal = () => {
  showAddModal.value = false
  newPath.value = ''
  scanMode.value = 'auto'
  thumbnailEnabled.value = true
  thumbnailInterval.value = 1
}

const browseFolder = async () => {
  try {
    if (!('showDirectoryPicker' in window)) {
      alert('当前浏览器不支持目录选择，请手动输入绝对路径。')
      return
    }

    const dirHandle = await (window as any).showDirectoryPicker()
    const folderName = dirHandle.name
    try {
      const res = await axios.get(`${API_BASE_URL}/search-folder`, { params: { name: folderName } })
      newPath.value = res.data.results?.[0] || folderName
    } catch {
      newPath.value = folderName
    }
  } catch (err: any) {
    if (err?.name !== 'AbortError') {
      console.error('文件夹选择失败:', err)
    }
  }
}

const addFolder = async () => {
  if (!newPath.value) return
  loading.value = true
  try {
    thumbnailInterval.value = Math.min(60, Math.max(1, Number(thumbnailInterval.value) || 1))
    await axios.post(`${API_BASE_URL}/folders`, {
      path: newPath.value,
      scan_mode: scanMode.value,
      thumbnail_enabled: thumbnailEnabled.value,
      thumbnail_interval: thumbnailInterval.value,
    })
    await fetchFolders()
    closeAddModal()
  } catch (err: any) {
    const errorMsg = err.response?.data?.detail || '添加文件夹失败，请确认路径是有效的绝对路径。'
    alert(errorMsg)
  } finally {
    loading.value = false
  }
}

const scanFolder = async (id: number) => {
  const folder = folders.value.find(f => f.id === id)
  if (folder) folder.status = 'scanning'

  try {
    await axios.post(`${API_BASE_URL}/folders/${id}/scan`)
    window.setTimeout(fetchFolders, 1000)
  } catch (err) {
    console.error(err)
    if (folder) folder.status = 'idle'
  }
}

const removeFolder = async (id: number) => {
  if (!confirm('确定要从库中移除此目录吗？\n该操作不会删除硬盘上的文件，只会清理库中的媒体记录。')) return
  try {
    await axios.delete(`${API_BASE_URL}/folders/${id}`)
    await fetchFolders()
  } catch (err) {
    console.error(err)
  }
}

const formatLocalTime = (timeStr: string | null) => {
  if (!timeStr) return ''
  return timeStr.replace('T', ' ').split('.')[0]
}

onMounted(fetchFolders)
</script>

<template>
  <div class="p-6 md:p-8 max-w-5xl mx-auto z-10 relative">
    <header class="mb-10 flex flex-wrap justify-between items-end gap-5">
      <div>
        <h1 class="text-3xl md:text-4xl font-black mb-3 text-white">偏好设置</h1>
        <p class="text-white/50 text-base md:text-lg">配置媒体库来源、扫描行为和界面主题。</p>
      </div>
      <button @click="openAddModal" class="bg-accent hover:bg-accent-glow text-white px-5 py-3 rounded-xl font-semibold flex items-center gap-2 transition-all shadow-lg shadow-accent/20 active:scale-95">
        <FolderPlus :size="20" />
        添加媒体库
      </button>
    </header>

    <div class="space-y-8">
      <section class="border border-white/10 bg-sidebar/45 backdrop-blur-xl rounded-2xl p-6 md:p-8 shadow-2xl">
        <div class="flex items-center gap-3 mb-6">
          <Palette class="text-accent" />
          <div>
            <h2 class="text-xl font-bold text-white/90">主题</h2>
            <p class="text-sm text-white/45 mt-1">选择你喜欢的颜色风格，设置会保存在本机。</p>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5 gap-4">
          <button
            v-for="theme in themes"
            :key="theme.id"
            @click="selectTheme(theme.id)"
            :class="selectedTheme === theme.id ? 'border-accent bg-accent/10 text-white' : 'border-white/10 bg-white/5 text-white/70 hover:bg-white/10'"
            class="relative text-left rounded-xl border p-4 transition-all min-h-[136px]"
          >
            <span v-if="selectedTheme === theme.id" class="absolute right-3 top-3 w-7 h-7 rounded-lg bg-accent text-white flex items-center justify-center">
              <Check :size="16" />
            </span>

            <div class="flex gap-2 mb-4">
              <span
                v-for="color in theme.swatches"
                :key="color"
                class="w-8 h-8 rounded-lg border border-white/15"
                :style="{ backgroundColor: color }"
              ></span>
            </div>
            <p class="font-bold text-white">{{ theme.name }}</p>
            <p class="text-xs text-white/45 mt-1 leading-relaxed pr-5">{{ theme.description }}</p>
          </button>

          <!-- Custom Theme Picker -->
          <label
            :class="selectedTheme.startsWith('#') ? 'border-accent bg-accent/10 text-white' : 'border-white/10 bg-white/5 text-white/70 hover:bg-white/10'"
            class="relative text-left rounded-xl border p-4 transition-all min-h-[136px] block cursor-pointer"
          >
            <input 
              type="color" 
              :value="selectedTheme.startsWith('#') ? selectedTheme : '#818cf8'"
              @input="(e) => selectTheme((e.target as HTMLInputElement).value)"
              class="absolute opacity-0 w-0 h-0"
              title="选择自定义颜色"
            />

            <span v-if="selectedTheme.startsWith('#')" class="absolute right-3 top-3 w-7 h-7 rounded-lg bg-accent text-white flex items-center justify-center pointer-events-none">
              <Check :size="16" />
            </span>

            <div class="flex gap-2 mb-4 pointer-events-none">
              <!-- Single color swatch / Rainbow -->
              <span
                class="w-8 h-8 rounded-lg"
                :class="selectedTheme.startsWith('#') ? 'border border-white/15' : 'shadow-inner'"
                :style="selectedTheme.startsWith('#') ? { backgroundColor: selectedTheme } : { background: 'linear-gradient(135deg, #ff0000, #ff7f00, #ffff00, #00ff00, #0000ff, #4b0082, #9400d3)' }"
              ></span>
            </div>
            
            <p class="font-bold text-white pointer-events-none">自定义颜色</p>
            <p class="text-xs text-white/45 mt-1 leading-relaxed pr-5 pointer-events-none">选择您喜欢的色系，一键生成全局主题。</p>
          </label>
        </div>
      </section>

      <section class="border border-white/10 bg-sidebar/45 backdrop-blur-xl rounded-2xl p-6 md:p-8 shadow-2xl">
        <h2 class="text-xl font-bold flex items-center gap-3 mb-6 text-white/90">
          <HardDrive class="text-green-400" />
          已挂载目录
        </h2>

        <div v-if="folders.length === 0" class="text-center py-12 border-2 border-dashed border-white/10 rounded-xl">
          <p class="text-white/35 font-medium">尚未添加任何扫描来源</p>
        </div>

        <div v-else class="space-y-4">
          <div
            v-for="folder in folders"
            :key="folder.id"
            class="group flex flex-wrap items-center justify-between gap-4 p-5 bg-black/20 hover:bg-black/35 border border-white/10 rounded-xl transition-all"
          >
            <div class="min-w-0">
              <p class="font-mono text-base md:text-lg text-white/90 break-all">{{ folder.path }}</p>
              <div class="flex flex-wrap items-center gap-3 mt-2">
                <span class="text-[10px] font-black uppercase bg-white/10 px-2 py-0.5 rounded border border-white/10 text-white/65">
                  {{ folder.scan_mode }}
                </span>
                <span v-if="folder.scan_mode === 'video' || folder.scan_mode === 'auto'" class="text-[10px] font-black uppercase bg-white/10 px-2 py-0.5 rounded border border-white/10 text-white/65">
                  {{ folder.thumbnail_enabled ? `THUMB ${folder.thumbnail_interval}s` : 'THUMB OFF' }}
                </span>
                <p class="text-sm flex items-center gap-2" :class="folder.status === 'scanning' ? 'text-accent animate-pulse' : 'text-white/80'">
                  <span class="w-1.5 h-1.5 rounded-full" :class="folder.status === 'scanning' ? 'bg-accent shadow-[0_0_8px_rgba(129,140,248,0.8)]' : 'bg-green-500'"></span>
                  {{ folder.status === 'scanning' ? '深度扫描中...' : '空闲' }}
                  <span class="text-white/50" v-if="folder.last_scanned_at">
                    上次扫描: {{ formatLocalTime(folder.last_scanned_at) }}
                  </span>
                </p>
              </div>
            </div>

            <div class="flex gap-2">
              <button
                @click="scanFolder(folder.id)"
                :disabled="folder.status === 'scanning'"
                class="w-11 h-11 rounded-xl bg-white/5 hover:bg-accent/20 hover:text-accent flex items-center justify-center transition-all disabled:opacity-50 active:scale-90"
                title="重新扫描"
              >
                <RefreshCw :class="{ 'animate-spin text-accent': folder.status === 'scanning' }" :size="20" />
              </button>

              <button
                @click="removeFolder(folder.id)"
                class="w-11 h-11 rounded-xl bg-white/5 hover:bg-red-500/10 hover:text-red-400 flex items-center justify-center transition-all active:scale-90"
                title="从库中移除"
              >
                <Trash2 :size="20" />
              </button>
            </div>
          </div>
        </div>
      </section>
    </div>

    <Teleport to="body">
      <div v-if="showAddModal" class="fixed inset-0 z-[200] flex items-center justify-center">
        <div class="absolute inset-0 bg-background/80 backdrop-blur-sm" @click="closeAddModal"></div>

        <div class="relative w-full max-w-2xl bg-sidebar/95 backdrop-blur-xl border border-white/10 rounded-2xl p-6 md:p-8 shadow-2xl m-4">
          <div class="flex justify-between items-center mb-7">
            <h2 class="text-2xl font-bold flex items-center gap-3 text-white/90">
              <FolderPlus class="text-accent" />
              添加新来源
            </h2>
            <button @click="closeAddModal" class="text-white/45 hover:text-white transition-colors w-10 h-10 rounded-xl hover:bg-white/5 flex items-center justify-center">
              <X :size="23" />
            </button>
          </div>

          <div class="space-y-6">
            <div>
              <label class="block text-xs font-bold text-white/45 uppercase tracking-widest mb-2 ml-1">文件夹绝对路径</label>
              <div class="flex gap-2">
                <input
                  v-model="newPath"
                  type="text"
                  class="flex-1 min-w-0 bg-black/35 border border-white/10 rounded-xl px-4 py-3.5 text-white placeholder-white/30 focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent transition-all"
                  placeholder="例如: D:\Manga\Collection"
                />
                <button
                  @click="browseFolder"
                  class="shrink-0 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl px-4 py-3 text-white/70 hover:text-white transition-all flex items-center gap-2 active:scale-95"
                  title="浏览文件夹"
                >
                  <FolderOpen :size="20" />
                  <span class="text-sm font-medium hidden sm:inline">浏览</span>
                </button>
              </div>
            </div>

            <div>
              <label class="block text-xs font-bold text-white/45 uppercase tracking-widest mb-2 ml-1">识别模式</label>
              <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
                <button
                  v-for="mode in modes"
                  :key="mode.id"
                  @click="scanMode = mode.id"
                  :class="scanMode === mode.id ? 'bg-accent/20 border-accent text-white' : 'bg-white/5 border-white/10 text-white/50 hover:bg-white/10'"
                  class="flex flex-col items-start p-4 rounded-xl border transition-all text-left min-h-[104px]"
                >
                  <span class="text-sm font-black uppercase tracking-widest mb-1">{{ mode.label }}</span>
                  <span class="text-xs opacity-70 leading-relaxed">{{ mode.description }}</span>
                </button>
              </div>
            </div>

            <div v-if="scanMode === 'video' || scanMode === 'auto'" class="rounded-xl border border-white/10 bg-black/20 p-5 space-y-5">
              <div class="flex items-center justify-between gap-4">
                <div class="flex items-center gap-3">
                  <div class="w-10 h-10 rounded-xl bg-accent/15 text-accent flex items-center justify-center">
                    <ImageIcon :size="20" />
                  </div>
                  <div>
                    <p class="text-sm font-bold text-white/90">生成进度条预览</p>
                    <p class="text-xs text-white/45 mt-0.5">封面始终生成，此选项只影响播放器悬停预览。</p>
                  </div>
                </div>
                <button
                  type="button"
                  @click="thumbnailEnabled = !thumbnailEnabled"
                  :class="thumbnailEnabled ? 'bg-accent' : 'bg-white/10'"
                  class="relative w-12 h-7 rounded-full transition-colors shrink-0"
                  :aria-pressed="thumbnailEnabled"
                >
                  <span
                    :class="thumbnailEnabled ? 'translate-x-5' : 'translate-x-1'"
                    class="absolute top-1 left-0 w-5 h-5 rounded-full bg-white transition-transform"
                  ></span>
                </button>
              </div>

              <div :class="thumbnailEnabled ? 'opacity-100' : 'opacity-40 pointer-events-none'" class="transition-opacity">
                <div class="flex items-center justify-between mb-2">
                  <label class="text-xs font-bold text-white/50 uppercase tracking-widest flex items-center gap-2">
                    <Timer :size="14" />
                    生成间隔
                  </label>
                  <span class="text-sm font-mono text-white/80">{{ thumbnailInterval }} 秒</span>
                </div>
                <div class="flex items-center gap-4">
                  <input v-model.number="thumbnailInterval" type="range" min="1" max="60" step="1" class="flex-1 accent-indigo-400" />
                  <input v-model.number="thumbnailInterval" type="number" min="1" max="60" class="w-20 bg-black/35 border border-white/10 rounded-xl px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/60" />
                </div>
                <p class="text-xs text-white/35 mt-2">电脑性能一般可以设为 5-10 秒；想要更细的预览就设为 1-2 秒。</p>
              </div>
            </div>

            <div class="flex items-center justify-between pt-5 border-t border-white/10 gap-4">
              <p class="text-sm text-white/45 flex items-center gap-2">
                <HardDrive :size="14" /> 请确认路径在本机真实存在
              </p>
              <button
                @click="addFolder"
                :disabled="loading"
                class="bg-accent hover:bg-accent-glow text-white px-6 py-3 rounded-xl font-bold flex items-center gap-2 transition-all disabled:opacity-50 active:scale-95"
              >
                <RefreshCw v-if="loading" class="animate-spin" :size="18" />
                <FolderPlus v-else :size="18" />
                {{ loading ? '正在扫描...' : '确认添加' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
