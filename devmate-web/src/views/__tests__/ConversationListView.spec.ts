import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as conversationApi from '@/api/conversations'
import * as projectApi from '@/api/projects'
import type { Conversation, Project } from '@/api/types'
import ConversationListView from '@/views/ConversationListView.vue'

vi.mock('@/api/conversations')
vi.mock('@/api/projects')

const project: Project = {
  id: 42,
  name: 'DevMate',
  description: null,
  createTime: '2026-09-21T01:00:00Z',
  updateTime: '2026-09-21T02:00:00Z',
}

const conversation: Conversation = {
  id: 9,
  projectId: 42,
  title: '<script>alert(1)</script>',
  generationState: 'GENERATING',
  createdAt: 'invalid',
  updatedAt: '2026-09-21T02:00:00Z',
}

const unavailableError = {
  isAxiosError: true,
  response: { status: 404, data: { message: 'private' } },
}

async function mountList(path = '/projects/42/conversations?page=1&pageSize=20') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', name: 'project-list', component: { template: '<div />' } },
      { path: '/projects/:projectId', name: 'project-detail', component: { template: '<div />' } },
      {
        path: '/projects/:projectId/conversations',
        name: 'conversation-list',
        component: ConversationListView,
      },
      {
        path: '/projects/:projectId/conversations/:conversationId',
        name: 'conversation-chat',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ConversationListView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  })
  await flushPromises()
  return { router, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(projectApi.getProject).mockResolvedValue(project)
  vi.mocked(conversationApi.listConversations).mockResolvedValue({
    page: 1,
    pageSize: 20,
    total: 1,
    items: [conversation],
  })
})

describe('ConversationListView', () => {
  it('loads conversations in server order and renders untrusted titles as text', async () => {
    const { wrapper } = await mountList()

    expect(projectApi.getProject).toHaveBeenCalledWith(42)
    expect(conversationApi.listConversations).toHaveBeenCalledWith(42, {
      page: 1,
      pageSize: 20,
    })
    expect(wrapper.text()).toContain(conversation.title)
    expect(wrapper.text()).toContain('正在生成')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('--')
  })

  it('normalizes invalid URL pagination before requesting data', async () => {
    const { router } = await mountList('/projects/42/conversations?page=0&pageSize=999')

    expect(router.currentRoute.value.query).toMatchObject({ page: '1', pageSize: '20' })
    expect(conversationApi.listConversations).toHaveBeenCalledTimes(1)
  })

  it('does not call APIs for an invalid project id', async () => {
    const { wrapper } = await mountList(
      '/projects/9007199254740992/conversations?page=1&pageSize=20',
    )

    expect(projectApi.getProject).not.toHaveBeenCalled()
    expect(conversationApi.listConversations).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('项目不存在或无权访问')
  })

  it('shows an empty state and retries a transient failure', async () => {
    vi.mocked(conversationApi.listConversations)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ page: 1, pageSize: 20, total: 0, items: [] })
    const { wrapper } = await mountList()

    expect(wrapper.text()).toContain('无法加载对话')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试')
      ?.trigger('click')
    await flushPromises()

    expect(conversationApi.listConversations).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('还没有对话')
  })

  it('uses one unavailable state for a 404 without exposing server text', async () => {
    vi.mocked(conversationApi.listConversations).mockRejectedValue(unavailableError)
    const { wrapper } = await mountList()

    expect(wrapper.text()).toContain('项目不存在或无权访问')
    expect(wrapper.text()).not.toContain('private')
  })

  it('validates title by Unicode code points', async () => {
    const { wrapper } = await mountList()
    await wrapper.find('.page-actions .el-button--primary').trigger('click')
    await flushPromises()
    const input = wrapper.find('.el-dialog input')
    expect(input.exists()).toBe(true)
    await input.setValue('😀'.repeat(201))

    expect(wrapper.text()).toContain('标题不能超过 200 个字符')
    expect(conversationApi.createConversation).not.toHaveBeenCalled()
  })

  it('normalizes a title, prevents duplicate creation, and navigates with the server id', async () => {
    let resolveCreate!: (value: Conversation) => void
    vi.mocked(conversationApi.createConversation).mockImplementation(
      () => new Promise((resolve) => (resolveCreate = resolve)),
    )
    const { router, wrapper } = await mountList()
    await wrapper.find('.page-actions .el-button--primary').trigger('click')
    await flushPromises()
    await wrapper.find('.el-dialog input').setValue('  Architecture  ')
    const submit = wrapper.findAll('button').find((button) => button.text() === '创建并进入')
    await submit?.trigger('click')
    await submit?.trigger('click')

    expect(conversationApi.createConversation).toHaveBeenCalledTimes(1)
    expect(conversationApi.createConversation).toHaveBeenCalledWith(42, { title: 'Architecture' })

    resolveCreate({ ...conversation, id: 99, title: 'Architecture', generationState: 'IDLE' })
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('conversation-chat'))
    expect(router.currentRoute.value.params.conversationId).toBe('99')
  })

  it('does not redirect after creation completes if the user left the list', async () => {
    let resolveCreate!: (value: Conversation) => void
    vi.mocked(conversationApi.createConversation).mockImplementation(
      () => new Promise((resolve) => (resolveCreate = resolve)),
    )
    const { router, wrapper } = await mountList()
    await wrapper.find('.page-actions .el-button--primary').trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '创建并进入')
      ?.trigger('click')

    await router.push('/projects/42')
    resolveCreate({ ...conversation, id: 99, generationState: 'IDLE' })
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('project-detail')
  })
})
