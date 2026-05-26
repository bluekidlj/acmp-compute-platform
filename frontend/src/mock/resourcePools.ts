import { mockResponse } from './index';
import { mockPools } from './data';
import type { ResourcePool, ResourcePoolCreateRequest } from '../types';

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
        totalQuota: q.totalQuota,
        allocatedQuota: 0,
        availableQuota: q.totalQuota,
      })),
      createdAt: new Date().toISOString(),
    };
    mockPools.push(newPool);
    return mockResponse<ResourcePool>(newPool);
  },
};
