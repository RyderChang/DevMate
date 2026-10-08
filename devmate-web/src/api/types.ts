export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  page: number
  pageSize: number
  total: number
  items: T[]
}

export interface Project {
  id: number
  name: string
  description: string | null
  createTime: string
  updateTime: string
}

export interface ProjectMutationRequest {
  name: string
  description?: string | null
}

export type GenerationState = 'IDLE' | 'GENERATING'
export type ConversationRole = 'USER' | 'ASSISTANT'

export interface Conversation {
  id: number
  projectId: number
  title: string
  generationState: GenerationState
  createdAt: string
  updatedAt: string
}

export interface ConversationMessage {
  id: number
  role: ConversationRole
  content: string
  sequenceNo: number
  createdAt: string
  evidence?: RagEvidence | null
}

export interface RagSummary {
  retrievalId: string
  spec: string
  queryTokens: number
  rounds: number
  inspectedPoints: number
  templateVersion: string
  checkedAt: string
  offsetUnit: 'NORMALIZED_UNICODE_CODE_POINT'
}

export interface CitationSource {
  pointId: string
  documentId: number
  filename: string
  processingId: number
  indexId: number
  processingGeneration: number
  indexGeneration: number
  parserVersion: string
  strategyVersion: string
  sourceSha256: string
  chunkSha256: string
  ordinal: number
  start: number
  end: number
  startLine: number
  endLine: number
}

export interface Citation {
  citationId: string
  source: CitationSource
  available: boolean
}

export interface RagEvidence {
  rag: RagSummary
  citations: Citation[]
}

export interface InvocationSummary {
  status: 'SUCCEEDED'
  provider: string
  model: string
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  durationMs: number | null
  completedAt: string | null
}

export interface SendMessageResponse {
  conversationId: number
  userMessage: ConversationMessage
  assistantMessage: ConversationMessage
  invocation: InvocationSummary
}

export interface RagSendMessageResponse extends SendMessageResponse, RagEvidence {}

export interface User {
  id: number
  username: string
  nickname: string
  avatar: string | null
  role: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
}

export interface LoginResponse {
  token: string
  tokenType: 'Bearer'
  expiresIn: number
  user: User
}
