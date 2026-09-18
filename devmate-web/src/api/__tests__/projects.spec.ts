import { AxiosHeaders } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  createProject,
  deleteProject,
  getProject,
  listProjects,
  updateProject,
} from '@/api/projects'
import type { ApiResult, PageResult, Project } from '@/api/types'
import http from '@/api/http'

const originalAdapter = http.defaults.adapter

const project: Project = {
  id: 42,
  name: 'DevMate',
  description: null,
  createTime: '2026-09-13T00:00:00Z',
  updateTime: '2026-09-13T01:00:00Z',
}

function response<T>(config: InternalAxiosRequestConfig, data: ApiResult<T>): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config }
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('project api', () => {
  it('lists projects with explicit pagination parameters', async () => {
    const page: PageResult<Project> = { page: 2, pageSize: 20, total: 1, items: [project] }
    const adapter = vi.fn<AxiosAdapter>(async (config) =>
      response(config, { code: 200, message: 'success', data: page }),
    )
    http.defaults.adapter = adapter

    await expect(listProjects({ page: 2, pageSize: 20 })).resolves.toEqual(page)
    expect(adapter.mock.calls[0]?.[0].url).toBe('/projects')
    expect(adapter.mock.calls[0]?.[0].params).toEqual({ page: 2, pageSize: 20 })
  })

  it('uses the expected paths and safe mutation body for CRUD requests', async () => {
    const adapter = vi.fn<AxiosAdapter>(async (config) =>
      response(config, { code: 200, message: 'success', data: project }),
    )
    http.defaults.adapter = adapter
    const mutation = { name: 'DevMate', description: null }

    await createProject(mutation)
    await getProject(42)
    await updateProject(42, mutation)
    await deleteProject(42)

    expect(adapter.mock.calls.map(([config]) => [config.method, config.url])).toEqual([
      ['post', '/projects'],
      ['get', '/projects/42'],
      ['put', '/projects/42'],
      ['delete', '/projects/42'],
    ])
    expect(JSON.parse(String(adapter.mock.calls[0]?.[0].data))).toEqual(mutation)
    expect(JSON.parse(String(adapter.mock.calls[2]?.[0].data))).toEqual(mutation)
  })

  it('rejects an invalid project id before making a request', async () => {
    const adapter = vi.fn<AxiosAdapter>()
    http.defaults.adapter = adapter

    await expect(getProject(Number.MAX_SAFE_INTEGER + 1)).rejects.toBeInstanceOf(RangeError)
    await expect(updateProject(0, { name: 'invalid' })).rejects.toBeInstanceOf(RangeError)
    await expect(deleteProject(-1)).rejects.toBeInstanceOf(RangeError)
    expect(adapter).not.toHaveBeenCalled()
  })
})
