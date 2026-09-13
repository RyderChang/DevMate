import http from '@/api/http'
import type { ApiResult, LoginRequest, LoginResponse, RegisterRequest, User } from '@/api/types'

export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await http.post<ApiResult<LoginResponse>>('/auth/login', request)
  return response.data.data
}

export async function register(request: RegisterRequest): Promise<User> {
  const response = await http.post<ApiResult<User>>('/auth/register', request)
  return response.data.data
}

export async function fetchCurrentUser(): Promise<User> {
  const response = await http.get<ApiResult<User>>('/auth/me')
  return response.data.data
}
