<script setup lang="ts">
import axios from 'axios'
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  getConversation,
  listConversationMessages,
  sendRagConversationMessage,
  sendConversationMessage,
} from '@/api/conversations'
import { getProject } from '@/api/projects'
import type { Conversation, ConversationMessage, Project, SendMessageResponse } from '@/api/types'
import RagCitations from '@/components/conversation/RagCitations.vue'
import AppIcon from '@/components/AppIcon.vue'
import {
  classifyRagSendFailure,
  codePointLength,
  CONVERSATION_UNAVAILABLE_MESSAGE,
  getConversationErrorMessage,
  getSendErrorMessage,
  isConversationUnavailableError,
  isUncertainSendError,
  isValidRagContent,
  MAX_MESSAGE_LENGTH,
  parsePositiveSafeId,
} from '@/utils/conversations'
import { formatProjectDate } from '@/utils/projects'

const MESSAGE_PAGE_SIZE = 50
const SEND_ATTEMPT_LIMIT = 3
const ragEnabled = import.meta.env.VITE_RAG_ENABLED === 'true'

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
const mode = ref<'chat' | 'rag'>('chat')
const sending = ref(false)
const sendError = ref('')
const pendingRequestId = ref<string | null>(null)
const pendingContent = ref<string | null>(null)
const pendingMode = ref<'chat' | 'rag' | null>(null)
const pendingAttempts = ref(0)
const recoveryMode = ref<'uncertain' | 'conflict' | null>(null)
let requestVersion = 0
let active = true
let currentRouteKey = ''
let routeEpoch = 0

