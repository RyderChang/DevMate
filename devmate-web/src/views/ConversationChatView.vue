<script setup lang="ts">
import axios from 'axios'
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  getConversation,
  listConversationMessages,
  sendConversationMessage,
} from '@/api/conversations'
import { getProject } from '@/api/projects'
import type { Conversation, ConversationMessage, Project, SendMessageResponse } from '@/api/types'
import {
  codePointLength,
  CONVERSATION_UNAVAILABLE_MESSAGE,
  getConversationErrorMessage,
  getSendErrorMessage,
  isConversationUnavailableError,
  isUncertainSendError,
  MAX_MESSAGE_LENGTH,
  parsePositiveSafeId,
} from '@/utils/conversations'
import { formatProjectDate } from '@/utils/projects'

const MESSAGE_PAGE_SIZE = 50
const SEND_ATTEMPT_LIMIT = 3

const route = useRoute()
const router = useRouter()
const project = ref<Project | null>(null)
const conversation = ref<Conversation | null>(null)
const messages = ref<ConversationMessage[]>([])
const loading = ref(false)
const loaded = ref(false)
const unavailable = ref(false)
const errorMessage = ref('')
const historyLoading = ref(false)
const historyError = ref('')
const historyRetry = ref<'reload' | 'earlier' | null>(null)
const earliestPage = ref(1)
const totalMessages = ref(0)
const draft = ref('')
const sending = ref(false)
const sendError = ref('')
const pendingRequestId = ref<string | null>(null)
const pendingContent = ref<string | null>(null)
const pendingAttempts = ref(0)
const recoveryMode = ref<'uncertain' | 'conflict' | null>(null)
let requestVersion = 0
let active = true
let currentRouteKey = ''

const draftLength = computed(() => codePointLength(draft.value))
const normalizedDraft = computed(() => draft.value.trim())
const draftInvalid = computed(
  () =>
    normalizedDraft.value.length === 0 ||
    codePointLength(normalizedDraft.value) > MAX_MESSAGE_LENGTH,
)
const hasEarlierMessages = computed(() => earliestPage.value > 1)
const serverGenerating = computed(() => conversation.value?.generationState === 'GENERATING')
const composerLocked = computed(
  () => sending.value || serverGenerating.value || pendingRequestId.value !== null,
)

onUnmounted(() => {
  active = false
  requestVersion += 1
})

function mergeMessages(incoming: ConversationMessage[]): ConversationMessage[] {
  const byId = new Set<number>()
  const bySequence = new Set<number>()
  return [...messages.value, ...incoming]
    .sort((left, right) => left.sequenceNo - right.sequenceNo || left.id - right.id)
    .filter((message) => {
      if (byId.has(message.id) || bySequence.has(message.sequenceNo)) {
        return false
      }
      byId.add(message.id)
      bySequence.add(message.sequenceNo)
      return true
    })
}

async function loadHistory(
  projectId: number,
  conversationId: number,
  version: number,
): Promise<void> {
  const existingMessages = messages.value
  historyLoading.value = true
  historyError.value = ''
  historyRetry.value = null
  try {
    const first = await listConversationMessages(projectId, conversationId, {
      page: 1,
      pageSize: MESSAGE_PAGE_SIZE,
    })
    if (!active || version !== requestVersion) {
      return
    }
    totalMessages.value = first.total
    const lastPage = Math.max(1, Math.ceil(first.total / MESSAGE_PAGE_SIZE))
    if (lastPage === 1) {
      messages.value = []
      messages.value = mergeMessages(first.items)
      earliestPage.value = 1
      return
    }
    try {
      const latest = await listConversationMessages(projectId, conversationId, {
        page: lastPage,
        pageSize: MESSAGE_PAGE_SIZE,
      })
      if (active && version === requestVersion) {
        messages.value = []
        messages.value = mergeMessages(latest.items)
        earliestPage.value = lastPage
      }
    } catch (error) {
      if (active && version === requestVersion) {
        if (existingMessages.length === 0) {
          messages.value = []
          messages.value = mergeMessages(first.items)
          earliestPage.value = 1
        }
        unavailable.value = isConversationUnavailableError(error)
        historyError.value = getConversationErrorMessage(
          error,
          '最新消息加载失败，已保留成功加载的历史消息',
        )
        historyRetry.value = 'reload'
      }
    }
  } catch (error) {
    if (active && version === requestVersion) {
      unavailable.value = isConversationUnavailableError(error)
      historyError.value = getConversationErrorMessage(error, '加载消息历史失败，请稍后重试')
      historyRetry.value = 'reload'
    }
  } finally {
    if (active && version === requestVersion) {
      historyLoading.value = false
    }
  }
}

