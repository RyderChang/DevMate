import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as conversationApi from '@/api/conversations'
import * as projectApi from '@/api/projects'
import type { Conversation, ConversationMessage, Project, SendMessageResponse } from '@/api/types'
import ConversationChatView from '@/views/ConversationChatView.vue'

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
  title: 'Architecture',
  generationState: 'IDLE',
  createdAt: '2026-09-21T01:00:00Z',
  updatedAt: '2026-09-21T02:00:00Z',
}

function message(
  id: number,
  sequenceNo: number,
  role: ConversationMessage['role'],
  content = `message ${sequenceNo}`,
): ConversationMessage {
  return { id, sequenceNo, role, content, createdAt: '2026-09-21T02:00:00Z' }
}

function sendResponse(content = 'question'): SendMessageResponse {
  return {
    conversationId: 9,
    userMessage: message(101, 3, 'USER', content),
    assistantMessage: message(102, 4, 'ASSISTANT', 'answer'),
    invocation: {
      status: 'SUCCEEDED',
      provider: 'openai',
      model: 'test-model',
      inputTokens: null,
      outputTokens: null,
      totalTokens: null,
      durationMs: null,
      completedAt: null,
    },
  }
}

async function mountChat(path = '/projects/42/conversations/9') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:projectId', name: 'project-detail', component: { template: '<div />' } },
      {
        path: '/projects/:projectId/conversations',
        name: 'conversation-list',
        component: { template: '<div />' },
      },
      {
        path: '/projects/:projectId/conversations/:conversationId',
        name: 'conversation-chat',
        component: ConversationChatView,
      },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ConversationChatView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  })
  await flushPromises()
  return { router, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(projectApi.getProject).mockResolvedValue(project)
  vi.mocked(conversationApi.getConversation).mockResolvedValue(conversation)
  vi.mocked(conversationApi.listConversationMessages).mockResolvedValue({
    page: 1,
    pageSize: 50,
    total: 0,
    items: [],
  })
  vi.mocked(conversationApi.sendConversationMessage).mockResolvedValue(sendResponse())
})

