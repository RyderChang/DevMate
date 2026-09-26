import { describe, expect, it } from 'vitest'

import {
  codePointLength,
  getConversationErrorMessage,
  getSendErrorMessage,
  isUncertainSendError,
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
