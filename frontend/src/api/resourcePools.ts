import apiClient from './client';
import type { ResourcePool, ResourcePoolCreateRequest } from '../types';
import { USE_MOCK } from '../mock';
import { mockResourcePoolApi } from '../mock/resourcePools';

const realApi = {
  list: (physicalClusterId?: string) => {
    const params = physicalClusterId ? { physicalClusterId } : {};
    return apiClient.get<ResourcePool[]>('/resource-pools', { params });
  },
  get: (id: string) => apiClient.get<ResourcePool>(`/resource-pools/${id}`),
  create: (data: ResourcePoolCreateRequest) =>
    apiClient.post<ResourcePool>('/admin/resource-pools', data),
};

export const resourcePoolApi = USE_MOCK ? mockResourcePoolApi : realApi;
