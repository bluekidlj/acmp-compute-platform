import { USE_MOCK, callApi, apiClient } from './client';
import type { Workspace } from '../types';
import { mockWorkspaces } from '../mock/data';

export const workspacesApi = {
  list: async (): Promise<Workspace[]> => {
    if (USE_MOCK) return mockWorkspaces;
    return callApi(() => apiClient.get<Workspace[]>('/workspaces'));
  },
  get: async (id: string): Promise<Workspace> => {
    if (USE_MOCK) {
      const w = mockWorkspaces.find((x) => x.id === id);
      if (!w) throw new Error('工作空间不存在');
      return w;
    }
    return callApi(() => apiClient.get<Workspace>(`/workspaces/${id}`));
  },
  create: async (req: { name: string; description?: string; clusterId: string; memberIds?: string[]; maxPods?: number }): Promise<Workspace> => {
    if (USE_MOCK) {
      const id = 'ws-' + Date.now();
      const shortId = Math.random().toString(16).slice(2, 10);
      const w: Workspace = {
        id, name: req.name, description: req.description ?? null,
        primaryClusterId: req.clusterId, primaryClusterName: '北京生产 K8s 集群',
        namespace: `ws-${req.name}-${shortId}`,
        serviceAccountName: `sa-ws-${req.name}-${shortId}`,
        volcanoQueueName: `queue-ws-${req.name}-${shortId}`,
        maxPods: req.maxPods ?? 50,
        createdBy: 'user-admin', status: 'active',
        pools: [
          { id: `pool-${id}-ex`, poolType: 'EXCLUSIVE', name: `${req.name}-exclusive`, description: null, totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
          { id: `pool-${id}-sh`, poolType: 'SHARED', name: `${req.name}-shared`, description: null, totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
          { id: `pool-${id}-ov`, poolType: 'OVERSELL', name: `${req.name}-oversell`, description: null, totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
        ],
        memberIds: req.memberIds ?? [],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockWorkspaces.push(w);
      return w;
    }
    return callApi(() => apiClient.post<Workspace>('/workspaces', req));
  },
  update: async (id: string, req: { name?: string; description?: string; maxPods?: number }): Promise<Workspace> => {
    if (USE_MOCK) {
      const w = mockWorkspaces.find((x) => x.id === id);
      if (!w) throw new Error('工作空间不存在');
      if (req.name) w.name = req.name;
      if (req.description !== undefined) w.description = req.description;
      if (req.maxPods) w.maxPods = req.maxPods;
      w.updatedAt = new Date().toISOString();
      return w;
    }
    return callApi(() => apiClient.put<Workspace>(`/workspaces/${id}`, req));
  },
  remove: async (id: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockWorkspaces.findIndex((x) => x.id === id);
      if (idx >= 0) mockWorkspaces.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/workspaces/${id}`));
  },
  addMember: async (wsId: string, userId: string): Promise<void> => {
    if (USE_MOCK) {
      const w = mockWorkspaces.find((x) => x.id === wsId);
      if (!w) throw new Error('工作空间不存在');
      if (!w.memberIds.includes(userId)) w.memberIds.push(userId);
      return;
    }
    return callApi(() => apiClient.post<void>(`/workspaces/${wsId}/members`, { userId }));
  },
  removeMember: async (wsId: string, userId: string): Promise<void> => {
    if (USE_MOCK) {
      const w = mockWorkspaces.find((x) => x.id === wsId);
      if (!w) throw new Error('工作空间不存在');
      w.memberIds = w.memberIds.filter((u) => u !== userId);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/workspaces/${wsId}/members/${userId}`));
  },
  listMembers: async (wsId: string): Promise<string[]> => {
    if (USE_MOCK) {
      const w = mockWorkspaces.find((x) => x.id === wsId);
      return w?.memberIds ?? [];
    }
    return callApi(() => apiClient.get<string[]>(`/workspaces/${wsId}/members`));
  },
};