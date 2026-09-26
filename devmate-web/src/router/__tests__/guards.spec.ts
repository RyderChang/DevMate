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
    expect(router.currentRoute.value.query.redirect).toBe('/projects')
  })

  it('restores a valid session before entering a protected route', async () => {
    window.sessionStorage.setItem('devmate.accessToken', 'signed-token')
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user)
    useAuthStore(pinia).$reset()

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('project-list')
  })

  it('keeps an authenticated user away from guest-only pages', async () => {
    const store = useAuthStore(pinia)
    store.$patch({ token: 'signed-token', user, initialized: true })
    await router.push('/register')

    expect(router.currentRoute.value.name).toBe('project-list')
  })

  it.each([
    '/projects',
    '/projects/new',
    '/projects/42',
    '/projects/42/edit',
    '/projects/42/conversations',
    '/projects/42/conversations/9',
  ])('protects the project route %s', async (path) => {
    await router.push(path)

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe(path)
  })

  it('matches the static create route before the project id route', () => {
    expect(router.resolve('/projects/new').name).toBe('project-create')
  })

  it('resolves the project conversation routes', () => {
    expect(router.resolve('/projects/42/conversations').name).toBe('conversation-list')
    expect(router.resolve('/projects/42/conversations/9').name).toBe('conversation-chat')
  })
})