async function load(): Promise<void> {
  const projectId = parsePositiveSafeId(route.params.projectId)
  const conversationId = parsePositiveSafeId(route.params.conversationId)
  const routeKey = `${String(route.params.projectId)}:${String(route.params.conversationId)}`
  const routeChanged = routeKey !== currentRouteKey
  if (routeChanged) {
    currentRouteKey = routeKey
    draft.value = ''
    pendingRequestId.value = null
    pendingContent.value = null
    pendingAttempts.value = 0
    recoveryMode.value = null
    sendError.value = ''
    sending.value = false
    project.value = null
    conversation.value = null
    messages.value = []
    loaded.value = false
  }
  const version = ++requestVersion
  errorMessage.value = ''
  historyError.value = ''
  unavailable.value = projectId === null || conversationId === null
  if (projectId === null || conversationId === null) {
    loading.value = false
    return
  }

  loading.value = true
  try {
    const [projectResult, conversationResult] = await Promise.all([
      getProject(projectId),
      getConversation(projectId, conversationId),
    ])
    if (!active || version !== requestVersion) {
      return
    }
    if (conversationResult.projectId !== projectId || conversationResult.id !== conversationId) {
      throw new Error('Invalid conversation response')
    }
    project.value = projectResult
    conversation.value = conversationResult
    loaded.value = true
    await loadHistory(projectId, conversationId, version)
  } catch (error) {
    if (active && version === requestVersion) {
      unavailable.value = isConversationUnavailableError(error)
      if (!unavailable.value) {
        errorMessage.value = getConversationErrorMessage(error, '加载对话失败，请稍后重试')
      }
    }
  } finally {
    if (active && version === requestVersion) {
      loading.value = false
    }
  }
}

watch(() => [route.params.projectId, route.params.conversationId], load, { immediate: true })

async function loadEarlier(): Promise<void> {
  const projectId = parsePositiveSafeId(route.params.projectId)
  const conversationId = parsePositiveSafeId(route.params.conversationId)
  if (
    projectId === null ||
    conversationId === null ||
    historyLoading.value ||
    earliestPage.value <= 1
  ) {
    return
  }
  const version = requestVersion
  const targetPage = earliestPage.value - 1
  historyLoading.value = true
  historyError.value = ''
  historyRetry.value = null
  try {
    const result = await listConversationMessages(projectId, conversationId, {
      page: targetPage,
      pageSize: MESSAGE_PAGE_SIZE,
    })
    if (active && version === requestVersion) {
      messages.value = mergeMessages(result.items)
      earliestPage.value = targetPage
      totalMessages.value = result.total
    }
  } catch (error) {
    if (active && version === requestVersion) {
      unavailable.value = isConversationUnavailableError(error)
      historyError.value = getConversationErrorMessage(error, '加载更早消息失败，请稍后重试')
      historyRetry.value = 'earlier'
    }
  } finally {
    if (active && version === requestVersion) {
      historyLoading.value = false
    }
  }
}

function retryHistory(): void {
  if (historyRetry.value === 'earlier') {
    void loadEarlier()
    return
  }
  void load()
}

function validMessage(message: ConversationMessage): boolean {
  return (
    Number.isSafeInteger(message.id) &&
    message.id > 0 &&
    Number.isSafeInteger(message.sequenceNo) &&
    message.sequenceNo > 0 &&
    (message.role === 'USER' || message.role === 'ASSISTANT') &&
    typeof message.content === 'string'
  )
}

function validSendResponse(response: SendMessageResponse, conversationId: number): boolean {
  return (
    response.conversationId === conversationId &&
    validMessage(response.userMessage) &&
    validMessage(response.assistantMessage)
  )
}

