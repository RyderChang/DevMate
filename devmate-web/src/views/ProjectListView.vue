<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { deleteProject, listProjects } from '@/api/projects'
import type { Project } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import {
  formatProjectDate,
  getProjectErrorMessage,
  normalizeProjectPagination,
  PROJECT_PAGE_SIZES,
} from '@/utils/projects'

const route = useRoute()
const router = useRouter()
const projects = ref<Project[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const loaded = ref(false)
const errorMessage = ref('')
const deletingProjectId = ref<number | null>(null)
const deleting = ref(false)
let requestVersion = 0
let active = true

onUnmounted(() => {
  active = false
  requestVersion += 1
})

async function load(): Promise<void> {
  const pagination = normalizeProjectPagination(route.query.page, route.query.pageSize)
  if (!pagination.canonical) {
    await router.replace({
      name: 'project-list',
      query: {
        ...route.query,
        page: String(pagination.page),
        pageSize: String(pagination.pageSize),
      },
    })
    return
  }

  page.value = pagination.page
  pageSize.value = pagination.pageSize
  const version = ++requestVersion
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listProjects({ page: pagination.page, pageSize: pagination.pageSize })
    if (active && version === requestVersion) {
      projects.value = result.items
      page.value = result.page
      pageSize.value = result.pageSize
      total.value = result.total
      loaded.value = true
    }
  } catch (error) {
    if (active && version === requestVersion) {
      errorMessage.value = getProjectErrorMessage(error, '加载项目列表失败，请稍后重试')
    }
  } finally {
    if (active && version === requestVersion) {
      loading.value = false
    }
  }
}

watch(() => [route.query.page, route.query.pageSize], load, { immediate: true })

function changePage(nextPage: number): void {
  void router.push({
    name: 'project-list',
    query: { ...route.query, page: String(nextPage), pageSize: String(pageSize.value) },
  })
}

function changePageSize(nextPageSize: number): void {
  void router.push({
    name: 'project-list',
    query: { ...route.query, page: '1', pageSize: String(nextPageSize) },
  })
}

async function remove(project: Project): Promise<void> {
  if (deletingProjectId.value !== null) {
    return
  }
  const version = requestVersion
  deletingProjectId.value = project.id
  try {
    await ElMessageBox.confirm(
      `确定删除项目“${project.name}”吗？删除后将无法在项目空间中访问。`,
      '删除项目',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    deletingProjectId.value = null
    return
  }

  if (!active || version !== requestVersion) {
    deletingProjectId.value = null
    return
  }

  errorMessage.value = ''
  deleting.value = true
  try {
    await deleteProject(project.id)
    if (!active || version !== requestVersion) {
      return
    }
    ElMessage.success('项目已删除')
    if (projects.value.length === 1 && page.value > 1) {
      changePage(page.value - 1)
    } else {
      await load()
    }
  } catch (error) {
    if (active && version === requestVersion) {
      errorMessage.value = getProjectErrorMessage(error, '删除项目失败，请稍后重试')
    }
  } finally {
    if (active) {
      deletingProjectId.value = null
      deleting.value = false
    }
  }
}
</script>

<template>
  <section class="project-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">项目空间</p>
        <h1>我的项目</h1>
        <p>归拢项目上下文，继续你的下一次构建。</p>
      </div>
      <el-button type="primary" @click="router.push({ name: 'project-create' })"
        ><AppIcon name="plus" />创建项目</el-button
      >
    </div>

    <el-alert
      v-if="errorMessage && loaded"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button link type="primary" @click="load">重试</el-button>
      </template>
    </el-alert>

    <el-skeleton v-if="loading && !loaded" :rows="7" animated />
    <el-result
      v-else-if="errorMessage && !loaded"
      icon="error"
      title="无法加载项目"
      :sub-title="errorMessage"
    >
      <template #extra>
        <el-button type="primary" @click="load">重试</el-button>
      </template>
    </el-result>
    <el-empty v-else-if="loaded && projects.length === 0" description="还没有项目">
      <el-button type="primary" @click="router.push({ name: 'project-create' })"
        >创建第一个项目</el-button
      >
    </el-empty>
    <template v-else-if="loaded">
      <div class="collection-heading">
        <h2 class="collection-title">
          全部项目 <span class="collection-count">{{ total }}</span>
        </h2>
        <span class="collection-order">按最近更新时间排序</span>
      </div>
      <div class="project-grid" :aria-busy="loading">
        <el-card v-for="project in projects" :key="project.id" class="project-card" shadow="never">
          <div class="project-card-content">
            <div class="project-card-top">
              <span class="project-folder"><AppIcon name="folder" /></span
              ><span class="project-kind">项目空间</span>
            </div>
            <h2>{{ project.name }}</h2>
            <p class="project-summary">{{ project.description || '暂无描述' }}</p>
            <p class="project-time">
              <AppIcon name="clock" />更新于 {{ formatProjectDate(project.updateTime) }}
            </p>
          </div>
          <div class="project-card-actions">
            <el-button
              link
              type="primary"
              @click="router.push({ name: 'project-detail', params: { projectId: project.id } })"
            >
              查看<AppIcon name="arrow" />
            </el-button>
            <el-button
              link
              type="primary"
              @click="router.push({ name: 'project-edit', params: { projectId: project.id } })"
            >
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              :loading="deleting && deletingProjectId === project.id"
              :disabled="deletingProjectId !== null && deletingProjectId !== project.id"
              :aria-label="`删除项目 ${project.name}`"
              @click="remove(project)"
            >
              删除
            </el-button>
          </div>
        </el-card>
      </div>

      <div class="project-pagination">
        <span>共 {{ total }} 个项目</span>
        <el-pagination
          background
          layout="sizes, prev, pager, next"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[...PROJECT_PAGE_SIZES]"
          :total="total"
          @update:current-page="changePage"
          @update:page-size="changePageSize"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 290px), 1fr));
  gap: 20px;
}
.project-card {
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}
.project-card:hover {
  border-color: #c6d1e8;
  box-shadow: 0 6px 20px #23334d06;
}
.project-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 274px;
  padding: 24px;
}
.project-card-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.project-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}
.project-folder {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1px solid #e1e7f6;
  border-radius: 10px;
  background: #f0f4fe;
  color: #5777cc;
  font-size: 19px;
}
.project-kind {
  color: var(--dm-muted);
  font-size: 10px;
  letter-spacing: 0.08em;
}
.project-card h2 {
  margin: 0 0 12px;
  overflow-wrap: anywhere;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.5;
}
.project-summary {
  display: -webkit-box;
  min-height: 44px;
  margin: 0;
  overflow: hidden;
  color: var(--dm-secondary);
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}
.project-time {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: auto 0 0;
  padding-top: 24px;
  color: var(--dm-muted);
  font-size: 11px;
}
.project-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  justify-content: flex-end;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--dm-border);
}
.project-card-actions .el-button {
  min-height: 26px;
  font-size: 12px;
}
.project-card-actions .el-button:first-child {
  margin-right: auto;
}
.project-card-actions .el-button:nth-child(2) {
  color: var(--dm-secondary);
}
</style>
