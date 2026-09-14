import { describe, expect, it } from 'vitest'

import {
  formatProjectDate,
  getProjectErrorMessage,
  normalizeProjectPagination,
  parseProjectId,
} from '@/utils/projects'

describe('project utilities', () => {
  it('accepts only positive JavaScript-safe project identifiers', () => {
    expect(parseProjectId('42')).toBe(42)
    expect(parseProjectId('0')).toBeNull()
    expect(parseProjectId('-1')).toBeNull()
    expect(parseProjectId('1.5')).toBeNull()
    expect(parseProjectId('9007199254740992')).toBeNull()
  })

  it('normalizes pagination to backend and UI boundaries', () => {
    expect(normalizeProjectPagination('2', '50')).toEqual({
      page: 2,
      pageSize: 50,
      canonical: true,
    })
    expect(normalizeProjectPagination('0', '99')).toEqual({
      page: 1,
      pageSize: 20,
      canonical: false,
    })
    expect(normalizeProjectPagination(undefined, undefined)).toEqual({
      page: 1,
      pageSize: 20,
      canonical: false,
    })
    expect(normalizeProjectPagination(['1'], ['20']).canonical).toBe(false)
  })

  it('does not throw when a project timestamp is invalid', () => {
    expect(formatProjectDate('not-a-date')).toBe('--')
  })

  it('uses safe status-specific errors and hides server failures', () => {
    expect(
      getProjectErrorMessage({ isAxiosError: true, response: { status: 404 } }, 'fallback'),
    ).toBe('项目不存在或无权访问')
    expect(
      getProjectErrorMessage({ isAxiosError: true, response: { status: 403 } }, 'fallback'),
    ).toBe('无权执行此操作')
    expect(
      getProjectErrorMessage(
        { isAxiosError: true, response: { status: 500, data: { message: 'stack trace' } } },
        'fallback',
      ),
    ).toBe('fallback')
  })
})
