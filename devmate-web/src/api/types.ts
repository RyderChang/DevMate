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
