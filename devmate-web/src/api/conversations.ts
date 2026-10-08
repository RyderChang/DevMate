import http from '@/api/http'
import type {
  ApiResult,
  Conversation,
  ConversationMessage,
  Citation,
  PageResult,
  RagEvidence,
  RagSendMessageResponse,
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
export const RAG_SEND_TIMEOUT_MS = 690_000

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
  if (value.evidence != null) {
    if (value.role !== 'ASSISTANT') throw new TypeError('Only assistant messages can have evidence')
    validateEvidence(value.evidence)
  }
  return value
}

function validateCitation(value: Citation): void {
  if (!/^C[1-5]$/.test(value.citationId)) throw new TypeError('Citation id is invalid')
  if (typeof value.available !== 'boolean') throw new TypeError('Citation availability is invalid')
  const source = value.source
  if (!source || typeof source !== 'object') throw new TypeError('Citation source is invalid')
  for (const key of [
    'pointId',
    'filename',
    'parserVersion',
    'strategyVersion',
    'sourceSha256',
    'chunkSha256',
  ] as const) {
    assertString(source[key], `Citation ${key}`)
  }
  for (const key of [
    'documentId',
    'processingId',
    'indexId',
    'processingGeneration',
    'indexGeneration',
    'ordinal',
    'start',
    'end',
    'startLine',
    'endLine',
  ] as const) {
    assertNonNegativeSafeInteger(source[key], `Citation ${key}`)
  }
  for (const key of [
    'documentId',
    'processingId',
    'indexId',
    'processingGeneration',
    'indexGeneration',
  ] as const) {
    positiveSafeInteger(source[key], `Citation ${key}`)
  }
  if (source.start >= source.end || source.startLine > source.endLine) {
    throw new TypeError('Citation range is invalid')
  }
}

function validateEvidence(value: RagEvidence): RagEvidence {
  if (!value || !value.rag || !Array.isArray(value.citations)) {
    throw new TypeError('RAG evidence is invalid')
  }
  const rag = value.rag
  for (const key of ['retrievalId', 'spec', 'templateVersion', 'checkedAt'] as const) {
    assertString(rag[key], `RAG ${key}`)
  }
  for (const key of ['queryTokens', 'rounds', 'inspectedPoints'] as const) {
    assertNonNegativeSafeInteger(rag[key], `RAG ${key}`)
  }
  if (rag.queryTokens < 1 || rag.offsetUnit !== 'NORMALIZED_UNICODE_CODE_POINT') {
    throw new TypeError('RAG summary is invalid')
  }
  if (value.citations.length < 1 || value.citations.length > 5) {
    throw new TypeError('Citation count is invalid')
  }
  value.citations.forEach(validateCitation)
  if (
    new Set(value.citations.map((citation) => citation.citationId)).size !== value.citations.length
  ) {
    throw new TypeError('Citation ids must be unique')
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
  if (value.userMessage.role !== 'USER' || value.assistantMessage.role !== 'ASSISTANT') {
    throw new TypeError('Send response roles are invalid')
  }
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

export async function sendRagConversationMessage(
  projectId: number,
  conversationId: number,
  request: SendConversationMessageRequest,
): Promise<RagSendMessageResponse> {
  const response = await http.post<ApiResult<RagSendMessageResponse>>(
    `${conversationPath(projectId, conversationId)}/rag-messages`,
    request,
    { timeout: RAG_SEND_TIMEOUT_MS },
  )
  const result = response.data.data
  validateSendResponse(result)
  validateEvidence(result)
  return result
}