const draftLength = computed(() =>
  mode.value === 'rag' ? draft.value.length : codePointLength(draft.value),
)
const normalizedDraft = computed(() => draft.value.trim())
const draftInvalid = computed(() =>
  mode.value === 'rag'
    ? !isValidRagContent(normalizedDraft.value)
    : normalizedDraft.value.length === 0 ||
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
    routeEpoch += 1
    draft.value = ''
    mode.value = 'chat'
    pendingRequestId.value = null
    pendingContent.value = null
    pendingMode.value = null
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

function isCurrentConversation(projectId: number, conversationId: number, epoch: number): boolean {
  return (
    active &&
    routeEpoch === epoch &&
    parsePositiveSafeId(route.params.projectId) === projectId &&
    parsePositiveSafeId(route.params.conversationId) === conversationId
  )
}

async function performSend(
  clientRequestId: string,
  content: string,
  requestMode: 'chat' | 'rag',
): Promise<void> {
  const projectId = parsePositiveSafeId(route.params.projectId)
  const conversationId = parsePositiveSafeId(route.params.conversationId)
  if (projectId === null || conversationId === null || sending.value) {
    return
  }
  const epoch = routeEpoch
  sending.value = true
  sendError.value = ''
  try {
    const request = { clientRequestId, content }
    const answer =
      requestMode === 'rag'
        ? {
            mode: 'rag' as const,
            response: await sendRagConversationMessage(projectId, conversationId, request),
          }
        : {
            mode: 'chat' as const,
            response: await sendConversationMessage(projectId, conversationId, request),
          }
    const response = answer.response
    if (!isCurrentConversation(projectId, conversationId, epoch)) {
      return
    }
    if (!validSendResponse(response, conversationId)) {
      throw new Error('Invalid send response')
    }
    const assistant =
      answer.mode === 'rag'
        ? {
            ...answer.response.assistantMessage,
            evidence: { rag: answer.response.rag, citations: answer.response.citations },
          }
        : response.assistantMessage
    messages.value = mergeMessages([response.userMessage, assistant])
    totalMessages.value = Math.max(totalMessages.value, messages.value.length)
    draft.value = ''
    pendingRequestId.value = null
    pendingContent.value = null
    pendingMode.value = null
    pendingAttempts.value = 0
    recoveryMode.value = null
    if (conversation.value) {
      conversation.value = { ...conversation.value, generationState: 'IDLE' }
    }
  } catch (error) {
    if (!isCurrentConversation(projectId, conversationId, epoch)) {
      return
    }
    if (requestMode === 'rag') {
      const failure = classifyRagSendFailure(error)
      sendError.value = failure.message
      unavailable.value = failure.unavailable
      if (failure.uncertain) {
        recoveryMode.value = failure.inProgress ? 'conflict' : 'uncertain'
        if (failure.inProgress && conversation.value) {
          conversation.value = { ...conversation.value, generationState: 'GENERATING' }
        }
      } else {
        pendingRequestId.value = null
        pendingContent.value = null
        pendingMode.value = null
        pendingAttempts.value = 0
        recoveryMode.value = null
        if (conversation.value) {
          conversation.value = { ...conversation.value, generationState: 'IDLE' }
        }
      }
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
      pendingMode.value = null
      pendingAttempts.value = 0
      recoveryMode.value = null
      if (conversation.value) {
        conversation.value = { ...conversation.value, generationState: 'IDLE' }
      }
    }
  } finally {
    if (isCurrentConversation(projectId, conversationId, epoch)) {
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
  pendingMode.value = mode.value
  pendingAttempts.value = 1
  recoveryMode.value = null
  void performSend(clientRequestId, content, mode.value)
}

function retryPending(): void {
  if (!pendingRequestId.value || !pendingContent.value || !pendingMode.value || sending.value) {
    return
  }
  if (pendingAttempts.value >= SEND_ATTEMPT_LIMIT) {
    sendError.value = '已达到手动确认上限，请刷新页面核对服务端状态'
    return
  }
  pendingAttempts.value += 1
  void performSend(pendingRequestId.value, pendingContent.value, pendingMode.value)
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

      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon>
        <template #default>
          <el-button link type="primary" @click="load">重试</el-button>
        </template>
      </el-alert>
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
              <span class="message-author"
                ><span class="message-avatar" aria-hidden="true"
                  ><AppIcon v-if="message.role === 'ASSISTANT'" name="spark" /><span v-else
                    >你</span
                  ></span
                ><strong>{{ message.role === 'USER' ? '你' : 'DevMate' }}</strong></span
              >
              <time>{{ formatProjectDate(message.createdAt) }}</time>
            </div>
            <p>{{ message.content }}</p>
            <RagCitations v-if="message.evidence" :evidence="message.evidence" />
          </li>
        </ol>
      </el-card>

      <el-card class="composer-card" shadow="never">
        <div v-if="ragEnabled" class="mode-choice" role="group" aria-label="对话模式">
          <el-radio-group v-model="mode" :disabled="composerLocked">
            <el-radio-button value="chat">普通聊天</el-radio-button>
            <el-radio-button value="rag">文档问答</el-radio-button>
          </el-radio-group>
          <span v-if="mode === 'rag'">使用当前项目的活动文档；回答会显示可核验的引用。</span>
        </div>
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
          :rows="4"
          resize="vertical"
          placeholder="描述你想推进的问题…"
          aria-label="消息内容"
          :disabled="composerLocked"
          @keydown.ctrl.enter.prevent="submit"
          @keydown.meta.enter.prevent="submit"
        />
        <div class="composer-actions">
          <span class="composer-shortcut">Ctrl / ⌘ + Enter 发送</span>
          <span :class="{ 'character-count-error': draftLength > MAX_MESSAGE_LENGTH }">
            {{ draftLength }}/{{ MAX_MESSAGE_LENGTH }} {{ mode === 'rag' ? 'UTF-16 码元' : '字符' }}
          </span>
          <el-button
            type="primary"
            :loading="sending"
            :disabled="composerLocked || draftInvalid"
            @click="submit"
          >
            {{ sending ? '正在生成' : '发送消息' }}
            <AppIcon name="arrow" />
          </el-button>
        </div>
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.conversation-chat-page {
  width: min(100%, 960px);
}
.conversation-chat-page > .el-alert {
  margin-bottom: 16px;
}
.conversation-heading {
  padding-bottom: 24px;
  border-bottom: 1px solid var(--dm-border);
}
.conversation-heading h1 {
  margin-bottom: 14px;
  font-size: clamp(23px, 2.1vw, 28px);
}
.conversation-heading .page-actions {
  padding-top: 20px;
}
.message-panel {
  margin-bottom: 28px;
  border: 0;
  background: transparent;
}
.message-panel :deep(.el-card__body) {
  padding: 8px 0 24px;
}
.load-earlier {
  margin-bottom: 28px;
  text-align: center;
}
.message-list {
  display: grid;
  gap: 30px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.message-item {
  width: 100%;
  min-width: 0;
  padding: 0;
}
.message-user {
  justify-self: end;
  width: fit-content;
  max-width: 86%;
  padding: 16px 20px;
  border: 1px solid #dfe6f3;
  border-radius: 14px 14px 3px 14px;
  background: #edf2fb;
}
.message-assistant {
  justify-self: start;
}
.message-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  color: var(--dm-muted);
  font-size: 11px;
}
.message-author {
  display: flex;
  align-items: center;
  gap: 10px;
}
.message-meta strong {
  color: var(--dm-text);
  font-size: 12px;
  font-weight: 600;
}
.message-avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #fff;
  background: var(--dm-accent);
  font-size: 15px;
}
.message-user .message-avatar {
  width: 24px;
  height: 24px;
  border: 1px solid #d0daee;
  color: #526c9e;
  background: #f7f9fd;
  font-size: 10px;
}
.message-user .message-meta {
  justify-content: space-between;
}
.message-item p {
  margin: 12px 0 0;
  overflow-wrap: anywhere;
  line-height: 1.9;
  white-space: pre-wrap;
}
.message-assistant > p {
  margin-left: 40px;
}
.message-assistant > :deep(.rag-evidence) {
  margin-left: 40px;
}
.composer-card {
  position: sticky;
  bottom: 16px;
  z-index: 1;
  border-color: #d8dfed;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 6px 30px #23334d09;
}
.composer-card :deep(.el-card__body) {
  padding: 18px 20px 14px;
}
.composer-card :deep(.el-textarea__inner) {
  padding: 4px 2px 10px;
  border-radius: 0;
  box-shadow: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.8;
}
.composer-card :deep(.el-textarea__inner:focus-visible) {
  outline-offset: 4px;
  border-radius: 4px;
}
.composer-card .el-alert {
  margin-bottom: 14px;
}
.mode-choice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  color: var(--dm-secondary);
  font-size: 12px;
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--dm-border);
  color: var(--dm-muted);
  font-size: 11px;
}
.composer-shortcut {
  margin-right: auto;
}
.character-count-error {
  color: var(--el-color-danger);
}
@media (max-width: 640px) {
  .composer-card {
    position: static;
  }
  .conversation-heading .page-actions {
    padding-top: 0;
  }
  .composer-shortcut {
    display: none;
  }
  .message-user {
    max-width: 94%;
  }
  .message-assistant > p,
  .message-assistant > :deep(.rag-evidence) {
    margin-left: 0;
  }
}
@media (max-width: 380px) {
  .composer-actions {
    gap: 10px;
    flex-wrap: wrap;
  }
  .composer-actions .el-button {
    margin-left: auto;
  }
  .message-user {
    padding: 14px;
  }
  .composer-card :deep(.el-card__body) {
    padding: 16px;
  }
}
</style>
