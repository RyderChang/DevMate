import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import * as authApi from '@/api/auth'
import type { User } from '@/api/types'
import RegisterView from '@/views/RegisterView.vue'

vi.mock('@/api/auth')

const user: User = {
  id: 7,
  username: 'alice',
  nickname: 'Alice',
  avatar: null,
  role: 'USER',
}

async function mountRegisterView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const placeholder = defineComponent({ template: '<div>Login</div>' })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', component: RegisterView },
      { path: '/login', name: 'login', component: placeholder },
    ],
  })
  await router.push('/register')
  await router.isReady()
  const wrapper = mount(RegisterView, { global: { plugins: [pinia, router, ElementPlus] } })
  return { wrapper, router }
}

describe('RegisterView', () => {
  it('rejects mismatched passwords before calling the backend', async () => {
    const { wrapper } = await mountRegisterView()
    const passwordInputs = wrapper.findAll('input[autocomplete="new-password"]')

    await wrapper.find('input[autocomplete="username"]').setValue('alice')
    await passwordInputs[0]?.setValue('password123')
    await passwordInputs[1]?.setValue('different123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('两次输入的密码不一致')
    expect(authApi.register).not.toHaveBeenCalled()
  })

  it('registers normalized values and returns to login without creating a session', async () => {
    vi.mocked(authApi.register).mockResolvedValue(user)
    const { wrapper, router } = await mountRegisterView()
    const passwordInputs = wrapper.findAll('input[autocomplete="new-password"]')

    await wrapper.find('input[autocomplete="username"]').setValue('  alice  ')
    await wrapper.find('input[autocomplete="nickname"]').setValue('  Alice  ')
    await passwordInputs[0]?.setValue('password123')
    await passwordInputs[1]?.setValue('password123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(authApi.register).toHaveBeenCalledWith({
      username: 'alice',
      nickname: 'Alice',
      password: 'password123',
    })
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.registered).toBe('1')
    expect(window.sessionStorage.getItem('devmate.accessToken')).toBeNull()
  })
})
