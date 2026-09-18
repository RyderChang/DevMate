import ElementPlus, { ElMessageBox } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as projectApi from '@/api/projects'
import type { PageResult, Project } from '@/api/types'
import ProjectListView from '@/views/ProjectListView.vue'

vi.mock('@/api/projects')

const project: Project = {
  id: 42,
  name: '<img src=x onerror=alert(1)>',
  description: '<strong>plain text</strong>',
  createTime: '2026-09-12T00:00:00Z',
  updateTime: '2026-09-13T00:00:00Z',
}

function result(items: Project[] = [project], page = 1): PageResult<Project> {
  return { page, pageSize: 20, total: items.length, items }
}

beforeEach(() => {
  vi.clearAllMocks()
})

async function mountList(path = '/projects?page=1&pageSize=20') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', name: 'project-list', component: ProjectListView },
      { path: '/projects/new', name: 'project-create', component: { template: '<div />' } },
      { path: '/projects/:projectId', name: 'project-detail', component: { template: '<div />' } },
      {
        path: '/projects/:projectId/edit',
        name: 'project-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ProjectListView, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { router, wrapper }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ProjectListView', () => {
  it('renders returned project content as text and shows pagination', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue(result())

    const { wrapper } = await mountList()

    expect(wrapper.text()).toContain(project.name)
    expect(wrapper.text()).toContain(project.description)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('共 1 个项目')
  })

  it('shows a loading state until the first request resolves', async () => {
    let resolveList!: (value: PageResult<Project>) => void
    vi.mocked(projectApi.listProjects).mockImplementation(
      () => new Promise((resolve) => (resolveList = resolve)),
    )

    const { wrapper } = await mountList()
    expect(wrapper.find('.el-skeleton').exists()).toBe(true)

    resolveList(result())
    await flushPromises()
    expect(wrapper.text()).toContain(project.name)
  })

  it('shows the empty state when the user has no projects', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue(result([]))

    const { wrapper } = await mountList()

    expect(wrapper.text()).toContain('还没有项目')
    expect(wrapper.text()).toContain('创建第一个项目')
  })

  it('shows a retryable initial error and preserves the recovered result', async () => {
    vi.mocked(projectApi.listProjects)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(result())

    const { wrapper } = await mountList()
    expect(wrapper.text()).toContain('无法加载项目')

    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试'))
    await retry?.trigger('click')
    await flushPromises()

    expect(projectApi.listProjects).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(project.name)
  })

  it('canonicalizes invalid query values before requesting data', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue(result())

    const { router } = await mountList('/projects?page=0&pageSize=99')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({ page: '1', pageSize: '20' })
    expect(projectApi.listProjects).toHaveBeenCalledWith({ page: 1, pageSize: 20 })
  })

  it('writes page and page-size changes to the URL', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue(result())
    const { router, wrapper } = await mountList()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })

    pagination.vm.$emit('update:current-page', 2)
    await flushPromises()
    expect(router.currentRoute.value.query.page).toBe('2')

    pagination.vm.$emit('update:page-size', 50)
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ page: '1', pageSize: '50' })
  })

  it('does not delete when confirmation is cancelled', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue(result())
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const { wrapper } = await mountList()

    await wrapper.find(`button[aria-label="删除项目 ${project.name}"]`).trigger('click')
    await flushPromises()

    expect(projectApi.deleteProject).not.toHaveBeenCalled()
  })

  it('keeps the project visible when delete fails', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue(result())
    vi.mocked(projectApi.deleteProject).mockRejectedValue(new Error('offline'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
    )
    const { wrapper } = await mountList()

    await wrapper.find(`button[aria-label="删除项目 ${project.name}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('删除项目失败，请稍后重试')
    expect(wrapper.text()).toContain(project.name)
  })

  it('allows only one delete request while an operation is pending', async () => {
    let resolveDelete!: () => void
    vi.mocked(projectApi.listProjects).mockResolvedValue(result())
    vi.mocked(projectApi.deleteProject).mockImplementation(
      () => new Promise<void>((resolve) => (resolveDelete = resolve)),
    )
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
    )
    const { wrapper } = await mountList()
    const remove = wrapper.find(`button[aria-label="删除项目 ${project.name}"]`)

    await remove.trigger('click')
    await remove.trigger('click')
    await flushPromises()
    expect(projectApi.deleteProject).toHaveBeenCalledTimes(1)

    resolveDelete()
    await flushPromises()
  })

  it('does not let an old delete result change a newer list route', async () => {
    let resolveDelete!: () => void
    const newerProject = { ...project, id: 43, name: 'Newer project' }
    vi.mocked(projectApi.listProjects).mockImplementation(async ({ page }) =>
      result([page === 1 ? project : newerProject], page),
    )
    vi.mocked(projectApi.deleteProject).mockImplementation(
      () => new Promise<void>((resolve) => (resolveDelete = resolve)),
    )
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
    )
    const { router, wrapper } = await mountList()

    await wrapper.find(`button[aria-label="删除项目 ${project.name}"]`).trigger('click')
    await flushPromises()
    await router.push('/projects?page=2&pageSize=20')
    await flushPromises()

    resolveDelete()
    await flushPromises()

    expect(router.currentRoute.value.query.page).toBe('2')
    expect(wrapper.text()).toContain(newerProject.name)
    expect(wrapper.text()).not.toContain(project.name)
  })

  it('moves to the previous page after deleting its last item', async () => {
    vi.mocked(projectApi.listProjects).mockImplementation(async ({ page }) =>
      result([project], page),
    )
    vi.mocked(projectApi.deleteProject).mockResolvedValue()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
      'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
    )
    const { router, wrapper } = await mountList('/projects?page=2&pageSize=20')

    await wrapper.find(`button[aria-label="删除项目 ${project.name}"]`).trigger('click')
    await flushPromises()

    expect(projectApi.deleteProject).toHaveBeenCalledWith(project.id)
    expect(router.currentRoute.value.query.page).toBe('1')
  })
})
