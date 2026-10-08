import axios from 'axios'

import { getErrorMessage } from '@/api/errors'

export const DEFAULT_CONVERSATION_PAGE = 1
export const DEFAULT_CONVERSATION_PAGE_SIZE = 20
export const CONVERSATION_PAGE_SIZES = [10, 20, 50, 100] as const
export const MAX_CONVERSATION_PAGE = 10_000
export const MAX_CONVERSATION_TITLE_LENGTH = 200
export const MAX_MESSAGE_LENGTH = 8_000
export const CONVERSATION_UNAVAILABLE_MESSAGE = '对话不存在或无权访问'

export function parsePositiveSafeId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) {
    return null
  }
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}

function parseInteger(value: unknown, fallback: number, maximum: number): number {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) {
    return fallback
  }
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed <= maximum ? parsed : fallback
}

export function normalizeConversationPagination(pageValue: unknown, pageSizeValue: unknown) {
  const page = parseInteger(pageValue, DEFAULT_CONVERSATION_PAGE, MAX_CONVERSATION_PAGE)
  const parsedPageSize = parseInteger(pageSizeValue, DEFAULT_CONVERSATION_PAGE_SIZE, 100)
  const pageSize = CONVERSATION_PAGE_SIZES.includes(
    parsedPageSize as (typeof CONVERSATION_PAGE_SIZES)[number],
  )
    ? parsedPageSize
    : DEFAULT_CONVERSATION_PAGE_SIZE

  return {
    page,
    pageSize,
    canonical:
      pageValue === String(page) &&
      typeof pageValue === 'string' &&
      pageSizeValue === String(pageSize) &&
      typeof pageSizeValue === 'string',
  }
}

export function codePointLength(value: string): number {
  return Array.from(value).length
}

export function hasUnpairedSurrogate(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      return true
    }
  }
  return false
}

export function isValidRagContent(value: string): boolean {
  return value.length > 0 && value.length <= 8_000 && !hasUnpairedSurrogate(value)
}

export type RagSendFailure = {
  message: string
  uncertain: boolean
  inProgress: boolean
  unavailable: boolean
}

/** Match the server's fixed message together with HTTP status; never treat every 409 as in progress. */
export function classifyRagSendFailure(error: unknown): RagSendFailure {
  if (!axios.isAxiosError(error)) {
    return {
      message: '结果无法确认，请使用同一请求确认',
      uncertain: true,
      inProgress: false,
      unavailable: false,
    }
  }
  const status = error.response?.status
  const message = error.response?.data?.message
  if (status === undefined || (status === 503 && message === 'RAG state cannot be confirmed')) {
    return {
      message: '请求状态不确定，请使用同一请求确认结果',
      uncertain: true,
      inProgress: false,
      unavailable: false,
    }
  }
  if (status === 409 && message === 'An AI response is already being generated') {
    return {
      message: '已有回复正在生成，请稍后用同一请求确认结果',
      uncertain: true,
      inProgress: true,
      unavailable: false,
    }
  }
  const known: Record<string, string> = {
    'No bounded document context is available': '没有可用于回答的文档片段，请检查项目文档与索引',
    'Document retrieval is incomplete': '文档检索未完成，本次没有生成回答',
    'Document sources are no longer eligible': '引用来源已变化，本次没有生成回答',
    'Request mode or content conflicts': '请求编号与模式或内容冲突，请重新提交',
    'RAG record capacity exceeded': '文档问答记录额度已满',
    'RAG conversation is disabled': '服务端尚未启用文档问答',
    'Document retrieval is disabled': '服务端尚未启用文档检索',
    'Document retrieval could not be confirmed': '文档检索状态无法确认，本次请求已结束',
    'Document retrieval capacity exceeded': '文档检索额度已满',
  }
  const translated = typeof message === 'string' ? known[message] : undefined
  return {
    message: translated ?? getSendErrorMessage(error),
    uncertain: false,
    inProgress: false,
    unavailable: status === 404,
  }
}

export function isConversationUnavailableError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404
}

export function getConversationErrorMessage(error: unknown, fallback: string): string {
  if (isConversationUnavailableError(error)) {
    return CONVERSATION_UNAVAILABLE_MESSAGE
  }
  if (axios.isAxiosError(error) && error.response?.status === 403) {
    return '无权访问此项目或对话'
  }
  return getErrorMessage(error, fallback)
}

export function getSendErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return '发送失败，请检查内容后重试'
  }
  switch (error.response?.status) {
    case 400:
      return '消息内容无效，请检查后重试'
    case 403:
      return '无权访问此项目或对话'
    case 404:
      return CONVERSATION_UNAVAILABLE_MESSAGE
    case 409:
      return '已有回复正在生成，请稍后用同一请求确认结果'
    case 502:
    case 503:
      return 'AI 服务暂时不可用，请稍后重试'
    case 504:
      return 'AI 回复超时，请稍后重试'
    default:
      return error.response ? '发送失败，请稍后重试' : '网络状态不确定，请使用同一请求确认结果'
  }
}

export function isUncertainSendError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response === undefined
}
