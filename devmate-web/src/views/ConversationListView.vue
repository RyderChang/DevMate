<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { createConversation, listConversations } from '@/api/conversations'
import { getProject } from '@/api/projects'
import type { Conversation, Project } from '@/api/types'
import {
  codePointLength,
  CONVERSATION_PAGE_SIZES,
  getConversationErrorMessage,
  isConversationUnavailableError,
  MAX_CONVERSATION_TITLE_LENGTH,
  normalizeConversationPagination,
  parsePositiveSafeId,
} from '@/utils/conversations'
import { formatProjectDate, PROJECT_UNAVAILABLE_MESSAGE } from '@/utils/projects'

const route = useRoute()
const router = useRouter()
const project = ref<Project | null>(null)
const conversations = ref<Conversation[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const loaded = ref(false)
const unavailable = ref(false)
const errorMessage = ref('')
const createVisible = ref(false)
const title = ref('')
const creating = ref(false)
const createError = ref('')
let requestVersion = 0
let active = true

const titleLength = computed(() => codePointLength(title.value.trim()))
const titleInvalid = computed(() => titleLength.value > MAX_CONVERSATION_TITLE_LENGTH)

onUnmounted(() => {
  active = false
  requestVersion += 1
})

async function load(): Promise<void> {
  if (route.name !== 'conversation-list') {
    requestVersion += 1
    return
  }
  const projectId = parsePositiveSafeId(route.params.projectId)
  const pagination = normalizeConversationPagination(route.query.page, route.query.pageSize)
  const version = ++requestVersion
  errorMessage.value = ''
  unavailable.value = projectId === null
  if (projectId === null) {
    project.value = null
    conversations.value = []
    loaded.value = false
    loading.value = false
    return
  }
  if (!pagination.canonical) {
    await router.replace({
      name: 'conversation-list',
      params: { projectId },
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
  loading.value = true
  try {
    const [projectResult, conversationResult] = await Promise.all([
      getProject(projectId),
      listConversations(projectId, { page: pagination.page, pageSize: pagination.pageSize }),
    ])
    if (active && version === requestVersion) {
      project.value = projectResult
      conversations.value = conversationResult.items
      page.value = conversationResult.page
      pageSize.value = conversationResult.pageSize
      total.value = conversationResult.total
      loaded.value = true
    }
  } catch (error) {
    if (active && version === requestVersion) {
      unavailable.value = isConversationUnavailableError(error)
      if (unavailable.value) {
        project.value = null
        conversations.value = []
      } else {
        errorMessage.value = getConversationErrorMessage(error, '加载对话列表失败，请稍后重试')
      }
    }
  } finally {
    if (active && version === requestVersion) {
      loading.value = false
    }
  }
}

watch(() => [route.name, route.params.projectId, route.query.page, route.query.pageSize], load, {
  immediate: true,
})

function changePage(nextPage: number): void {
  void router.push({
    name: 'conversation-list',
    params: { projectId: route.params.projectId },
    query: { ...route.query, page: String(nextPage), pageSize: String(pageSize.value) },
  })
}

function changePageSize(nextPageSize: number): void {
  void router.push({
    name: 'conversation-list',
    params: { projectId: route.params.projectId },
    query: { ...route.query, page: '1', pageSize: String(nextPageSize) },
  })
}

function openCreate(): void {
  title.value = ''
  createError.value = ''
  createVisible.value = true
}

function isCurrentList(projectId: number): boolean {
  return (
    active &&
    route.name === 'conversation-list' &&
    parsePositiveSafeId(route.params.projectId) === projectId
  )
}

async function submitCreate(): Promise<void> {
  const projectId = parsePositiveSafeId(route.params.projectId)
  if (projectId === null || creating.value || titleInvalid.value) {
    return
  }
  const normalizedTitle = title.value.trim()
  creating.value = true
  createError.value = ''
  try {
    const created = await createConversation(projectId, { title: normalizedTitle || null })
    if (!isCurrentList(projectId)) {
      return
    }
    if (!Number.isSafeInteger(created.id) || created.id <= 0 || created.projectId !== projectId) {
      createError.value = '服务端返回了无效的对话信息，请刷新后重试'
      return
    }
    createVisible.value = false
    await router.push({
      name: 'conversation-chat',
      params: { projectId, conversationId: created.id },
    })
  } catch (error) {
    if (isCurrentList(projectId)) {
      if (isConversationUnavailableError(error)) {
        createVisible.value = false
        unavailable.value = true
      } else {
        createError.value = getConversationErrorMessage(error, '创建对话失败，请稍后重试')
      }
    }
  } finally {
    if (active) {
      creating.value = false
    }
  }
}
</script>

<template>
  <section class="project-page conversation-list-page">
    <el-result v-if="unavailable" icon="warning" :title="PROJECT_UNAVAILABLE_MESSAGE">
      <template #extra>
        <el-button @click="router.push({ name: 'project-list' })">返回项目列表</el-button>
      </template>
    </el-result>
    <template v-else>
      <div class="page-heading">
        <div>
          <p class="eyebrow">项目对话</p>
          <h1>{{ project?.name || '加载项目中…' }}</h1>
          <p>创建对话并围绕当前项目进行同步 AI 交流。</p>
        </div>
        <div class="page-actions">
          <el-button
            :disabled="!project"
            @click="router.push({ name: 'project-detail', params: { projectId: project?.id } })"
          >
            返回项目
          </el-button>
          <el-button type="primary" :disabled="!project" @click="openCreate">创建对话</el-button>
        </div>
      </div>

      <el-alert
        v-if="errorMessage && loaded"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default><el-button link type="primary" @click="load">重试</el-button></template>
      </el-alert>

      <el-skeleton v-if="loading && !loaded" :rows="7" animated />
      <el-result
        v-else-if="errorMessage && !loaded"
        icon="error"
        title="无法加载对话"
        :sub-title="errorMessage"
      >
        <template #extra><el-button type="primary" @click="load">重试</el-button></template>
      </el-result>
      <el-empty v-else-if="loaded && conversations.length === 0" description="还没有对话">
        <el-button type="primary" @click="openCreate">创建第一个对话</el-button>
      </el-empty>
      <template v-else-if="loaded">
        <div class="conversation-grid" :aria-busy="loading">
          <el-card
            v-for="conversation in conversations"
            :key="conversation.id"
            class="conversation-card"
            shadow="hover"
          >
            <div class="conversation-card-heading">
              <h2>{{ conversation.title }}</h2>
              <el-tag :type="conversation.generationState === 'GENERATING' ? 'warning' : 'success'">
                {{ conversation.generationState === 'GENERATING' ? '正在生成' : '空闲' }}
              </el-tag>
            </div>
            <p>创建于 {{ formatProjectDate(conversation.createdAt) }}</p>
            <p>更新于 {{ formatProjectDate(conversation.updatedAt) }}</p>
            <el-button
              link
              type="primary"
              @click="
                router.push({
                  name: 'conversation-chat',
                  params: { projectId: conversation.projectId, conversationId: conversation.id },
                })
              "
            >
              进入对话
            </el-button>
          </el-card>
        </div>
        <div class="project-pagination">
          <span>共 {{ total }} 个对话</span>
          <el-pagination
            background
            layout="sizes, prev, pager, next"
            :current-page="page"
            :page-size="pageSize"
            :page-sizes="[...CONVERSATION_PAGE_SIZES]"
            :total="total"
            @update:current-page="changePage"
            @update:page-size="changePageSize"
          />
        </div>
      </template>
    </template>

    <el-dialog
      v-model="createVisible"
      title="创建项目对话"
      width="min(92vw, 520px)"
      :teleported="false"
    >
      <el-form label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="对话标题（可选）">
          <el-input v-model="title" placeholder="留空将使用默认标题" @keyup.enter="submitCreate" />
          <span class="character-count">{{ titleLength }}/{{ MAX_CONVERSATION_TITLE_LENGTH }}</span>
          <span v-if="titleInvalid" class="title-error" role="alert">标题不能超过 200 个字符</span>
        </el-form-item>
        <el-alert
          v-if="createError"
          :title="createError"
          type="error"
          :closable="false"
          show-icon
        />
      </el-form>
      <template #footer>
        <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="creating"
          :disabled="titleInvalid"
          @click="submitCreate"
        >
          创建并进入
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.conversation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
  gap: 18px;
}

.conversation-card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.conversation-card h2 {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 19px;
}

.conversation-card p {
  margin: 14px 0 0;
  color: #909399;
  font-size: 13px;
}

.conversation-card .el-button {
  float: right;
  margin-top: 12px;
}

.project-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 24px;
  color: #606266;
}

.character-count {
  display: block;
  width: 100%;
  color: #909399;
  text-align: right;
}

.title-error {
  color: #f56c6c;
  font-size: 12px;
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
