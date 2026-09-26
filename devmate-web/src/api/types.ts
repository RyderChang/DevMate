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
