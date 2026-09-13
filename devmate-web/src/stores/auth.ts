import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import type { LoginRequest, RegisterRequest, User } from '@/api/types'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/utils/session'

interface AuthState {
  token: string | null
  user: User | null
  initialized: boolean
  loading: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: getAccessToken(),
    user: null,
    initialized: false,
    loading: false,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token && state.user),
  },
  actions: {
    async login(request: LoginRequest): Promise<void> {
      this.loading = true
      try {
        const response = await authApi.login(request)
        this.token = response.token
        this.user = response.user
        this.initialized = true
        setAccessToken(response.token)
      } finally {
        this.loading = false
      }
    },
    async register(request: RegisterRequest): Promise<User> {
      this.loading = true
      try {
        return await authApi.register(request)
      } finally {
        this.loading = false
      }
    },
    async restoreSession(): Promise<boolean> {
      if (this.initialized) {
        return this.isAuthenticated
      }

      const token = getAccessToken()
      if (!token) {
        this.clearSession()
        this.initialized = true
        return false
      }

      this.token = token
      try {
        this.user = await authApi.fetchCurrentUser()
        return true
      } catch {
        this.clearSession()
        return false
      } finally {
        this.initialized = true
      }
    },
    clearSession(): void {
      clearAccessToken()
      this.token = null
      this.user = null
    },
    logout(): void {
      this.clearSession()
      this.initialized = true
    },
  },
})
