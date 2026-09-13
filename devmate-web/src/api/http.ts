import axios from 'axios'

import { clearAccessToken, getAccessToken } from '@/utils/session'

let unauthorizedHandler: (() => void) | undefined

export function setUnauthorizedHandler(handler: (() => void) | undefined): void {
  unauthorizedHandler = handler
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10_000,
})

http.interceptors.request.use(
  (config) => {
    const token = getAccessToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error: unknown) => Promise.reject(error),
)

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      clearAccessToken()
      unauthorizedHandler?.()
    }
    return Promise.reject(error)
  },
)

export default http
