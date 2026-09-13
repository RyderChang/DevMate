<template>
  <el-card class="home-card" shadow="never">
    <h1>欢迎回来，{{ authStore.user?.nickname || authStore.user?.username }}</h1>
    <p>认证流程已接通。项目空间页面将在后续任务中实现。</p>
  </el-card>
</template>

<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
</script>

<style scoped>
.home-card {
  width: min(100%, 640px);
  margin: 64px auto;
  text-align: center;
}

h1 {
  margin: 0 0 16px;
  font-size: 26px;
}

p {
  margin: 0;
  color: #606266;
  line-height: 1.7;
}
</style>
