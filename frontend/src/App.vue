<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { authState, initAuth, logout } from './auth'
import Sidebar from './components/Sidebar.vue'
import AuthView from './views/AuthView.vue'

const isCollapsed = ref(false)
const route = useRoute()

const isEmbed = computed(() => {
  return route.path.endsWith('/embed') || route.query.embed === 'true'
})

onMounted(initAuth)
</script>

<template>
  <AuthView v-if="authState.ready && !authState.user" :has-users="authState.hasUsers" :startup-error="authState.error" />
  <div
    v-else-if="authState.ready"
    class="h-screen w-full bg-background text-white/90 font-sans selection:bg-accent selection:text-white relative overflow-hidden flex"
  >
    <div class="fixed inset-0 pointer-events-none bg-[radial-gradient(circle_at_top_left,rgba(255,255,255,0.06),transparent_32%),linear-gradient(135deg,rgba(255,255,255,0.03),transparent_45%)]"></div>

    <Sidebar
      v-if="!isEmbed"
      v-model:collapsed="isCollapsed"
      class="shrink-0 relative z-40 transition-all duration-300 ease-in-out"
      :class="isCollapsed ? 'w-20' : 'w-64'"
      :user="authState.user"
      @logout="logout"
    />

    <main
      class="flex-1 min-w-0 relative z-10 box-border"
      :class="isEmbed ? 'h-screen overflow-hidden' : 'h-screen overflow-y-auto overflow-x-hidden scroll-smooth custom-scrollbar'"
    >
      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
      <div v-if="!isEmbed" class="h-20 w-full"></div>
    </main>
  </div>
  <div v-else class="h-screen w-full bg-background text-white/50 flex items-center justify-center">
    正在检查登录状态
  </div>
</template>

<style>
.page-fade-enter-active,
.page-fade-leave-active {
  transition: all 0.2s ease;
}

.page-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.12);
  border-radius: 10px;
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.22);
}
</style>
