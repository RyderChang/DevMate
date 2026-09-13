<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from 'vue-router'

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
      <div class="app-navigation">
        <RouterLink class="brand" to="/projects">DevMate</RouterLink>
        <RouterLink class="navigation-link" to="/projects">项目</RouterLink>
      </div>
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
  color: #213547;
  font-size: 20px;
  font-weight: 600;
}

.brand:hover,
.navigation-link:hover {
  text-decoration: none;
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

.app-navigation {
  display: flex;
  align-items: center;
  gap: 28px;
}

.navigation-link {
  color: #606266;
  font-weight: 500;
}

.navigation-link.router-link-active {
  color: #409eff;
}

@media (max-width: 480px) {
  .app-header {
    padding: 0 16px;
  }

  .app-navigation {
    gap: 16px;
  }

  .account > span {
    display: none;
  }

  .app-content {
    padding: 20px 16px;
  }
}
</style>
