import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as conversationApi from '@/api/conversations'
import * as projectApi from '@/api/projects'
import type {
  Conversation,
  ConversationMessage,
  Project,
  RagSendMessageResponse,
  SendMessageResponse,
} from '@/api/types'
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

function ragResponse(available = true): RagSendMessageResponse {
  return {
    ...sendResponse(),
    rag: {
      retrievalId: '11111111-1111-4111-8111-111111111111',
      spec: 'test-spec',
      queryTokens: 3,
      rounds: 1,
      inspectedPoints: 1,
      templateVersion: 'project-rag-v1',
      checkedAt: '2026-09-29T00:00:00Z',
      offsetUnit: 'NORMALIZED_UNICODE_CODE_POINT',
    },
    citations: [
      {
        citationId: 'C1',
        available,
        source: {
          pointId: 'point-1',
          documentId: 1,
          filename: '<img src=x onerror=alert(1)>.md',
          processingId: 2,
          indexId: 3,
          processingGeneration: 1,
          indexGeneration: 1,
          parserVersion: 'p1',
          strategyVersion: 's1',
          sourceSha256: 'a'.repeat(64),
          chunkSha256: 'b'.repeat(64),
          ordinal: 0,
          start: 0,
          end: 10,
          startLine: 1,
          endLine: 2,
        },
      },
    ],
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
  vi.mocked(conversationApi.sendRagConversationMessage).mockResolvedValue(ragResponse())
})

afterEach(() => vi.unstubAllEnvs())

