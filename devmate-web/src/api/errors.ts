import axios from 'axios'

interface ErrorPayload {
  message?: unknown
}

export function getErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError<ErrorPayload>(error)) {
    return fallback
  }

  const status = error.response?.status
  const message = error.response?.data?.message
  if (status !== undefined && status < 500 && typeof message === 'string' && message.trim()) {
    return message
  }
  return fallback
}
