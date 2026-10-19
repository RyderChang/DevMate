import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { useAuthStore } from '@/stores/auth'

async function mountLayout(path: string) {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  auth.$patch({
    user: { id: 7, username: 'reviewer', nickname: '', avatar: null, role: 'USER' },
    token: 'synthetic-session',
    initialized: true,
  })
  const page = { template: '<h1>合成页面</h1>' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: DefaultLayout,
        children: [
          { path: 'projects', name: 'project-list', component: page },
          { path: 'projects/:projectId', name: 'project-detail', component: page },
          { path: 'projects/:projectId/conversations', name: 'conversation-list', component: page },
          {
            path: 'projects/:projectId/conversations/:conversationId',
            name: 'conversation-chat',
            component: page,
          },
        ],
      },
      { path: '/login', name: 'login', component: page },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(
    { template: '<RouterView />' },
    { global: { plugins: [pinia, router, ElementPlus] } },
  )
  await flushPromises()
  return { wrapper, router, auth }
}

describe('workspace navigation', () => {
  it('offers only general navigation without a valid project and provides a skip target', async () => {
    const { wrapper, router } = await mountLayout('/projects')
    expect(wrapper.findAll('nav a').map((link) => link.attributes('href'))).toEqual(['/projects'])
    expect(wrapper.find('nav a[aria-current="page"]').text()).toBe('我的项目')
    expect(wrapper.find('.skip-link').attributes('href')).toBe('#main-content')
    expect(wrapper.find('#main-content').attributes('tabindex')).toBe('-1')
    await router.push('/projects/not-an-id')
    await flushPromises()
    expect(wrapper.findAll('nav a')).toHaveLength(1)
  })

  it('keeps project links scoped to the current route and updates the active destination', async () => {
    const { wrapper, router } = await mountLayout('/projects/42/conversations/3')
    expect(wrapper.find('nav a[aria-current="page"]').attributes('href')).toBe(
      '/projects/42/conversations',
    )
    expect(wrapper.find('a[href="/projects/42"]').text()).toBe('项目概览')
    await router.push('/projects/99')
    await flushPromises()
    expect(wrapper.find('nav a[aria-current="page"]').attributes('href')).toBe('/projects/99')
    expect(wrapper.find('a[href="/projects/99/conversations"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/projects/42/conversations"]').exists()).toBe(false)
  })

  it('uses the account fallback and clears the session before returning to login', async () => {
    const { wrapper, router, auth } = await mountLayout('/projects')
    expect(wrapper.find('.account-name').text()).toBe('reviewer')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
  })
})
