<script setup lang="ts">
import { computed, ref } from 'vue'
import { bootstrapAdmin, login } from '../auth'

const props = defineProps<{
  hasUsers: boolean
  startupError?: string
}>()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

const modeText = computed(() => props.hasUsers ? '登录' : '创建管理员')
const hintText = computed(() => props.hasUsers ? '输入账号密码进入媒体库' : '第一次使用，请先创建管理员账号')

const submit = async () => {
  if (!username.value.trim() || !password.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    if (props.hasUsers) {
      await login(username.value.trim(), password.value)
    } else {
      await bootstrapAdmin(username.value.trim(), password.value)
    }
  } catch (err: any) {
    errorMessage.value = err.response?.data?.detail || `${modeText.value}失败`
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen w-full bg-background text-white flex items-center justify-center px-6">
    <div class="fixed inset-0 pointer-events-none bg-[radial-gradient(circle_at_top_left,rgba(255,255,255,0.07),transparent_32%),linear-gradient(135deg,rgba(255,255,255,0.03),transparent_45%)]"></div>
    <form
      @submit.prevent="submit"
      class="relative z-10 w-full max-w-sm bg-white/[0.04] border border-white/10 rounded-2xl p-6 shadow-2xl space-y-5"
    >
      <div>
        <div class="w-11 h-11 rounded-xl bg-accent text-white font-black flex items-center justify-center mb-4">HE</div>
        <h1 class="text-2xl font-black tracking-tight">{{ modeText }}</h1>
        <p class="text-sm text-white/45 mt-1">{{ hintText }}</p>
      </div>

      <p v-if="startupError" class="text-sm text-amber-100 bg-amber-400/10 border border-amber-300/20 rounded-xl px-3 py-2">
        {{ startupError }}
      </p>

      <label class="block space-y-2">
        <span class="text-xs font-bold text-white/55">用户名</span>
        <input
          v-model="username"
          autocomplete="username"
          class="w-full bg-black/25 border border-white/10 rounded-xl px-3 py-3 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50"
        />
      </label>

      <label class="block space-y-2">
        <span class="text-xs font-bold text-white/55">密码</span>
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          class="w-full bg-black/25 border border-white/10 rounded-xl px-3 py-3 text-sm text-white focus:outline-none focus:ring-2 focus:ring-accent/50"
        />
      </label>

      <p v-if="errorMessage" class="text-sm text-red-200 bg-red-400/10 border border-red-400/20 rounded-xl px-3 py-2">
        {{ errorMessage }}
      </p>

      <button
        type="submit"
        :disabled="loading || !username.trim() || !password"
        class="w-full h-12 rounded-xl bg-accent text-white font-black hover:brightness-110 disabled:opacity-45 disabled:cursor-not-allowed transition-all"
      >
        {{ loading ? '处理中' : modeText }}
      </button>
    </form>
  </div>
</template>
