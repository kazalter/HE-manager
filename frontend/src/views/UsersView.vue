<script setup lang="ts">
import { onMounted, ref } from 'vue'
import axios from 'axios'
import { Edit, Plus, RefreshCw, ShieldCheck, Trash2, UserRound, X } from 'lucide-vue-next'
import { API_BASE_URL } from '../config'
import { authState } from '../auth'
import type { User } from '../types'

const users = ref<User[]>([])
const loading = ref(false)
const showDialog = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const username = ref('')
const password = ref('')
const isAdmin = ref(false)
const isActive = ref(true)
const editingUser = ref<User | null>(null)

const fetchUsers = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await axios.get(`${API_BASE_URL}/users`)
    users.value = res.data
  } catch (err: any) {
    errorMessage.value = err.response?.data?.detail || '读取用户失败'
  } finally {
    loading.value = false
  }
}

const openDialog = (user?: User) => {
  if (user && typeof user === 'object' && 'id' in user) {
    editingUser.value = user
    username.value = user.username
    password.value = ''
    isAdmin.value = user.is_admin
    isActive.value = user.is_active
  } else {
    editingUser.value = null
    username.value = ''
    password.value = ''
    isAdmin.value = false
    isActive.value = true
  }
  errorMessage.value = ''
  showDialog.value = true
}

const closeDialog = () => {
  showDialog.value = false
}

const saveUser = async () => {
  if (!username.value.trim()) return
  if (!editingUser.value && !password.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    if (editingUser.value) {
      await axios.put(`${API_BASE_URL}/users/${editingUser.value.id}`, {
        username: username.value.trim(),
        password: password.value || undefined,
        is_admin: isAdmin.value,
        is_active: isActive.value,
      })
    } else {
      await axios.post(`${API_BASE_URL}/users`, {
        username: username.value.trim(),
        password: password.value,
        is_admin: isAdmin.value,
      })
    }
    closeDialog()
    await fetchUsers()
  } catch (err: any) {
    errorMessage.value = err.response?.data?.detail || '保存失败'
  } finally {
    saving.value = false
  }
}

const deleteUser = async (user: User) => {
  if (!confirm(`确定要删除用户 "${user.username}" 吗？`)) return
  try {
    await axios.delete(`${API_BASE_URL}/users/${user.id}`)
    await fetchUsers()
  } catch (err: any) {
    alert(err.response?.data?.detail || '删除失败')
  }
}

onMounted(fetchUsers)
</script>

