import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as authApi from '@/api/auth'
import type { LoginResponse, User } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth')

const user: User = {
  id: 7,
  username: 'alice',
  nickname: 'Alice',
  avatar: null,
  role: 'USER',
}

const loginResponse: LoginResponse = {
  token: 'signed-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  user,
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('logs in and stores the access token for the current tab', async () => {
    vi.mocked(authApi.login).mockResolvedValue(loginResponse)
    const store = useAuthStore()

    await store.login({ username: 'alice', password: 'password123' })

    expect(store.user).toEqual(user)
    expect(store.isAuthenticated).toBe(true)
    expect(store.loading).toBe(false)
    expect(window.sessionStorage.getItem('devmate.accessToken')).toBe('signed-token')
  })

  it('restores a valid session through auth me', async () => {
    window.sessionStorage.setItem('devmate.accessToken', 'existing-token')
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user)
    const store = useAuthStore()

    await expect(store.restoreSession()).resolves.toBe(true)

    expect(store.user).toEqual(user)
    expect(store.token).toBe('existing-token')
    expect(store.initialized).toBe(true)
  })

  it('clears an invalid session without leaking the API error', async () => {
    window.sessionStorage.setItem('devmate.accessToken', 'expired-token')
    vi.mocked(authApi.fetchCurrentUser).mockRejectedValue(new Error('expired'))
    const store = useAuthStore()

    await expect(store.restoreSession()).resolves.toBe(false)

    expect(store.isAuthenticated).toBe(false)
    expect(store.initialized).toBe(true)
    expect(window.sessionStorage.getItem('devmate.accessToken')).toBeNull()
  })

  it('registers without creating a local authenticated session', async () => {
    vi.mocked(authApi.register).mockResolvedValue(user)
    const store = useAuthStore()

    await expect(
      store.register({ username: 'alice', password: 'password123', nickname: 'Alice' }),
    ).resolves.toEqual(user)

    expect(store.isAuthenticated).toBe(false)
    expect(window.sessionStorage.getItem('devmate.accessToken')).toBeNull()
  })

  it('logs out and removes all session state', () => {
    const store = useAuthStore()
    store.$patch({ token: 'signed-token', user })
    window.sessionStorage.setItem('devmate.accessToken', 'signed-token')

    store.logout()

    expect(store.user).toBeNull()
    expect(store.token).toBeNull()
    expect(store.initialized).toBe(true)
    expect(window.sessionStorage.getItem('devmate.accessToken')).toBeNull()
  })
})
