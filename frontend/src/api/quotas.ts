import { USE_MOCK, callApi, apiClient } from './client';
import type { ProjectQuota, ProjectQuotaRequest } from '../types';
import { mockQuotas } from '../mock/data';

export const quotasApi = {
  listByProject: async (projectId: string): Promise<ProjectQuota[]> => {
    if (USE_MOCK) return mockQuotas.filter((q) => q.projectId === projectId);
    return callApi(() => apiClient.get<ProjectQuota[]>(`/projects/${projectId}/quotas`));
  },
  allocate: async (projectId: string, req: ProjectQuotaRequest): Promise<ProjectQuota> => {
    if (USE_MOCK) {
      const id = 'prq-' + Date.now();
      const q: ProjectQuota = {
        id,
        projectId,
        poolId: req.poolId,
        specId: req.specId,
        totalNodes: req.totalNodes,
        usedNodes: 0,
        availableNodes: req.totalNodes,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockQuotas.push(q);
      return q;
    }
    return callApi(() => apiClient.post<ProjectQuota>(`/projects/${projectId}/quotas`, req));
  },
  update: async (projectId: string, quotaId: string, totalNodes: number): Promise<ProjectQuota> => {
    if (USE_MOCK) {
      const q = mockQuotas.find((x) => x.id === quotaId);
      if (!q) throw new Error('配额不存在');
      q.totalNodes = totalNodes;
      q.availableNodes = totalNodes - q.usedNodes;
      q.updatedAt = new Date().toISOString();
      return q;
    }
    return callApi(() => apiClient.patch<ProjectQuota>(`/projects/${projectId}/quotas/${quotaId}`, { totalNodes }));
  },
  remove: async (projectId: string, quotaId: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockQuotas.findIndex((x) => x.id === quotaId);
      if (idx >= 0) mockQuotas.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/projects/${projectId}/quotas/${quotaId}`));
  },
};