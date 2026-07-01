import { USE_MOCK, callApi, apiClient } from './client';
import type { ResourcePool } from '../types';
import { mockPools } from '../mock/data';

export const poolsApi = {
  listByWorkspace: async (workspaceId: string): Promise<ResourcePool[]> => {
    if (USE_MOCK) return mockPools.filter((p) => p.workspaceId === workspaceId);
    return callApi(() => apiClient.get<ResourcePool[]>(`/workspaces/${workspaceId}/pools`));
  },
  get: async (id: string): Promise<ResourcePool> => {
    if (USE_MOCK) {
      const p = mockPools.find((x) => x.id === id);
      if (!p) throw new Error('资源池不存在');
      return p;
    }
    return callApi(() => apiClient.get<ResourcePool>(`/pools/${id}`));
  },
  update: async (id: string, req: { specs: string[] }): Promise<ResourcePool> => {
    if (USE_MOCK) {
      const p = mockPools.find((x) => x.id === id);
      if (!p) throw new Error('资源池不存在');
      p.updatedAt = new Date().toISOString();
      return p;
    }
    return callApi(() => apiClient.patch<ResourcePool>(`/pools/${id}`, req));
  },
  remove: async (id: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockPools.findIndex((x) => x.id === id);
      if (idx >= 0) mockPools.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/pools/${id}`));
  },
};