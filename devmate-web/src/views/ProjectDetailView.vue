<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { deleteProject, getProject } from '@/api/projects'
import type { Project } from '@/api/types'
import {
  formatProjectDate,
  getProjectErrorMessage,
  isProjectUnavailableError,
  parseProjectId,
  PROJECT_UNAVAILABLE_MESSAGE,
} from '@/utils/projects'

const route = useRoute()
const router = useRouter()
const project = ref<Project | null>(null)
const loading = ref(false)
const deleting = ref(false)
const errorMessage = ref('')
const unavailable = ref(false)
let requestVersion = 0
let active = true

onUnmounted(() => {
  active = false
  requestVersion += 1
})

async function load(): Promise<void> {
  const projectId = parseProjectId(route.params.projectId)
  const version = ++requestVersion
  project.value = null
  errorMessage.value = ''
  unavailable.value = projectId === null
  if (projectId === null) {
    loading.value = false
    return
  }

  loading.value = true
  try {
    const response = await getProject(projectId)
    if (active && version === requestVersion) {
      project.value = response
    }
  } catch (error) {
    if (active && version === requestVersion) {
      unavailable.value = isProjectUnavailableError(error)
      if (!unavailable.value) {
        errorMessage.value = getProjectErrorMessage(error, '加载项目失败，请稍后重试')
      }
    }
  } finally {
    if (active && version === requestVersion) {
      loading.value = false
    }
  }
}

watch(() => route.params.projectId, load, { immediate: true })

async function remove(): Promise<void> {
  const currentProject = project.value
  if (!currentProject || deleting.value) {
    return
  }
  deleting.value = true
  try {
    await ElMessageBox.confirm(
      `确定删除项目“${currentProject.name}”吗？删除后将无法在项目空间中访问。`,
      '删除项目',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    deleting.value = false
    return
  }

  if (!active) {
    return
  }

  errorMessage.value = ''
  try {
    await deleteProject(currentProject.id)
    if (active) {
      ElMessage.success('项目已删除')
      await router.replace({ name: 'project-list' })
    }
  } catch (error) {
    if (!active) {
      return
    }
    if (isProjectUnavailableError(error)) {
      unavailable.value = true
      project.value = null
    } else {
      errorMessage.value = getProjectErrorMessage(error, '删除项目失败，请稍后重试')
    }
  } finally {
    if (active) {
      deleting.value = false
    }
  }
}
</script>

<template>
  <section class="project-page narrow-page">
    <el-skeleton v-if="loading" :rows="6" animated />
    <el-result v-else-if="unavailable" icon="warning" :title="PROJECT_UNAVAILABLE_MESSAGE">
      <template #extra>
        <el-button @click="router.push({ name: 'project-list' })">返回项目列表</el-button>
      </template>
    </el-result>
    <template v-else-if="project">
      <div class="page-heading">
        <div>
          <p class="eyebrow">项目详情</p>
          <h1>{{ project.name }}</h1>
          <p>查看和维护项目的基本信息。</p>
        </div>
        <div class="page-actions">
          <el-button @click="router.push({ name: 'project-list' })">返回列表</el-button>
          <el-button
            type="primary"
            @click="router.push({ name: 'project-edit', params: { projectId: project.id } })"
          >
            编辑项目
          </el-button>
          <el-button type="danger" plain :loading="deleting" @click="remove">删除项目</el-button>
        </div>
      </div>
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
      <el-card class="project-detail-card" shadow="never">
        <dl>
          <div>
            <dt>项目名称</dt>
            <dd>{{ project.name }}</dd>
          </div>
          <div>
            <dt>项目描述</dt>
            <dd class="project-description">{{ project.description || '暂无描述' }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatProjectDate(project.createTime) }}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{{ formatProjectDate(project.updateTime) }}</dd>
          </div>
        </dl>
      </el-card>
    </template>
    <el-result v-else icon="error" title="无法加载项目" :sub-title="errorMessage">
      <template #extra>
        <el-button type="primary" @click="load">重试</el-button>
        <el-button @click="router.push({ name: 'project-list' })">返回列表</el-button>
      </template>
    </el-result>
  </section>
</template>

<style scoped>
.project-detail-card dl {
  display: grid;
  gap: 24px;
  margin: 0;
}

.project-detail-card dl > div {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 20px;
}

dt {
  color: #909399;
}

dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.project-description {
  white-space: pre-wrap;
}

@media (max-width: 480px) {
  .project-detail-card dl > div {
    grid-template-columns: 1fr;
    gap: 6px;
  }
}
</style>
