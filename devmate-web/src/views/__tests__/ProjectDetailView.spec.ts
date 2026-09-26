import ElementPlus, { ElMessageBox } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as projectApi from '@/api/projects'
import type { Project } from '@/api/types'
import ProjectDetailView from '@/views/ProjectDetailView.vue'

vi.mock('@/api/projects')

const project: Project = {
  id: 42,
  name: '<script>alert(1)</script>',
  description: '<b>safe text</b>',
  createTime: 'invalid',
  updateTime: '2026-09-13T00:00:00Z',
}

const notFound = {
  isAxiosError: true,
  response: { status: 404, data: { message: 'Project not found' } },
}

beforeEach(() => {
  vi.clearAllMocks()
})

async function mountDetail(path = '/projects/42') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', name: 'project-list', component: { template: '<div />' } },
      {
        path: '/projects/:projectId',
        name: 'project-detail',
        component: ProjectDetailView,
      },
      {
        path: '/projects/:projectId/edit',
        name: 'project-edit',
        component: { template: '<div />' },
      },
      {
        path: '/projects/:projectId/conversations',
        name: 'conversation-list',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { router, wrapper }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ProjectDetailView', () => {
  it('shows project fields as text and tolerates an invalid timestamp', async () => {
    vi.mocked(projectApi.getProject).mockResolvedValue(project)

    const { wrapper } = await mountDetail()

    expect(wrapper.text()).toContain(project.name)
    expect(wrapper.text()).toContain(project.description)
    expect(wrapper.text()).toContain('--')
    expect(wrapper.find('script').exists()).toBe(false)
  })

  it('opens the current project conversation list', async () => {
    vi.mocked(projectApi.getProject).mockResolvedValue(project)
    const { router, wrapper } = await mountDetail()

    const conversations = wrapper.findAll('button').find((button) => button.text() === '项目对话')
    await conversations?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('conversation-list')
    expect(router.currentRoute.value.params.projectId).toBe('42')
  })

  it('does not request an invalid or unsafe project id', async () => {
    const { wrapper } = await mountDetail('/projects/9007199254740992')

    expect(projectApi.getProject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('项目不存在或无权访问')
  })

  it('uses one unavailable state for a 404 response', async () => {
    vi.mocked(projectApi.getProject).mockRejectedValue(notFound)

    const { wrapper } = await mountDetail()

    expect(wrapper.text()).toContain('项目不存在或无权访问')
    expect(wrapper.text()).not.toContain('Project not found')
  })

  it('retries a transient loading failure', async () => {
    vi.mocked(projectApi.getProject)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(project)

    const { wrapper } = await mountDetail()
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试'))
    await retry?.trigger('click')
    await flushPromises()

    expect(projectApi.getProject).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(project.name)
  })

  it('ignores an older response after the route changes', async () => {
    let resolveOld!: (value: Project) => void
    const newerProject = { ...project, id: 43, name: 'Newer project' }
    vi.mocked(projectApi.getProject).mockImplementation((projectId) => {
      if (projectId === 42) {
        return new Promise((resolve) => (resolveOld = resolve))
      }
      return Promise.resolve(newerProject)
    })
    const { router, wrapper } = await mountDetail()

    await router.push('/projects/43')
    await flushPromises()
    expect(wrapper.text()).toContain(newerProject.name)

    resolveOld(project)
    await flushPromises()
    expect(wrapper.text()).toContain(newerProject.name)
    expect(wrapper.text()).not.toContain(project.name)
  })

  it('clears loading when a pending project route changes to an invalid id', async () => {
    vi.mocked(projectApi.getProject).mockImplementation(() => new Promise(() => undefined))
    const { router, wrapper } = await mountDetail()

    expect(wrapper.find('.el-skeleton').exists()).toBe(true)
    await router.push('/projects/not-a-number')
    await flushPromises()

    expect(wrapper.find('.el-skeleton').exists()).toBe(false)
    expect(wrapper.text()).toContain('项目不存在或无权访问')
  })

  it('does not call delete when the user cancels confirmation', async () => {
    vi.mocked(projectApi.getProject).mockResolvedValue(project)
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const { wrapper } = await mountDetail()

    const remove = wrapper.findAll('button').find((button) => button.text().includes('删除项目'))
    await remove?.trigger('click')
    await flushPromises()

    expect(projectApi.deleteProject).not.toHaveBeenCalled()
  })

  it('deletes after confirmation and returns to the list', async () => {
    vi.mocked(projectApi.getProject).mockResolvedValue(project)
    vi.mocked(projectApi.deleteProject).mockResolvedValue()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
    )
    const { router, wrapper } = await mountDetail()

    const remove = wrapper.findAll('button').find((button) => button.text().includes('删除项目'))
    await remove?.trigger('click')
    await flushPromises()

    expect(projectApi.deleteProject).toHaveBeenCalledWith(42)
    expect(router.currentRoute.value.name).toBe('project-list')
  })

  it('does not navigate from an old delete result after the route changes', async () => {
    let resolveDelete!: () => void
    const newerProject = { ...project, id: 43, name: 'Newer project' }
    vi.mocked(projectApi.getProject).mockImplementation((projectId) =>
      Promise.resolve(projectId === 42 ? project : newerProject),
    )
    vi.mocked(projectApi.deleteProject).mockImplementation(
      () => new Promise<void>((resolve) => (resolveDelete = resolve)),
    )
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
    )
    const { router, wrapper } = await mountDetail()

    const remove = wrapper.findAll('button').find((button) => button.text().includes('删除项目'))
    await remove?.trigger('click')
    await flushPromises()
    await router.push('/projects/43')
    await flushPromises()

    resolveDelete()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/projects/43')
    expect(wrapper.text()).toContain(newerProject.name)
    expect(wrapper.text()).not.toContain(project.name)
  })
})