describe('ConversationChatView', () => {
  it('does not call APIs for invalid or unsafe route ids', async () => {
    const { wrapper } = await mountChat('/projects/42/conversations/9007199254740992')

    expect(projectApi.getProject).not.toHaveBeenCalled()
    expect(conversationApi.getConversation).not.toHaveBeenCalled()
    expect(conversationApi.listConversationMessages).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('对话不存在或无权访问')
  })

  it('loads the latest page, prepends earlier messages, sorts, and deduplicates', async () => {
    const oldest = message(1, 1, 'USER')
    const duplicateSequence = message(999, 1, 'USER', 'duplicate')
    const latest = message(51, 51, 'ASSISTANT')
    vi.mocked(conversationApi.listConversationMessages)
      .mockResolvedValueOnce({ page: 1, pageSize: 50, total: 51, items: [oldest] })
      .mockResolvedValueOnce({ page: 2, pageSize: 50, total: 51, items: [latest] })
      .mockResolvedValueOnce({
        page: 1,
        pageSize: 50,
        total: 51,
        items: [duplicateSequence, oldest],
      })
    const { wrapper } = await mountChat()

    expect(wrapper.text()).toContain('message 51')
    expect(wrapper.text()).not.toContain('message 1')
    expect(conversationApi.listConversationMessages).toHaveBeenNthCalledWith(1, 42, 9, {
      page: 1,
      pageSize: 50,
    })
    expect(conversationApi.listConversationMessages).toHaveBeenNthCalledWith(2, 42, 9, {
      page: 2,
      pageSize: 50,
    })

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '加载更早消息')
      ?.trigger('click')
    await flushPromises()
    const items = wrapper.findAll('.message-item')
    expect(items).toHaveLength(2)
    expect(items[0]?.text()).toContain('message 1')
    expect(items[1]?.text()).toContain('message 51')
    expect(wrapper.text()).not.toContain('duplicate')
  })

  it('renders message HTML as text and preserves newlines', async () => {
    vi.mocked(conversationApi.listConversationMessages).mockResolvedValue({
      page: 1,
      pageSize: 50,
      total: 1,
      items: [message(1, 1, 'ASSISTANT', '<img src=x onerror=alert(1)>\nnext line')],
    })
    const { wrapper } = await mountChat()

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('.message-item img').exists()).toBe(false)
    expect(wrapper.find('.message-item p').attributes('style')).toBeUndefined()
  })

  it('preserves loaded messages when a manual refresh cannot reload history', async () => {
    vi.mocked(conversationApi.listConversationMessages).mockResolvedValueOnce({
      page: 1,
      pageSize: 50,
      total: 1,
      items: [message(1, 1, 'ASSISTANT', 'existing message')],
    })
    const { wrapper } = await mountChat()
    vi.mocked(conversationApi.listConversationMessages).mockRejectedValueOnce(new Error('offline'))

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')
      ?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('existing message')
    expect(wrapper.text()).toContain('加载消息历史失败')
  })

  it('validates empty and over-limit content by Unicode code point', async () => {
    const { wrapper } = await mountChat()
    const textarea = wrapper.find('textarea')
    const send = wrapper.findAll('button').find((button) => button.text() === '发送消息')

    await textarea.setValue('   ')
    await send?.trigger('click')
    await textarea.setValue('😀'.repeat(8001))
    await send?.trigger('click')

    expect(conversationApi.sendConversationMessage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('8001/8000')
  })

  it('creates one UUID, prevents duplicate sends, and appends only server messages', async () => {
    const uuid = '11111111-1111-4111-8111-111111111111'
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(uuid)
    let resolveSend!: (value: SendMessageResponse) => void
    vi.mocked(conversationApi.sendConversationMessage).mockImplementation(
      () => new Promise((resolve) => (resolveSend = resolve)),
    )
    const { wrapper } = await mountChat()
    await wrapper.find('textarea').setValue('  question  ')
    const send = wrapper.findAll('button').find((button) => button.text() === '发送消息')
    await send?.trigger('click')
    await send?.trigger('click')

    expect(crypto.randomUUID).toHaveBeenCalledTimes(1)
    expect(conversationApi.sendConversationMessage).toHaveBeenCalledTimes(1)
    expect(conversationApi.sendConversationMessage).toHaveBeenCalledWith(42, 9, {
      clientRequestId: uuid,
      content: 'question',
    })
    expect(wrapper.text()).not.toContain('question')

    resolveSend(sendResponse('question'))
    await flushPromises()
    expect(wrapper.text()).toContain('question')
    expect(wrapper.text()).toContain('answer')
    expect(wrapper.find('textarea').element.value).toBe('')
  })

  it('reuses the UUID after an uncertain network result', async () => {
    const uuid = '11111111-1111-4111-8111-111111111111'
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(uuid)
    vi.mocked(conversationApi.sendConversationMessage)
      .mockRejectedValueOnce({ isAxiosError: true, code: 'ECONNABORTED' })
      .mockResolvedValueOnce(sendResponse())
    const { wrapper } = await mountChat()
    await wrapper.find('textarea').setValue('question')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('网络状态不确定')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试确认结果')
      ?.trigger('click')
    await flushPromises()

    expect(crypto.randomUUID).toHaveBeenCalledTimes(1)
    expect(conversationApi.sendConversationMessage).toHaveBeenCalledTimes(2)
    expect(
      vi.mocked(conversationApi.sendConversationMessage).mock.calls[1]?.[2].clientRequestId,
    ).toBe(uuid)
  })

  it('limits same-UUID confirmation attempts', async () => {
    const uuid = '11111111-1111-4111-8111-111111111111'
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(uuid)
    vi.mocked(conversationApi.sendConversationMessage).mockRejectedValue({
      isAxiosError: true,
      code: 'ECONNABORTED',
    })
    const { wrapper } = await mountChat()
    await wrapper.find('textarea').setValue('question')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await flushPromises()

    for (let attempt = 0; attempt < 3; attempt += 1) {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '重试确认结果')
        ?.trigger('click')
      await flushPromises()
    }

    expect(crypto.randomUUID).toHaveBeenCalledTimes(1)
    expect(conversationApi.sendConversationMessage).toHaveBeenCalledTimes(3)
    expect(
      vi
        .mocked(conversationApi.sendConversationMessage)
        .mock.calls.every((call) => call[2].clientRequestId === uuid),
    ).toBe(true)
    expect(wrapper.text()).toContain('已达到手动确认上限')
  })

  it('retains the UUID for 409 but uses a new UUID after a definite provider failure', async () => {
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('11111111-1111-4111-8111-111111111111')
      .mockReturnValueOnce('22222222-2222-4222-8222-222222222222')
    vi.mocked(conversationApi.sendConversationMessage)
      .mockRejectedValueOnce({ isAxiosError: true, response: { status: 409 } })
      .mockRejectedValueOnce({ isAxiosError: true, response: { status: 503 } })
      .mockResolvedValueOnce(sendResponse('second'))
    const { wrapper } = await mountChat()
    await wrapper.find('textarea').setValue('first')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已有回复正在生成')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试确认结果')
      ?.trigger('click')
    await flushPromises()
    expect(
      vi.mocked(conversationApi.sendConversationMessage).mock.calls[1]?.[2].clientRequestId,
    ).toBe('11111111-1111-4111-8111-111111111111')
    expect(wrapper.text()).toContain('AI 服务暂时不可用')

    await wrapper.find('textarea').setValue('second')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await flushPromises()
    expect(
      vi.mocked(conversationApi.sendConversationMessage).mock.calls[2]?.[2].clientRequestId,
    ).toBe('22222222-2222-4222-8222-222222222222')
  })

  it('blocks new sends while the server reports GENERATING', async () => {
    vi.mocked(conversationApi.getConversation).mockResolvedValue({
      ...conversation,
      generationState: 'GENERATING',
    })
    const { wrapper } = await mountChat()

    expect(wrapper.text()).toContain('服务端仍在生成回复')
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
    expect(conversationApi.sendConversationMessage).not.toHaveBeenCalled()
  })

  it('keeps the newer conversation when an old route response resolves late', async () => {
    let resolveOld!: (value: Conversation) => void
    const newer = { ...conversation, id: 10, title: 'New conversation' }
    vi.mocked(conversationApi.getConversation).mockImplementation((_projectId, conversationId) =>
      conversationId === 9
        ? new Promise((resolve) => (resolveOld = resolve))
        : Promise.resolve(newer),
    )
    const { router, wrapper } = await mountChat()

    await router.push('/projects/42/conversations/10')
    await flushPromises()
    expect(wrapper.text()).toContain('New conversation')

    resolveOld(conversation)
    await flushPromises()
    expect(wrapper.text()).toContain('New conversation')
    expect(wrapper.text()).not.toContain('Architecture')
  })
})
