<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <span class="brand">DevMate</span>
      <span class="stage">Frontend Foundation</span>
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
  place-items: center;
  padding: 32px;
}
</style>