<template>
  <div class="z-10 relative min-h-screen px-6 md:px-8 py-7">
    <header class="flex flex-wrap items-center justify-between gap-4 mb-6">
      <div>
        <h1 class="text-2xl md:text-3xl font-black text-white tracking-tight">用户管理</h1>
        <p class="text-sm text-white/45 mt-1">管理可以登录网页端和安卓端的账号</p>
      </div>
      <div class="flex items-center gap-3">
        <button
          v-if="authState.user?.is_admin"
          @click="openDialog()"
          class="h-11 px-4 rounded-xl bg-accent text-white hover:bg-accent/90 flex items-center gap-2 text-sm font-bold transition-all"
        >
          <Plus :size="16" />
          创建用户
        </button>
        <button
          @click="fetchUsers"
          class="h-11 px-4 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:text-white flex items-center gap-2 text-sm font-bold transition-all"
        >
          <RefreshCw :size="16" :class="loading ? 'animate-spin' : ''" />
          刷新
        </button>
      </div>
    </header>

    <div v-if="!authState.user?.is_admin" class="rounded-2xl border border-amber-300/20 bg-amber-400/10 p-5 text-amber-100">
      当前账号不是管理员，不能管理用户。
    </div>

    <section v-else class="bg-white/[0.04] border border-white/10 rounded-2xl overflow-hidden">
      <div class="px-5 py-4 border-b border-white/10 flex items-center justify-between">
        <h2 class="text-base font-black text-white">用户列表</h2>
        <span class="text-xs text-white/40">{{ users.length }} 个用户</span>
      </div>
      <div class="divide-y divide-white/10">
        <div v-for="user in users" :key="user.id" class="px-5 py-4 flex items-center justify-between gap-4">
          <div class="flex items-center gap-3 min-w-0">
            <div class="w-10 h-10 rounded-xl bg-white/5 border border-white/10 flex items-center justify-center text-white/70">
              <UserRound :size="19" />
            </div>
            <div class="min-w-0">
              <p class="font-black text-white truncate">{{ user.username }}</p>
              <p class="text-xs text-white/40">创建于 {{ new Date(user.created_at).toLocaleString() }}</p>
            </div>
          </div>
          <div class="flex items-center gap-4 shrink-0">
            <div class="flex items-center gap-2">
              <span v-if="user.is_admin" class="rounded-lg bg-accent/15 border border-accent/20 text-accent px-2 py-1 text-[11px] font-black flex items-center gap-1">
                <ShieldCheck :size="13" />
                管理员
              </span>
              <span :class="user.is_active ? 'text-emerald-300 bg-emerald-400/10 border-emerald-400/15' : 'text-red-300 bg-red-400/10 border-red-400/15'" class="rounded-lg border px-2 py-1 text-[11px] font-black">
                {{ user.is_active ? '启用' : '停用' }}
              </span>
            </div>
            
            <div class="flex items-center gap-1">
              <button @click="openDialog(user)" class="w-8 h-8 rounded-lg bg-white/5 border border-white/10 text-white/50 hover:text-white hover:bg-white/10 flex items-center justify-center transition-all">
                <Edit :size="14" />
              </button>
              <button 
                v-if="user.id !== authState.user?.id"
                @click="deleteUser(user)" 
                class="w-8 h-8 rounded-lg bg-white/5 border border-white/10 text-red-400/60 hover:text-red-400 hover:bg-red-400/10 flex items-center justify-center transition-all"
              >
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <Teleport to="body">
      <Transition name="modal-snap">
        <div v-if="showDialog" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-background/60 backdrop-blur-xl" @click.self="closeDialog">
          <div class="modal-content relative bg-[rgb(var(--color-sidebar))] border border-white/10 rounded-2xl p-6 w-full max-w-md shadow-2xl">
          <div class="flex items-center justify-between mb-5">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-accent/15 text-accent flex items-center justify-center">
                <Edit v-if="editingUser" :size="20" />
                <Plus v-else :size="20" />
              </div>
              <div>
                <h3 class="text-base font-black text-white">{{ editingUser ? '修改用户' : '创建用户' }}</h3>
                <p class="text-xs text-white/45">{{ editingUser ? '修改账号属性或密码' : '新用户可登录手机 app 和网页端' }}</p>
              </div>
            </div>
            <button @click="closeDialog" class="w-8 h-8 rounded-lg bg-white/5 border border-white/10 text-white/50 hover:text-white flex items-center justify-center transition-all">
              <X :size="16" />
            </button>
          </div>

          <form @submit.prevent="saveUser" class="space-y-4">
            <label class="block space-y-2">
              <span class="text-xs font-bold text-white/55">用户名</span>
              <input v-model="username" class="w-full bg-black/30 border border-white/10 rounded-xl px-3 py-3 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50" />
            </label>

            <label class="block space-y-2">
              <span class="text-xs font-bold text-white/55">{{ editingUser ? '修改密码 (留空保持不变)' : '初始密码' }}</span>
              <input v-model="password" type="password" class="w-full bg-black/30 border border-white/10 rounded-xl px-3 py-3 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50" />
            </label>

            <div class="grid grid-cols-2 gap-3">
              <label class="flex items-center gap-3 rounded-xl bg-black/30 border border-white/10 px-3 py-3 cursor-pointer">
                <input v-model="isAdmin" type="checkbox" class="w-4 h-4 accent-accent" />
                <span class="text-sm font-bold text-white/70">管理员</span>
              </label>

              <label class="flex items-center gap-3 rounded-xl bg-black/30 border border-white/10 px-3 py-3 cursor-pointer">
                <input v-model="isActive" type="checkbox" class="w-4 h-4 accent-accent" />
                <span class="text-sm font-bold text-white/70">启用账号</span>
              </label>
            </div>

            <p v-if="errorMessage" class="text-sm text-red-200 bg-red-400/10 border border-red-400/20 rounded-xl px-3 py-2">
              {{ errorMessage }}
            </p>

            <div class="flex gap-3 pt-2">
              <button type="button" @click="closeDialog" class="flex-1 h-12 rounded-xl bg-white/5 border border-white/10 text-white/70 font-bold hover:bg-white/10 transition-all">
                取消
              </button>
              <button type="submit" :disabled="saving || !username.trim() || (!editingUser && !password)" class="flex-1 h-12 rounded-xl bg-accent text-white font-black disabled:opacity-45 disabled:cursor-not-allowed transition-all">
                {{ saving ? '保存中' : (editingUser ? '保存' : '创建') }}
              </button>
            </div>
          </form>
        </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
