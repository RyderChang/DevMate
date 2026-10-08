import { describe, expect, it } from 'vitest'

import {
  classifyRagSendFailure,
  codePointLength,
  getConversationErrorMessage,
  getSendErrorMessage,
  isUncertainSendError,
  isValidRagContent,
  normalizeConversationPagination,
  parsePositiveSafeId,
} from '@/utils/conversations'

describe('conversation utilities', () => {
  it('accepts only positive JavaScript-safe identifiers', () => {
    expect(parsePositiveSafeId('42')).toBe(42)
    expect(parsePositiveSafeId('0')).toBeNull()
    expect(parsePositiveSafeId('-1')).toBeNull()
    expect(parsePositiveSafeId('1.5')).toBeNull()
    expect(parsePositiveSafeId('9007199254740992')).toBeNull()
    expect(parsePositiveSafeId(['42'])).toBeNull()
  })

  it('normalizes pagination to supported values', () => {
    expect(normalizeConversationPagination('2', '50')).toEqual({
      page: 2,
      pageSize: 50,
      canonical: true,
    })
    expect(normalizeConversationPagination('0', '99')).toEqual({
      page: 1,
      pageSize: 20,
      canonical: false,
    })
    expect(normalizeConversationPagination(undefined, undefined).canonical).toBe(false)
  })

  it('counts Unicode code points instead of UTF-16 code units', () => {
    expect(codePointLength('a😀b')).toBe(3)
  })

  it('uses UTF-16 and rejects broken surrogate pairs for RAG', () => {
    expect(isValidRagContent('😀'.repeat(4_000))).toBe(true)
    expect(isValidRagContent('😀'.repeat(4_001))).toBe(false)
    expect(isValidRagContent('\ud800')).toBe(false)
    expect(isValidRagContent('')).toBe(false)
  })

  it('distinguishes terminal RAG failures from uncertain or in-progress requests', () => {
    const error = (status: number, message: string) => ({
      isAxiosError: true,
      response: { status, data: { message } },
    })
    expect(
      classifyRagSendFailure(error(409, 'An AI response is already being generated')).inProgress,
    ).toBe(true)
    expect(
      classifyRagSendFailure(error(409, 'No bounded document context is available')),
    ).toMatchObject({
      uncertain: false,
      inProgress: false,
      message: '没有可用于回答的文档片段，请检查项目文档与索引',
    })
    expect(classifyRagSendFailure(error(503, 'RAG state cannot be confirmed')).uncertain).toBe(true)
    expect(classifyRagSendFailure({ isAxiosError: true, code: 'ECONNABORTED' }).uncertain).toBe(
      true,
    )
    expect(classifyRagSendFailure(error(503, 'RAG conversation is disabled')).uncertain).toBe(false)
  })

  it('uses status-based safe messages without exposing provider responses', () => {
    expect(
      getConversationErrorMessage({ isAxiosError: true, response: { status: 404 } }, 'fallback'),
    ).toBe('对话不存在或无权访问')
    expect(
      getSendErrorMessage({
        isAxiosError: true,
        response: { status: 503, data: { message: 'private provider response' } },
      }),
    ).toBe('AI 服务暂时不可用，请稍后重试')
    expect(
      isUncertainSendError({ isAxiosError: true, code: 'ECONNABORTED', response: undefined }),
    ).toBe(true)
  })
})
