import http from '@/api/http'
import type {
  ApiResult,
  Conversation,
  ConversationMessage,
  PageResult,
  SendMessageResponse,
} from '@/api/types'

export interface ConversationListParams {
  page: number
  pageSize: number
}

export interface CreateConversationRequest {
  title: string | null
}

export interface SendConversationMessageRequest {
  clientRequestId: string
  content: string
}

export const CONVERSATION_SEND_TIMEOUT_MS = 130_000

function positiveSafeInteger(value: number, label: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${label} must be a positive safe integer`)
  }
  return value
}

function assertNonNegativeSafeInteger(value: number, label: string): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${label} must be a non-negative safe integer`)
  }
}

function assertString(value: string, label: string): void {
  if (typeof value !== 'string') {
    throw new TypeError(`${label} must be a string`)
  }
}

function validateConversation(value: Conversation): Conversation {
  positiveSafeInteger(value.id, 'Conversation id')
  positiveSafeInteger(value.projectId, 'Conversation project id')
  assertString(value.title, 'Conversation title')
  assertString(value.createdAt, 'Conversation createdAt')
  assertString(value.updatedAt, 'Conversation updatedAt')
  if (value.generationState !== 'IDLE' && value.generationState !== 'GENERATING') {
    throw new TypeError('Conversation generationState is invalid')
  }
  return value
}

function validateMessage(value: ConversationMessage): ConversationMessage {
  positiveSafeInteger(value.id, 'Message id')
  positiveSafeInteger(value.sequenceNo, 'Message sequence number')
  assertString(value.content, 'Message content')
  assertString(value.createdAt, 'Message createdAt')
  if (value.role !== 'USER' && value.role !== 'ASSISTANT') {
    throw new TypeError('Message role is invalid')
  }
  return value
}

function validatePage<T>(value: PageResult<T>, validateItem: (item: T) => T): PageResult<T> {
  positiveSafeInteger(value.page, 'Page number')
  positiveSafeInteger(value.pageSize, 'Page size')
  assertNonNegativeSafeInteger(value.total, 'Page total')
  if (!Array.isArray(value.items)) {
    throw new TypeError('Page items must be an array')
  }
  value.items.forEach(validateItem)
  return value
}

function validateSendResponse(value: SendMessageResponse): SendMessageResponse {
  positiveSafeInteger(value.conversationId, 'Conversation id')
  validateMessage(value.userMessage)
  validateMessage(value.assistantMessage)
  if (value.invocation.status !== 'SUCCEEDED') {
    throw new TypeError('Invocation status is invalid')
  }
  assertString(value.invocation.provider, 'Invocation provider')
  assertString(value.invocation.model, 'Invocation model')
  for (const [label, metric] of [
    ['Invocation inputTokens', value.invocation.inputTokens],
    ['Invocation outputTokens', value.invocation.outputTokens],
    ['Invocation totalTokens', value.invocation.totalTokens],
    ['Invocation durationMs', value.invocation.durationMs],
  ] as const) {
    if (metric !== null) {
      assertNonNegativeSafeInteger(metric, label)
    }
  }
  if (value.invocation.completedAt !== null) {
    assertString(value.invocation.completedAt, 'Invocation completedAt')
  }
  return value
}

function conversationPath(projectId: number, conversationId?: number): string {
  const project = positiveSafeInteger(projectId, 'Project id')
  const base = `/projects/${project}/conversations`
  return conversationId === undefined
    ? base
    : `${base}/${positiveSafeInteger(conversationId, 'Conversation id')}`
}

export async function listConversations(
  projectId: number,
  params: ConversationListParams,
): Promise<PageResult<Conversation>> {
  const response = await http.get<ApiResult<PageResult<Conversation>>>(
    conversationPath(projectId),
    {
      params,
    },
  )
  return validatePage(response.data.data, validateConversation)
}

export async function createConversation(
  projectId: number,
  request: CreateConversationRequest,
): Promise<Conversation> {
  const response = await http.post<ApiResult<Conversation>>(conversationPath(projectId), request)
  return validateConversation(response.data.data)
}

export async function getConversation(
  projectId: number,
  conversationId: number,
): Promise<Conversation> {
  const response = await http.get<ApiResult<Conversation>>(
    conversationPath(projectId, conversationId),
  )
  return validateConversation(response.data.data)
}

export async function listConversationMessages(
  projectId: number,
  conversationId: number,
  params: ConversationListParams,
): Promise<PageResult<ConversationMessage>> {
  const response = await http.get<ApiResult<PageResult<ConversationMessage>>>(
    `${conversationPath(projectId, conversationId)}/messages`,
    { params },
  )
  return validatePage(response.data.data, validateMessage)
}

export async function sendConversationMessage(
  projectId: number,
  conversationId: number,
  request: SendConversationMessageRequest,
): Promise<SendMessageResponse> {
  const response = await http.post<ApiResult<SendMessageResponse>>(
    `${conversationPath(projectId, conversationId)}/messages`,
    request,
    { timeout: CONVERSATION_SEND_TIMEOUT_MS },
  )
  return validateSendResponse(response.data.data)
}
