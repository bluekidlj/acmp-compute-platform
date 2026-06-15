import { mockResponse } from './index';
import { mockPools } from './data';
import type {
  ResourcePool,
  ResourcePoolCreateRequest,
  ResourcePoolCapacityPatch,
  IssueCredentialRequest,
  IssueCredentialResponse,
} from '../types';

export const mockResourcePoolApi = {
  list: (_physicalClusterId?: string) => mockResponse<ResourcePool[]>(mockPools),

  get: (id: string) => {
    const pool = mockPools.find((p) => p.id === id);
    return pool
      ? mockResponse<ResourcePool>(pool)
      : Promise.reject({ response: { status: 404, data: { message: '资源池不存在' } } });
  },

  create: async (data: ResourcePoolCreateRequest) => {
    const newPool: ResourcePool = {
      id: 'pool-' + Math.random().toString(36).slice(2, 10),
      name: data.name,
      description: data.description,
      departmentCode: data.departmentCode,
      departmentName: data.departmentName,
      status: 'active',
      physicalClusterIds: data.physicalClusterIds,
      specQuotas: (data.specQuotas || []).map((q) => ({
        specId: 'spec-' + q.specName,
        specName: q.specName,
        totalNodes: q.totalQuota,
        allocatedNodes: 0,
        availableNodes: q.totalQuota,
      })),
      createdAt: new Date().toISOString(),
    };
    mockPools.push(newPool);
    return mockResponse<ResourcePool>(newPool);
  },

  patchCapacity: (id: string, _data: ResourcePoolCapacityPatch) => {
    const pool = mockPools.find((p) => p.id === id);
    return pool
      ? mockResponse<ResourcePool>(pool)
      : Promise.reject({ response: { status: 404, data: { message: '资源池不存在' } } });
  },

  issueCredential: (_poolId: string, data: IssueCredentialRequest) => {
    return mockResponse<IssueCredentialResponse>({
      kubeconfig: `apiVersion: v1\nkind: Config\nclusters:\n- cluster:\n    server: https://k8s-mock.example.com\n  name: mock-cluster\nusers:\n- name: ${data.username}\n`,
      namespace: 'ws-llm-training-a1b2c3d4',
      clusterName: 'beijing-nvidia-01',
      serviceAccountName: 'sa-ws-llm-training-a1b2c3d4',
      message: `凭证已生成，有效期 ${data.expireDays} 天，用户: ${data.username}`,
    });
  },
};
