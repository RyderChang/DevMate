<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { deleteProject, listProjects } from '@/api/projects'
import type { Project } from '@/api/types'
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
        <p>在独立项目中组织后续的代码、文档与 AI 工作流。</p>
      </div>
      <el-button type="primary" @click="router.push({ name: 'project-create' })"
        >创建项目</el-button
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
      <div class="project-grid" :aria-busy="loading">
        <el-card v-for="project in projects" :key="project.id" class="project-card" shadow="hover">
          <div class="project-card-content">
            <h2>{{ project.name }}</h2>
            <p class="project-summary">{{ project.description || '暂无描述' }}</p>
            <p class="project-time">更新于 {{ formatProjectDate(project.updateTime) }}</p>
          </div>
          <div class="project-card-actions">
            <el-button
              link
              type="primary"
              @click="router.push({ name: 'project-detail', params: { projectId: project.id } })"
            >
              查看
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
              :loading="deletingProjectId === project.id"
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
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 300px), 1fr));
  gap: 18px;
}

.project-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  min-height: 220px;
}

.project-card-content {
  flex: 1;
  min-width: 0;
}

.project-card h2 {
  margin: 0 0 12px;
  overflow-wrap: anywhere;
  font-size: 19px;
}

.project-summary {
  display: -webkit-box;
  min-height: 48px;
  margin: 0;
  overflow: hidden;
  color: #606266;
  line-height: 1.6;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.project-time {
  margin: 20px 0 0;
  color: #909399;
  font-size: 13px;
}

.project-card-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  margin-top: 18px;
}

.project-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 24px;
  color: #606266;
}

@media (max-width: 640px) {
  .project-pagination {
    align-items: flex-start;
    flex-direction: column;
  }

  .project-pagination :deep(.el-pagination) {
    flex-wrap: wrap;
  }
}
</style>
