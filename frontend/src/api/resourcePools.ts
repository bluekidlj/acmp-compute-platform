import apiClient from './client';
import type { ResourcePool, ResourcePoolCreateRequest } from '../types';

export const resourcePoolApi = {
  list: (physicalClusterId?: string) => {
    const params = physicalClusterId ? { physicalClusterId } : {};
    return apiClient.get<ResourcePool[]>('/resource-pools', { params });
  },
  get: (id: string) => apiClient.get<ResourcePool>(`/resource-pools/${id}`),
  create: (data: ResourcePoolCreateRequest) =>
    apiClient.post<ResourcePool>('/admin/resource-pools', data),
};
