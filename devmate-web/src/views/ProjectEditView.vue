<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getProject, updateProject } from '@/api/projects'
import type { Project, ProjectMutationRequest } from '@/api/types'
import ProjectForm from '@/components/project/ProjectForm.vue'
import {
  getProjectErrorMessage,
  isProjectUnavailableError,
  parseProjectId,
  PROJECT_UNAVAILABLE_MESSAGE,
} from '@/utils/projects'

const route = useRoute()
const router = useRouter()
const project = ref<Project | null>(null)
const loading = ref(false)
const submitting = ref(false)
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
  submitting.value = false
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

async function submit(request: ProjectMutationRequest): Promise<void> {
  const projectId = parseProjectId(route.params.projectId)
  if (projectId === null || submitting.value) {
    return
  }
  const version = requestVersion
  submitting.value = true
  errorMessage.value = ''
  try {
    await updateProject(projectId, request)
    if (active && version === requestVersion) {
      await router.replace({ name: 'project-detail', params: { projectId } })
    }
  } catch (error) {
    if (!active || version !== requestVersion) {
      return
    }
    if (isProjectUnavailableError(error)) {
      unavailable.value = true
      project.value = null
    } else {
      errorMessage.value = getProjectErrorMessage(error, '更新项目失败，请稍后重试')
    }
  } finally {
    if (active && version === requestVersion) {
      submitting.value = false
    }
  }
}

function cancel(): void {
  const projectId = parseProjectId(route.params.projectId)
  if (projectId === null) {
    void router.push({ name: 'project-list' })
  } else {
    void router.push({ name: 'project-detail', params: { projectId } })
  }
}
</script>

<template>
  <section class="project-page narrow-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">项目空间</p>
        <h1>编辑项目</h1>
        <p>保存前会使用服务端最新项目信息。</p>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-result v-else-if="unavailable" icon="warning" :title="PROJECT_UNAVAILABLE_MESSAGE">
      <template #extra>
        <el-button @click="router.push({ name: 'project-list' })">返回项目列表</el-button>
      </template>
    </el-result>
    <template v-else-if="project">
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
      <el-card shadow="never">
        <ProjectForm
          submit-label="保存修改"
          :initial-value="project"
          :submitting="submitting"
          @submit="submit"
          @cancel="cancel"
        />
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
