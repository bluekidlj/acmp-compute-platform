import { USE_MOCK, callApi, apiClient } from './client';
import type { Project } from '../types';
import { mockProjects, mockWorkspaces } from '../mock/data';

export const projectsApi = {
  listByWorkspace: async (workspaceId: string): Promise<Project[]> => {
    if (USE_MOCK) return mockProjects.filter((p) => p.workspaceId === workspaceId);
    return callApi(() => apiClient.get<Project[]>(`/workspaces/${workspaceId}/projects`));
  },
  get: async (id: string): Promise<Project> => {
    if (USE_MOCK) {
      const p = mockProjects.find((x) => x.id === id);
      if (!p) throw new Error('项目不存在');
      return p;
    }
    return callApi(() => apiClient.get<Project>(`/projects/${id}`));
  },
  create: async (workspaceId: string, req: { name: string; description?: string; memberIds?: string[] }): Promise<Project> => {
    if (USE_MOCK) {
      const id = 'proj-' + Date.now();
      const p: Project = {
        id, workspaceId,
        name: req.name, description: req.description ?? null,
        createdBy: 'user-admin', status: 'active',
        memberIds: req.memberIds ?? [],
        quotaByPoolType: {},
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockProjects.push(p);
      return p;
    }
    return callApi(() => apiClient.post<Project>(`/workspaces/${workspaceId}/projects`, req));
  },
  update: async (id: string, req: { name?: string; description?: string }): Promise<Project> => {
    if (USE_MOCK) {
      const p = mockProjects.find((x) => x.id === id);
      if (!p) throw new Error('项目不存在');
      if (req.name) p.name = req.name;
      if (req.description !== undefined) p.description = req.description;
      p.updatedAt = new Date().toISOString();
      return p;
    }
    return callApi(() => apiClient.put<Project>(`/projects/${id}`, req));
  },
  remove: async (id: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockProjects.findIndex((x) => x.id === id);
      if (idx >= 0) mockProjects.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/projects/${id}`));
  },
};