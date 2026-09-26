import { AxiosHeaders } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  CONVERSATION_SEND_TIMEOUT_MS,
  createConversation,
  getConversation,
  listConversationMessages,
  listConversations,
  sendConversationMessage,
} from '@/api/conversations'
import http from '@/api/http'
import type {
  ApiResult,
  Conversation,
  ConversationMessage,
  PageResult,
  SendMessageResponse,
} from '@/api/types'

const originalAdapter = http.defaults.adapter

const conversation: Conversation = {
  id: 9,
  projectId: 42,
  title: 'Architecture',
  generationState: 'IDLE',
  createdAt: '2026-09-21T01:00:00Z',
  updatedAt: '2026-09-21T02:00:00Z',
}

const message: ConversationMessage = {
  id: 11,
  role: 'USER',
  content: 'Hello',
  sequenceNo: 1,
  createdAt: '2026-09-21T02:00:00Z',
}

function response<T>(config: InternalAxiosRequestConfig, data: ApiResult<T>): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config }
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('conversation api', () => {
  it('uses the expected methods, paths, pagination and safe request bodies', async () => {
    const page: PageResult<Conversation> = {
      page: 1,
      pageSize: 20,
      total: 1,
      items: [conversation],
    }
    const messagePage: PageResult<ConversationMessage> = {
      page: 1,
      pageSize: 50,
      total: 1,
      items: [message],
    }
    const sendResponse: SendMessageResponse = {
      conversationId: 9,
      userMessage: message,
      assistantMessage: { ...message, id: 12, role: 'ASSISTANT', sequenceNo: 2 },
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
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      const data = config.url?.endsWith('/messages')
        ? config.method === 'post'
          ? sendResponse
          : messagePage
        : config.method === 'get' && config.url === '/projects/42/conversations'
          ? page
          : conversation
      return response(config, { code: 200, message: 'success', data })
    })
    http.defaults.adapter = adapter

    await listConversations(42, { page: 1, pageSize: 20 })
    await createConversation(42, { title: 'Architecture' })
    await getConversation(42, 9)
    await listConversationMessages(42, 9, { page: 1, pageSize: 50 })
    await sendConversationMessage(42, 9, {
      clientRequestId: '11111111-1111-4111-8111-111111111111',
      content: 'Hello',
    })

    expect(adapter.mock.calls.map(([config]) => [config.method, config.url])).toEqual([
      ['get', '/projects/42/conversations'],
      ['post', '/projects/42/conversations'],
      ['get', '/projects/42/conversations/9'],
      ['get', '/projects/42/conversations/9/messages'],
      ['post', '/projects/42/conversations/9/messages'],
    ])
    expect(adapter.mock.calls[0]?.[0].params).toEqual({ page: 1, pageSize: 20 })
    expect(adapter.mock.calls[3]?.[0].params).toEqual({ page: 1, pageSize: 50 })
    expect(JSON.parse(String(adapter.mock.calls[1]?.[0].data))).toEqual({ title: 'Architecture' })
    expect(JSON.parse(String(adapter.mock.calls[4]?.[0].data))).toEqual({
      clientRequestId: '11111111-1111-4111-8111-111111111111',
      content: 'Hello',
    })
    expect(adapter.mock.calls[4]?.[0].timeout).toBe(CONVERSATION_SEND_TIMEOUT_MS)
    expect(http.defaults.timeout).toBe(10_000)
  })

  it('rejects unsafe path identifiers before making a request', async () => {
    const adapter = vi.fn<AxiosAdapter>()
    http.defaults.adapter = adapter

    await expect(listConversations(0, { page: 1, pageSize: 20 })).rejects.toBeInstanceOf(RangeError)
    await expect(createConversation(-1, { title: null })).rejects.toBeInstanceOf(RangeError)
    await expect(getConversation(42, Number.MAX_SAFE_INTEGER + 1)).rejects.toBeInstanceOf(
      RangeError,
    )
    await expect(listConversationMessages(42, 0, { page: 1, pageSize: 50 })).rejects.toBeInstanceOf(
      RangeError,
    )
    await expect(
      sendConversationMessage(Number.NaN, 9, { clientRequestId: 'id', content: 'content' }),
    ).rejects.toBeInstanceOf(RangeError)
    expect(adapter).not.toHaveBeenCalled()
  })

  it('rejects unsafe BIGINT values returned by the API', async () => {
    const page: PageResult<Conversation> = {
      page: 1,
      pageSize: 20,
      total: 1,
      items: [conversation],
    }
    const messagePage: PageResult<ConversationMessage> = {
      page: 1,
      pageSize: 50,
      total: 1,
      items: [message],
    }
    const sendResponse: SendMessageResponse = {
      conversationId: 9,
      userMessage: message,
      assistantMessage: { ...message, id: 12, role: 'ASSISTANT', sequenceNo: 2 },
      invocation: {
        status: 'SUCCEEDED',
        provider: 'openai',
        model: 'test-model',
        inputTokens: null,
        outputTokens: null,
        totalTokens: null,
        durationMs: Number.MAX_SAFE_INTEGER + 1,
        completedAt: null,
      },
    }
    const unsafeConversation = { ...conversation, id: Number.MAX_SAFE_INTEGER + 1 }
    const unsafeMessagePage = {
      ...messagePage,
      items: [{ ...message, sequenceNo: Number.MAX_SAFE_INTEGER + 1 }],
    }
    const adapter = vi
      .fn<AxiosAdapter>()
      .mockImplementationOnce(async (config) =>
        response(config, { code: 200, message: 'success', data: unsafeConversation }),
      )
      .mockImplementationOnce(async (config) =>
        response(config, {
          code: 200,
          message: 'success',
          data: { ...page, total: Number.MAX_SAFE_INTEGER + 1 },
        }),
      )
      .mockImplementationOnce(async (config) =>
        response(config, { code: 200, message: 'success', data: unsafeMessagePage }),
      )
      .mockImplementationOnce(async (config) =>
        response(config, { code: 200, message: 'success', data: sendResponse }),
      )
    http.defaults.adapter = adapter

    await expect(getConversation(42, 9)).rejects.toBeInstanceOf(RangeError)
    await expect(listConversations(42, { page: 1, pageSize: 20 })).rejects.toBeInstanceOf(
      RangeError,
    )
    await expect(listConversationMessages(42, 9, { page: 1, pageSize: 50 })).rejects.toBeInstanceOf(
      RangeError,
    )
    await expect(
      sendConversationMessage(42, 9, { clientRequestId: 'id', content: 'content' }),
    ).rejects.toBeInstanceOf(RangeError)
    expect(adapter).toHaveBeenCalledTimes(4)
  })
})
