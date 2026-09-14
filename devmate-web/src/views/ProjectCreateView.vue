<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { createProject } from '@/api/projects'
import type { ProjectMutationRequest } from '@/api/types'
import ProjectForm from '@/components/project/ProjectForm.vue'
import { getProjectErrorMessage } from '@/utils/projects'

const router = useRouter()
const submitting = ref(false)
const errorMessage = ref('')
let active = true

onUnmounted(() => {
  active = false
})

async function submit(request: ProjectMutationRequest): Promise<void> {
  if (submitting.value) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const project = await createProject(request)
    if (active) {
      await router.replace({ name: 'project-detail', params: { projectId: project.id } })
    }
  } catch (error) {
    if (active) {
      errorMessage.value = getProjectErrorMessage(error, '创建项目失败，请稍后重试')
    }
  } finally {
    if (active) {
      submitting.value = false
    }
  }
}

function cancel(): void {
  void router.push({ name: 'project-list' })
}
</script>

<template>
  <section class="project-page narrow-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">项目空间</p>
        <h1>创建项目</h1>
        <p>建立一个独立的研发上下文。</p>
      </div>
    </div>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon />
    <el-card shadow="never">
      <ProjectForm
        submit-label="创建项目"
        :submitting="submitting"
        @submit="submit"
        @cancel="cancel"
      />
    </el-card>
  </section>
</template>