function isCurrentConversation(projectId: number, conversationId: number): boolean {
  return (
    active &&
    parsePositiveSafeId(route.params.projectId) === projectId &&
    parsePositiveSafeId(route.params.conversationId) === conversationId
  )
}

async function performSend(clientRequestId: string, content: string): Promise<void> {
  const projectId = parsePositiveSafeId(route.params.projectId)
  const conversationId = parsePositiveSafeId(route.params.conversationId)
  if (projectId === null || conversationId === null || sending.value) {
    return
  }
  sending.value = true
  sendError.value = ''
  try {
    const response = await sendConversationMessage(projectId, conversationId, {
      clientRequestId,
      content,
    })
    if (!isCurrentConversation(projectId, conversationId)) {
      return
    }
    if (!validSendResponse(response, conversationId)) {
      throw new Error('Invalid send response')
    }
    messages.value = mergeMessages([response.userMessage, response.assistantMessage])
    totalMessages.value = Math.max(totalMessages.value, messages.value.length)
    draft.value = ''
    pendingRequestId.value = null
    pendingContent.value = null
    pendingAttempts.value = 0
    recoveryMode.value = null
    if (conversation.value) {
      conversation.value = { ...conversation.value, generationState: 'IDLE' }
    }
  } catch (error) {
    if (!isCurrentConversation(projectId, conversationId)) {
      return
    }
    sendError.value = getSendErrorMessage(error)
    if (axios.isAxiosError(error) && error.response?.status === 404) {
      unavailable.value = true
    }
    if (axios.isAxiosError(error) && error.response?.status === 409) {
      recoveryMode.value = 'conflict'
      if (conversation.value) {
        conversation.value = { ...conversation.value, generationState: 'GENERATING' }
      }
    } else if (isUncertainSendError(error)) {
      recoveryMode.value = 'uncertain'
    } else {
      pendingRequestId.value = null
      pendingContent.value = null
      pendingAttempts.value = 0
      recoveryMode.value = null
      if (conversation.value) {
        conversation.value = { ...conversation.value, generationState: 'IDLE' }
      }
    }
  } finally {
    if (isCurrentConversation(projectId, conversationId)) {
      sending.value = false
    }
  }
}

function submit(): void {
  if (composerLocked.value || draftInvalid.value) {
    return
  }
  const content = normalizedDraft.value
  draft.value = content
  const clientRequestId = crypto.randomUUID()
  pendingRequestId.value = clientRequestId
  pendingContent.value = content
  pendingAttempts.value = 1
  recoveryMode.value = null
  void performSend(clientRequestId, content)
}

function retryPending(): void {
  if (!pendingRequestId.value || !pendingContent.value || sending.value) {
    return
  }
  if (pendingAttempts.value >= SEND_ATTEMPT_LIMIT) {
    sendError.value = '已达到手动确认上限，请刷新页面核对服务端状态'
    return
  }
  pendingAttempts.value += 1
  void performSend(pendingRequestId.value, pendingContent.value)
}
</script>

