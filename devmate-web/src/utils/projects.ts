import axios from 'axios'

import { getErrorMessage } from '@/api/errors'

export const DEFAULT_PROJECT_PAGE = 1
export const DEFAULT_PROJECT_PAGE_SIZE = 20
export const MAX_PROJECT_PAGE = 10_000
export const PROJECT_PAGE_SIZES = [10, 20, 50, 100] as const
export const PROJECT_UNAVAILABLE_MESSAGE = '项目不存在或无权访问'

export function parseProjectId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) {
    return null
  }
  const projectId = Number(value)
  return Number.isSafeInteger(projectId) ? projectId : null
}

function parseInteger(value: unknown, fallback: number, maximum: number): number {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) {
    return fallback
  }
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed <= maximum ? parsed : fallback
}

export function normalizeProjectPagination(pageValue: unknown, pageSizeValue: unknown) {
  const page = parseInteger(pageValue, DEFAULT_PROJECT_PAGE, MAX_PROJECT_PAGE)
  const parsedPageSize = parseInteger(pageSizeValue, DEFAULT_PROJECT_PAGE_SIZE, 100)
  const pageSize = PROJECT_PAGE_SIZES.includes(
    parsedPageSize as (typeof PROJECT_PAGE_SIZES)[number],
  )
    ? parsedPageSize
    : DEFAULT_PROJECT_PAGE_SIZE

  return {
    page,
    pageSize,
    canonical:
      typeof pageValue === 'string' &&
      pageValue === String(page) &&
      typeof pageSizeValue === 'string' &&
      pageSizeValue === String(pageSize),
  }
}

export function isProjectUnavailableError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404
}

export function getProjectErrorMessage(error: unknown, fallback: string): string {
  if (isProjectUnavailableError(error)) {
    return PROJECT_UNAVAILABLE_MESSAGE
  }
  if (axios.isAxiosError(error) && error.response?.status === 403) {
    return '无权执行此操作'
  }
  return getErrorMessage(error, fallback)
}

export function formatProjectDate(value: string): string {
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) {
    return '--'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(timestamp)
}
