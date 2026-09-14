import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as projectApi from '@/api/projects'
import type { Project } from '@/api/types'
import ProjectCreateView from '@/views/ProjectCreateView.vue'
import ProjectEditView from '@/views/ProjectEditView.vue'

vi.mock('@/api/projects')

const project: Project = {
  id: 42,
  name: 'Original project',
  description: 'Original description',
  createTime: '2026-09-12T00:00:00Z',
  updateTime: '2026-09-13T00:00:00Z',
}

beforeEach(() => {
  vi.clearAllMocks()
})

async function mountView(component: typeof ProjectCreateView, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', name: 'project-list', component: { template: '<div />' } },
      { path: '/projects/new', name: 'project-create', component: ProjectCreateView },
      {
        path: '/projects/:projectId',
        name: 'project-detail',
        component: { template: '<div />' },
      },
      { path: '/projects/:projectId/edit', name: 'project-edit', component },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { router, wrapper }
}

describe('project form views', () => {
  it('normalizes create fields and opens the new project detail', async () => {
    vi.mocked(projectApi.createProject).mockResolvedValue(project)
    const { router, wrapper } = await mountView(ProjectCreateView, '/projects/new')

    await wrapper.find('input[name="project-name"]').setValue('  New project  ')
    await wrapper.find('textarea.el-textarea__inner').setValue('  Description  ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(projectApi.createProject).toHaveBeenCalledWith({
      name: 'New project',
      description: 'Description',
    })
    expect(router.currentRoute.value).toMatchObject({
      name: 'project-detail',
      params: { projectId: '42' },
    })
  })

  it('validates the normalized project name before create', async () => {
    const { wrapper } = await mountView(ProjectCreateView, '/projects/new')

    await wrapper.find('input[name="project-name"]').setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(projectApi.createProject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入项目名称')
  })

  it('rejects normalized fields that exceed their length limits', async () => {
    const { wrapper } = await mountView(ProjectCreateView, '/projects/new')

    await wrapper.find('input[name="project-name"]').setValue(` ${'n'.repeat(101)} `)
    await wrapper.find('textarea.el-textarea__inner').setValue(` ${'d'.repeat(1001)} `)
    expect(wrapper.text()).toContain('101/100')
    expect(wrapper.text()).toContain('1001/1000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(projectApi.createProject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('项目名称不能超过 100 个字符')

    await wrapper.find('input[name="project-name"]').setValue('Valid name')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(projectApi.createProject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('项目描述不能超过 1000 个字符')
  })

  it('prevents duplicate create requests while submission is pending', async () => {
    let resolveCreate!: (value: Project) => void
    vi.mocked(projectApi.createProject).mockImplementation(
      () => new Promise((resolve) => (resolveCreate = resolve)),
    )
    const { wrapper } = await mountView(ProjectCreateView, '/projects/new')
    await wrapper.find('input[name="project-name"]').setValue('New project')

    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(projectApi.createProject).toHaveBeenCalledTimes(1)

    resolveCreate(project)
    await flushPromises()
  })

  it('does not navigate from an old create request after the page unmounts', async () => {
    let resolveCreate!: (value: Project) => void
    vi.mocked(projectApi.createProject).mockImplementation(
      () => new Promise((resolve) => (resolveCreate = resolve)),
    )
    const { router, wrapper } = await mountView(ProjectCreateView, '/projects/new')
    await wrapper.find('input[name="project-name"]').setValue('New project')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    wrapper.unmount()
    await router.push('/projects')
    resolveCreate(project)
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/projects')
  })

  it('loads current project data, updates normalized fields, and opens detail', async () => {
    vi.mocked(projectApi.getProject).mockResolvedValue(project)
    vi.mocked(projectApi.updateProject).mockResolvedValue({ ...project, name: 'Updated' })
    const { router, wrapper } = await mountView(ProjectEditView, '/projects/42/edit')

    expect((wrapper.find('input[name="project-name"]').element as HTMLInputElement).value).toBe(
      project.name,
    )
    await wrapper.find('input[name="project-name"]').setValue('  Updated  ')
    await wrapper.find('textarea.el-textarea__inner').setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(projectApi.updateProject).toHaveBeenCalledWith(42, {
      name: 'Updated',
      description: null,
    })
    expect(router.currentRoute.value.name).toBe('project-detail')
  })

  it('does not load an edit route with an invalid id', async () => {
    const { wrapper } = await mountView(ProjectEditView, '/projects/not-a-number/edit')

    expect(projectApi.getProject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('项目不存在或无权访问')
  })

  it('clears loading when a pending edit route changes to an invalid id', async () => {
    vi.mocked(projectApi.getProject).mockImplementation(() => new Promise(() => undefined))
    const { router, wrapper } = await mountView(ProjectEditView, '/projects/42/edit')

    expect(wrapper.find('.el-skeleton').exists()).toBe(true)
    await router.push('/projects/not-a-number/edit')
    await flushPromises()

    expect(wrapper.find('.el-skeleton').exists()).toBe(false)
    expect(wrapper.text()).toContain('项目不存在或无权访问')
  })

  it('ignores an update result after the edit route changes', async () => {
    let resolveUpdate!: (value: Project) => void
    const newerProject = { ...project, id: 43, name: 'Newer project' }
    vi.mocked(projectApi.getProject).mockImplementation((projectId) =>
      Promise.resolve(projectId === 42 ? project : newerProject),
    )
    vi.mocked(projectApi.updateProject).mockImplementation(
      () => new Promise((resolve) => (resolveUpdate = resolve)),
    )
    const { router, wrapper } = await mountView(ProjectEditView, '/projects/42/edit')

    await wrapper.find('input[name="project-name"]').setValue('Updated old project')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await router.push('/projects/43/edit')
    await flushPromises()

    resolveUpdate({ ...project, name: 'Updated old project' })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/projects/43/edit')
    expect((wrapper.find('input[name="project-name"]').element as HTMLInputElement).value).toBe(
      newerProject.name,
    )
  })
})