<template>
  <section class="project-page conversation-chat-page">
    <el-result v-if="unavailable" icon="warning" :title="CONVERSATION_UNAVAILABLE_MESSAGE">
      <template #extra>
        <el-button
          @click="
            router.push({
              name: 'conversation-list',
              params: { projectId: route.params.projectId },
            })
          "
        >
          返回项目对话
        </el-button>
      </template>
    </el-result>
    <el-skeleton v-else-if="loading && !loaded" :rows="8" animated />
    <el-result
      v-else-if="errorMessage && !loaded"
      icon="error"
      title="无法加载对话"
      :sub-title="errorMessage"
    >
      <template #extra>
        <el-button type="primary" @click="load">重试</el-button>
      </template>
    </el-result>
    <template v-else-if="conversation && project">
      <div class="page-heading conversation-heading">
        <div>
          <p class="eyebrow">{{ project.name }} · 项目对话</p>
          <h1>{{ conversation.title }}</h1>
          <el-tag :type="serverGenerating ? 'warning' : 'success'">
            {{ serverGenerating ? '正在生成' : '空闲' }}
          </el-tag>
        </div>
        <div class="page-actions">
          <el-button
            @click="router.push({ name: 'conversation-list', params: { projectId: project.id } })"
          >
            对话列表
          </el-button>
          <el-button
            @click="router.push({ name: 'project-detail', params: { projectId: project.id } })"
          >
            项目详情
          </el-button>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <el-alert
        v-if="serverGenerating && !pendingRequestId"
        title="服务端仍在生成回复，请稍后刷新查看结果"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" show-icon>
        <template #default>
          <el-button link type="primary" @click="retryHistory"> 重试 </el-button>
        </template>
      </el-alert>

      <el-card class="message-panel" shadow="never" :aria-busy="historyLoading">
        <div v-if="hasEarlierMessages" class="load-earlier">
          <el-button :loading="historyLoading" @click="loadEarlier">加载更早消息</el-button>
        </div>
        <el-skeleton v-if="historyLoading && messages.length === 0" :rows="5" animated />
        <el-empty v-else-if="messages.length === 0" description="还没有消息，开始第一次交流吧" />
        <ol v-else class="message-list" aria-label="对话消息">
          <li
            v-for="message in messages"
            :key="message.id"
            class="message-item"
            :class="message.role === 'USER' ? 'message-user' : 'message-assistant'"
          >
            <div class="message-meta">
              <strong>{{ message.role === 'USER' ? '你' : 'DevMate' }}</strong>
              <time>{{ formatProjectDate(message.createdAt) }}</time>
            </div>
            <p>{{ message.content }}</p>
          </li>
        </ol>
      </el-card>

      <el-card class="composer-card" shadow="never">
        <el-alert
          v-if="sendError"
          :title="sendError"
          :type="recoveryMode ? 'warning' : 'error'"
          :closable="false"
          show-icon
        >
          <template v-if="recoveryMode" #default>
            <el-button link type="primary" :loading="sending" @click="retryPending">
              重试确认结果
            </el-button>
          </template>
        </el-alert>
        <el-input
          v-model="draft"
          type="textarea"
          :rows="5"
          resize="vertical"
          placeholder="输入消息；内容只会以纯文本展示"
          :disabled="composerLocked"
          @keydown.ctrl.enter.prevent="submit"
          @keydown.meta.enter.prevent="submit"
        />
        <div class="composer-actions">
          <span :class="{ 'character-count-error': draftLength > MAX_MESSAGE_LENGTH }">
            {{ draftLength }}/{{ MAX_MESSAGE_LENGTH }} 字符
          </span>
          <el-button
            type="primary"
            :loading="sending"
            :disabled="composerLocked || draftInvalid"
            @click="submit"
          >
            {{ sending ? '正在生成' : '发送消息' }}
          </el-button>
        </div>
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.conversation-chat-page {
  width: min(100%, 920px);
}

.conversation-chat-page > .el-alert {
  margin-bottom: 16px;
}

.conversation-heading h1 {
  margin-bottom: 12px;
}

.message-panel {
  margin-bottom: 20px;
}

.message-panel :deep(.el-card__body) {
  padding: clamp(12px, 3vw, 24px);
}

.load-earlier {
  margin-bottom: 18px;
  text-align: center;
}

.message-list {
  display: grid;
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.message-item {
  width: min(86%, 720px);
  padding: 14px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 12px;
  background: #fff;
}

.message-user {
  justify-self: end;
  border-color: #b3d8ff;
  background: #ecf5ff;
}

.message-assistant {
  justify-self: start;
}

.message-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  color: #909399;
  font-size: 12px;
}

.message-meta strong {
  color: #303133;
}

.message-item p {
  margin: 10px 0 0;
  overflow-wrap: anywhere;
  line-height: 1.65;
  white-space: pre-wrap;
}

.composer-card {
  position: sticky;
  bottom: 12px;
  background: rgb(255 255 255 / 96%);
}

.composer-card .el-alert {
  margin-bottom: 14px;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}

.character-count-error {
  color: #f56c6c;
}

@media (max-width: 480px) {
  .message-item {
    width: 94%;
    padding: 12px;
  }

  .composer-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .composer-actions .el-button {
    width: 100%;
    margin: 0;
  }
}
</style>
