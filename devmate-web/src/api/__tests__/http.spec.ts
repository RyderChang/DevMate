import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import http, { setUnauthorizedHandler } from '@/api/http'

const originalAdapter = http.defaults.adapter

function response(config: InternalAxiosRequestConfig, status = 200): AxiosResponse {
  return {
    data: {},
    status,
    statusText: status === 200 ? 'OK' : 'Unauthorized',
    headers: new AxiosHeaders(),
    config,
  }
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
  setUnauthorizedHandler(undefined)
})

describe('http authentication interceptors', () => {
  it('adds the session token as a bearer authorization header', async () => {
    window.sessionStorage.setItem('devmate.accessToken', 'signed-token')
    const adapter = vi.fn<AxiosAdapter>(async (config) => response(config))
    http.defaults.adapter = adapter

    await http.get('/probe')

    const config = adapter.mock.calls[0]?.[0]
    expect(config?.headers.Authorization).toBe('Bearer signed-token')
  })

  it('clears the token and notifies the app after a 401 response', async () => {
    window.sessionStorage.setItem('devmate.accessToken', 'expired-token')
    const unauthorized = vi.fn()
    setUnauthorizedHandler(unauthorized)
    http.defaults.adapter = async (config) => {
      const unauthorizedResponse = response(config, 401)
      throw new AxiosError(
        'Unauthorized',
        'ERR_BAD_REQUEST',
        config,
        undefined,
        unauthorizedResponse,
      )
    }

    await expect(http.get('/protected')).rejects.toBeInstanceOf(AxiosError)

    expect(window.sessionStorage.getItem('devmate.accessToken')).toBeNull()
    expect(unauthorized).toHaveBeenCalledOnce()
  })
})
