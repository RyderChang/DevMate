import http from '@/api/http'
import type { ApiResult, PageResult, Project, ProjectMutationRequest } from '@/api/types'

export interface ProjectListParams {
  page: number
  pageSize: number
}

function projectPath(projectId: number): string {
  if (!Number.isSafeInteger(projectId) || projectId <= 0) {
    throw new RangeError('Project id must be a positive safe integer')
  }
  return `/projects/${projectId}`
}

export async function listProjects(params: ProjectListParams): Promise<PageResult<Project>> {
  const response = await http.get<ApiResult<PageResult<Project>>>('/projects', { params })
  return response.data.data
}

export async function createProject(request: ProjectMutationRequest): Promise<Project> {
  const response = await http.post<ApiResult<Project>>('/projects', request)
  return response.data.data
}

export async function getProject(projectId: number): Promise<Project> {
  const response = await http.get<ApiResult<Project>>(projectPath(projectId))
  return response.data.data
}

export async function updateProject(
  projectId: number,
  request: ProjectMutationRequest,
): Promise<Project> {
  const response = await http.put<ApiResult<Project>>(projectPath(projectId), request)
  return response.data.data
}

export async function deleteProject(projectId: number): Promise<void> {
  await http.delete<ApiResult<null>>(projectPath(projectId))
}