describe('ConversationChatView', () => {
  it('restores retired citations as untrusted text while the RAG composer is off', async () => {
    const answer = ragResponse(false)
    vi.mocked(conversationApi.listConversationMessages).mockResolvedValue({
      page: 1,
      pageSize: 50,
      total: 2,
      items: [
        answer.userMessage,
        {
          ...answer.assistantMessage,
          evidence: {
            rag: answer.rag,
            citations: answer.citations,
          },
        },
      ],
    })
    const { wrapper } = await mountChat()
    expect(wrapper.find('.mode-choice').exists()).toBe(false)
    expect(wrapper.text()).toContain('来源当前不可用')
    expect(wrapper.text()).toContain('发布时来源检查时间')
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>.md')
    expect(wrapper.find('.rag-evidence img').exists()).toBe(false)
    expect(conversationApi.sendRagConversationMessage).not.toHaveBeenCalled()
  })

  it('freezes RAG mode and UUID across uncertain results, then shows trusted citations', async () => {
    vi.stubEnv('VITE_RAG_ENABLED', 'true')
    const uuid = '11111111-1111-4111-8111-111111111111'
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(uuid)
    vi.mocked(conversationApi.sendRagConversationMessage)
      .mockRejectedValueOnce({
        isAxiosError: true,
        response: { status: 503, data: { message: 'RAG state cannot be confirmed' } },
      })
      .mockResolvedValueOnce(ragResponse())
    const { wrapper } = await mountChat()
    await wrapper
      .findAll('.el-radio-button')
      .find((button) => button.text() === '文档问答')
      ?.trigger('click')
    await wrapper.find('textarea').setValue('  document question  ')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请求状态不确定')
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试确认结果')
      ?.trigger('click')
    await flushPromises()
    expect(crypto.randomUUID).toHaveBeenCalledTimes(1)
    expect(
      vi.mocked(conversationApi.sendRagConversationMessage).mock.calls.map((call) => call[2]),
    ).toEqual([
      { clientRequestId: uuid, content: 'document question' },
      { clientRequestId: uuid, content: 'document question' },
    ])
    expect(conversationApi.sendConversationMessage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('文档引用')
    expect(wrapper.text()).toContain('当前可用')
    expect(wrapper.find('.rag-evidence img').exists()).toBe(false)
  })

  it('ends a terminal RAG 409 without offering same-request confirmation', async () => {
    vi.stubEnv('VITE_RAG_ENABLED', 'true')
    vi.mocked(conversationApi.sendRagConversationMessage).mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 409, data: { message: 'No bounded document context is available' } },
    })
    const { wrapper } = await mountChat()
    await wrapper
      .findAll('.el-radio-button')
      .find((button) => button.text() === '文档问答')
      ?.trigger('click')
    await wrapper.find('textarea').setValue('question')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('没有可用于回答的文档片段')
    expect(wrapper.text()).not.toContain('重试确认结果')
    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()
    expect(conversationApi.sendConversationMessage).not.toHaveBeenCalled()
  })

  it('ignores an old RAG answer after leaving and returning to the same route', async () => {
    vi.stubEnv('VITE_RAG_ENABLED', 'true')
    let resolveOld!: (value: RagSendMessageResponse) => void
    vi.mocked(conversationApi.sendRagConversationMessage).mockImplementation(
      () => new Promise((resolve) => (resolveOld = resolve)),
    )
    vi.mocked(conversationApi.getConversation).mockImplementation((_projectId, id) =>
      Promise.resolve(
        id === 9 ? conversation : { ...conversation, id: 10, title: 'Another conversation' },
      ),
    )
    const { router, wrapper } = await mountChat()
    await wrapper
      .findAll('.el-radio-button')
      .find((button) => button.text() === '文档问答')
      ?.trigger('click')
    await wrapper.find('textarea').setValue('old question')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await router.push('/projects/42/conversations/10')
    await router.push('/projects/42/conversations/9')
    await flushPromises()
    resolveOld(ragResponse())
    await flushPromises()
    expect(wrapper.findAll('.message-item')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('文档引用')
    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()
  })
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

  it('shows and retries metadata failures during a manual refresh', async () => {
    const { wrapper } = await mountChat()
    vi.mocked(conversationApi.getConversation).mockRejectedValueOnce(new Error('offline'))

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')
      ?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Architecture')
    expect(wrapper.text()).toContain('加载对话失败')

    await wrapper.find('.el-alert button').trigger('click')
    await flushPromises()

    expect(conversationApi.getConversation).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).not.toContain('加载对话失败')
  })

  it('retries the latest-page load instead of loading an older page', async () => {
    const oldest = message(1, 1, 'USER')
    const latest = message(101, 101, 'ASSISTANT')
    vi.mocked(conversationApi.listConversationMessages)
      .mockResolvedValueOnce({ page: 1, pageSize: 50, total: 101, items: [oldest] })
      .mockResolvedValueOnce({ page: 3, pageSize: 50, total: 101, items: [latest] })
      .mockResolvedValueOnce({ page: 1, pageSize: 50, total: 101, items: [oldest] })
      .mockRejectedValueOnce(new Error('latest page offline'))
      .mockResolvedValueOnce({ page: 1, pageSize: 50, total: 101, items: [oldest] })
      .mockResolvedValueOnce({ page: 3, pageSize: 50, total: 101, items: [latest] })
    const { wrapper } = await mountChat()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')
      ?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('最新消息加载失败')

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试')
      ?.trigger('click')
    await flushPromises()

    expect(conversationApi.listConversationMessages).toHaveBeenNthCalledWith(5, 42, 9, {
      page: 1,
      pageSize: 50,
    })
    expect(conversationApi.listConversationMessages).toHaveBeenNthCalledWith(6, 42, 9, {
      page: 3,
      pageSize: 50,
    })
    expect(wrapper.text()).toContain('message 101')
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

  it('keeps an in-flight send active across a same-route refresh', async () => {
    let resolveSend!: (value: SendMessageResponse) => void
    vi.mocked(conversationApi.sendConversationMessage).mockImplementation(
      () => new Promise((resolve) => (resolveSend = resolve)),
    )
    const { wrapper } = await mountChat()
    await wrapper.find('textarea').setValue('question')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')
      ?.trigger('click')
    await flushPromises()

    resolveSend(sendResponse('question'))
    await flushPromises()

    expect(wrapper.text()).toContain('question')
    expect(wrapper.text()).toContain('answer')
    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()
  })

  it('unlocks the composer and ignores the old send after switching conversations', async () => {
    let resolveSend!: (value: SendMessageResponse) => void
    vi.mocked(conversationApi.sendConversationMessage).mockImplementation(
      () => new Promise((resolve) => (resolveSend = resolve)),
    )
    vi.mocked(conversationApi.getConversation).mockImplementation((_projectId, conversationId) =>
      Promise.resolve(
        conversationId === 9
          ? conversation
          : { ...conversation, id: 10, title: 'New conversation' },
      ),
    )
    const { router, wrapper } = await mountChat()
    await wrapper.find('textarea').setValue('old question')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送消息')
      ?.trigger('click')

    await router.push('/projects/42/conversations/10')
    await flushPromises()
    expect(wrapper.text()).toContain('New conversation')
    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()

    resolveSend(sendResponse('old question'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('old question')
    expect(wrapper.text()).not.toContain('answer')
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
