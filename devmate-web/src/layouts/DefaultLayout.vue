<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import AppIcon from '@/components/AppIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { parseProjectId } from '@/utils/projects'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const projectId = computed(() => parseProjectId(route.params.projectId))
const inConversation = computed(() => String(route.name).startsWith('conversation-'))
const accountName = computed(
  () => authStore.user?.nickname || authStore.user?.username || '我的账号',
)
const accountInitial = computed(() => Array.from(accountName.value)[0]?.toUpperCase() || 'D')
const pageLabels: Record<string, string> = {
  'project-list': '我的项目',
  'project-create': '创建项目',
  'project-detail': '项目概览',
  'project-edit': '编辑项目',
  'conversation-list': '项目对话',
  'conversation-chat': '对话工作区',
}
const pageLabel = computed(() => pageLabels[String(route.name)] || '项目空间')

async function logout(): Promise<void> {
  authStore.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside class="app-sidebar" aria-label="工作空间导航">
      <RouterLink class="brand" to="/projects"
        ><span class="brand-mark"><AppIcon name="code" /></span>DevMate</RouterLink
      >
      <div class="workspace-label"><span class="workspace-indicator"></span>个人工作空间</div>
      <nav class="sidebar-nav" aria-label="主导航">
        <p class="nav-caption">工作空间</p>
        <RouterLink
          class="navigation-link"
          :class="{ 'is-active': projectId === null }"
          :aria-current="projectId === null ? 'page' : undefined"
          to="/projects"
        >
          <AppIcon name="grid" /><span>我的项目</span><AppIcon name="chevron" class="nav-arrow" />
        </RouterLink>
        <template v-if="projectId !== null">
          <p class="nav-caption project-nav-caption">当前项目</p>
          <RouterLink
            class="navigation-link"
            :class="{ 'is-active': !inConversation }"
            :aria-current="!inConversation ? 'page' : undefined"
            :to="{ name: 'project-detail', params: { projectId } }"
            ><AppIcon name="folder" /><span>项目概览</span></RouterLink
          >
          <RouterLink
            class="navigation-link"
            :class="{ 'is-active': inConversation }"
            :aria-current="inConversation ? 'page' : undefined"
            :to="{ name: 'conversation-list', params: { projectId } }"
            ><AppIcon name="chat" /><span>项目对话</span></RouterLink
          >
        </template>
      </nav>
      <div class="sidebar-bottom">
        <span class="sidebar-rule"></span>
        <p>保持上下文，<br />专注下一次交付。</p>
        <span class="sidebar-signature">DevMate Workspace</span>
      </div>
    </aside>
    <div class="workspace-area">
      <header class="app-header">
        <div class="header-location">
          <span>工作空间</span><AppIcon name="chevron" /><span class="header-current">{{
            pageLabel
          }}</span>
        </div>
        <div class="account">
          <span class="account-avatar" aria-hidden="true">{{ accountInitial }}</span
          ><span class="account-name">{{ accountName }}</span
          ><span class="account-divider"></span
          ><el-button text class="logout-button" @click="logout"
            ><AppIcon name="logout" /><span>退出登录</span></el-button
          >
        </div>
      </header>
      <main id="main-content" class="app-content" tabindex="-1"><RouterView /></main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: grid;
  grid-template-columns: 224px minmax(0, 1fr);
  min-height: 100vh;
}
.app-sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  height: 100dvh;
  padding: 30px 18px 26px;
  color: #a3adc0;
  background: var(--dm-sidebar);
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  width: fit-content;
  padding: 0 12px;
  color: #fff;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.6px;
}
.brand:hover {
  text-decoration: none;
}
.workspace-label {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 27px 14px 26px;
  font-size: 12px;
}
.workspace-indicator {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  background: #8599bd;
}
.sidebar-nav {
  display: grid;
  gap: 5px;
}
.nav-caption {
  margin: 4px 14px 9px;
  color: #7e8aa1;
  font-size: 11px;
  letter-spacing: 0.08em;
}
.project-nav-caption {
  margin-top: 28px;
}
.navigation-link {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 10px 14px;
  border-radius: 7px;
  color: #b0b9ca;
  font-size: 13px;
}
.navigation-link:hover {
  color: #fff;
  background: #ffffff08;
  text-decoration: none;
}
.navigation-link.is-active {
  color: #f3f6ff;
  background: #2a3650;
  box-shadow: inset 2px 0 #8eacff;
}
.nav-arrow {
  width: 13px;
  margin-left: auto;
  opacity: 0.6;
}
.sidebar-bottom {
  margin: auto 14px 0;
  padding-top: 64px;
}
.sidebar-rule {
  display: block;
  width: 26px;
  height: 2px;
  background: #566b93;
}
.sidebar-bottom p {
  margin: 20px 0 14px;
  color: #bac3d3;
  font-size: 13px;
  line-height: 1.9;
}
.sidebar-signature {
  font-size: 10px;
  letter-spacing: 0.04em;
  color: #7e8aa1;
}
.workspace-area {
  min-width: 0;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 76px;
  padding: 0 clamp(24px, 4vw, 56px);
  border-bottom: 1px solid var(--dm-border);
  background: var(--dm-surface);
}
.header-location {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--dm-muted);
  font-size: 12px;
}
.header-location .app-icon {
  width: 12px;
  color: #a7afbd;
}
.header-current {
  color: var(--dm-text);
}
.account {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: var(--dm-secondary);
  font-size: 12px;
}
.account-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  border: 1px solid #dce4f4;
  border-radius: 50%;
  color: #4260a2;
  background: #edf2fc;
  font-size: 12px;
  font-weight: 650;
}
.account-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.account-divider {
  height: 16px;
  width: 1px;
  background: var(--dm-border);
  margin: 0 4px;
}
.logout-button {
  color: var(--dm-secondary);
  padding: 8px;
}
.logout-button :deep(> span) {
  gap: 7px;
}
.app-content {
  min-width: 0;
  padding: 40px clamp(24px, 4vw, 56px) 48px;
  outline: none;
}
@media (max-width: 1000px) {
  .app-shell {
    grid-template-columns: 192px minmax(0, 1fr);
  }
  .app-sidebar {
    padding-inline: 12px;
  }
  .account-name {
    max-width: 90px;
  }
}
@media (max-width: 760px) {
  .app-shell {
    display: block;
  }
  .app-sidebar {
    position: static;
    height: auto;
    padding: 18px 20px 14px;
  }
  .brand {
    padding: 0;
    font-size: 20px;
  }
  .workspace-label,
  .nav-caption,
  .sidebar-bottom {
    display: none;
  }
  .sidebar-nav {
    display: flex;
    flex-wrap: wrap;
    margin-top: 18px;
    gap: 7px;
  }
  .navigation-link {
    min-height: 38px;
    padding: 8px 12px;
    font-size: 12px;
    gap: 8px;
  }
  .nav-arrow {
    display: none;
  }
  .app-header {
    height: 62px;
    padding: 0 20px;
    gap: 8px;
  }
  .header-location {
    gap: 6px;
    font-size: 11px;
  }
  .account-name,
  .account-divider {
    display: none;
  }
  .account {
    gap: 4px;
  }
  .logout-button {
    font-size: 11px;
    padding-inline: 5px;
  }
  .app-content {
    padding: 28px 20px 36px;
  }
}
@media (max-width: 380px) {
  .account-avatar {
    display: none;
  }
  .app-content {
    padding-inline: 16px;
  }
  .app-header {
    padding-inline: 16px;
  }
}
</style>
