import { USE_MOCK, callApi, apiClient } from './client';
import type { ModelDeployment, ModelDeploymentRequest } from '../types';
import { mockDeployments } from '../mock/data';

export const deploymentsApi = {
  listByProject: async (projectId: string): Promise<ModelDeployment[]> => {
    if (USE_MOCK) return mockDeployments.filter((d) => d.projectId === projectId);
    return callApi(() => apiClient.get<ModelDeployment[]>(`/projects/${projectId}/deployments`));
  },
  get: async (projectId: string, deploymentId: string): Promise<ModelDeployment> => {
    if (USE_MOCK) {
      const d = mockDeployments.find((x) => x.id === deploymentId);
      if (!d) throw new Error('部署不存在');
      return d;
    }
    return callApi(() => apiClient.get<ModelDeployment>(`/projects/${projectId}/deployments/${deploymentId}`));
  },
  create: async (projectId: string, req: ModelDeploymentRequest): Promise<ModelDeployment> => {
    if (USE_MOCK) {
      const id = 'dep-' + Date.now();
      const isOversell = req.specName.includes('oversell');
      const d: ModelDeployment = {
        id, projectId, workspaceId: 'ws-ai-rd',
        resourcePoolId: isOversell ? 'pool-ai-rd-oversell' : 'pool-ai-rd-shared',
        specId: 'spec-shared-a100-14',
        poolType: isOversell ? 'OVERSELL' : 'SHARED',
        name: req.name,
        modelName: req.modelName ?? null,
        modelSource: req.modelSource ?? null,
        modelIdOrPath: req.modelIdOrPath ?? null,
        vllmImage: req.image ?? null,
        gpuPerReplica: 1, gpumemMb: 20480, gpucores: 25,
        replicas: req.replicas,
        k8sDeploymentName: 'vllm-' + req.name,
        k8sServiceName: 'vllm-' + req.name + '-svc',
        status: 'running',
        serviceUrl: isOversell ? null : `http://vllm-${req.name}-svc.ws-ai-rd-1a2b3c4d.svc.cluster.local:8000`,
        readyReplicas: 1, actualClusterId: 'cluster-bj-01', poolCardId: 'pcard-a100-1',
        resourceKey: 'platform.io/shared-hami-a100-1-4',
        createdBy: 'user-admin',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockDeployments.push(d);
      return d;
    }
    return callApi(() => apiClient.post<ModelDeployment>(`/projects/${projectId}/deployments`, req));
  },
  remove: async (projectId: string, deploymentId: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockDeployments.findIndex((x) => x.id === deploymentId);
      if (idx >= 0) mockDeployments.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/projects/${projectId}/deployments/${deploymentId}`));
  },
};