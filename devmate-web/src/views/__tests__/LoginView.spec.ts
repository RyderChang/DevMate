import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import * as authApi from '@/api/auth'
import type { LoginResponse } from '@/api/types'
import LoginView from '@/views/LoginView.vue'

vi.mock('@/api/auth')

const loginResponse: LoginResponse = {
  token: 'signed-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  user: {
    id: 7,
    username: 'alice',
    nickname: 'Alice',
    avatar: null,
    role: 'USER',
  },
}

describe('LoginView', () => {
  it('logs in with normalized username and returns to the requested internal route', async () => {
    vi.mocked(authApi.login).mockResolvedValue(loginResponse)
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: LoginView },
        { path: '/register', component: LoginView },
        { path: '/projects', component: LoginView },
      ],
    })
    await router.push('/login?redirect=/projects')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router, ElementPlus] } })

    await wrapper.find('input[autocomplete="username"]').setValue('  alice  ')
    await wrapper.find('input[autocomplete="current-password"]').setValue('password123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(authApi.login).toHaveBeenCalledWith({ username: 'alice', password: 'password123' })
    expect(router.currentRoute.value.fullPath).toBe('/projects')
    expect(window.sessionStorage.getItem('devmate.accessToken')).toBe('signed-token')
  })

  it('does not follow an external redirect value', async () => {
    vi.mocked(authApi.login).mockResolvedValue(loginResponse)
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: LoginView },
        { path: '/login', component: LoginView },
        { path: '/register', component: LoginView },
      ],
    })
    await router.push('/login?redirect=https://example.com')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router, ElementPlus] } })

    await wrapper.find('input[autocomplete="username"]').setValue('alice')
    await wrapper.find('input[autocomplete="current-password"]').setValue('password123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
  })
})
