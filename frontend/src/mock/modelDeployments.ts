import { mockResponse } from './index';
import { mockDeployments } from './data';
import type { ModelDeployment, VllmDeployRequest } from '../types';

export const mockModelDeploymentApi = {
  deploy: async (_poolId: string, workspaceId: string, data: VllmDeployRequest) => {
    const newDep: ModelDeployment = {
      id: 'dep-' + Math.random().toString(36).slice(2, 10),
      workspaceId,
      resourcePoolId: _poolId,
      specId: 'spec-' + data.specName,
      name: data.name,
      modelName: data.modelName,
      modelSource: data.modelSource,
      modelIdOrPath: data.modelIdOrPath || '/models',
      vllmImage: data.vllmImage || 'vllm/vllm-openai:latest',
      gpuPerReplica: 1,
      replicas: data.replicas,
      k8sDeploymentName: 'vllm-' + data.name.toLowerCase().replace(/[^a-z0-9-]/g, '-'),
      k8sServiceName: 'vllm-' + data.name.toLowerCase().replace(/[^a-z0-9-]/g, '-') + '-svc',
      status: 'running',
      serviceUrl: 'http://vllm-' + data.name.toLowerCase().replace(/[^a-z0-9-]/g, '-') +
        '-svc.ws-llm-training-a1b2c3d4.svc.cluster.local:8000',
      readyReplicas: data.replicas,
      createdBy: 'current-user',
      createdAt: new Date().toISOString(),
    };
    mockDeployments.push(newDep);
    return mockResponse<ModelDeployment>(newDep, 600);
  },

  list: (workspaceId: string) => {
    const deps = mockDeployments.filter((d) => d.workspaceId === workspaceId);
    return mockResponse<ModelDeployment[]>(deps);
  },

  get: (_workspaceId: string, id: string) => {
    const dep = mockDeployments.find((d) => d.id === id);
    return dep
      ? mockResponse<ModelDeployment>(dep)
      : Promise.reject({ response: { status: 404, data: { message: '部署记录不存在' } } });
  },

  delete: (_workspaceId: string, id: string) => {
    const idx = mockDeployments.findIndex((d) => d.id === id);
    if (idx >= 0) mockDeployments.splice(idx, 1);
    return mockResponse<{ message: string }>({ message: '已删除部署，配额已归还' });
  },
};
