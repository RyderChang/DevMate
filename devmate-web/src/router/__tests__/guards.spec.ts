import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as authApi from '@/api/auth'
import type { User } from '@/api/types'
import router from '@/router'
import { pinia } from '@/stores'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth')

const user: User = {
  id: 7,
  username: 'alice',
  nickname: 'Alice',
  avatar: null,
  role: 'USER',
}

describe('authentication route guards', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    useAuthStore(pinia).$reset()
    await router.replace('/login')
  })

  it('redirects a guest from a protected route to login', async () => {
    await router.push('/')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/')
  })

  it('restores a valid session before entering a protected route', async () => {
    window.sessionStorage.setItem('devmate.accessToken', 'signed-token')
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user)
    useAuthStore(pinia).$reset()

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('keeps an authenticated user away from guest-only pages', async () => {
    const store = useAuthStore(pinia)
    store.$patch({ token: 'signed-token', user, initialized: true })
    await router.push('/register')

    expect(router.currentRoute.value.name).toBe('home')
  })
})
