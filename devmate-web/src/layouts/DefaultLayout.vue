<script setup lang="ts">
import { RouterView, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

async function logout(): Promise<void> {
  authStore.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <span class="brand">DevMate</span>
      <div class="account">
        <span>{{ authStore.user?.nickname || authStore.user?.username }}</span>
        <el-button text @click="logout">退出登录</el-button>
      </div>
    </header>
    <main class="app-content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: 0 32px;
  border-bottom: 1px solid #dcdfe6;
  background: #ffffff;
}

.brand {
  font-size: 20px;
  font-weight: 600;
}

.stage {
  color: #909399;
  font-size: 14px;
}

.app-content {
  display: grid;
  min-height: calc(100vh - 64px);
  padding: 32px;
}

.account {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #606266;
}
</style>
