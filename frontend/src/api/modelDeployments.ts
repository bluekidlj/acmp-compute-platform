import apiClient from './client';
import type { ModelDeployment, VllmDeployRequest } from '../types';
import { USE_MOCK } from '../mock';
import { mockModelDeploymentApi } from '../mock/modelDeployments';

const realApi = {
  deploy: (poolId: string, workspaceId: string, data: VllmDeployRequest) =>
    apiClient.post<ModelDeployment>(
      `/resource-pools/${poolId}/workspaces/${workspaceId}/model-deployments`,
      data,
    ),
  list: (workspaceId: string) =>
    apiClient.get<ModelDeployment[]>(`/workspaces/${workspaceId}/model-deployments`),
  get: (workspaceId: string, id: string) =>
    apiClient.get<ModelDeployment>(`/workspaces/${workspaceId}/model-deployments/${id}`),
  delete: (workspaceId: string, id: string) =>
    apiClient.delete<{ message: string }>(`/workspaces/${workspaceId}/model-deployments/${id}`),
};

export const modelDeploymentApi = USE_MOCK ? mockModelDeploymentApi : realApi;
