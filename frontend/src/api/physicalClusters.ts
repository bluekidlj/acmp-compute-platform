import apiClient from './client';
import type { PhysicalCluster, PhysicalClusterCreateRequest, PhysicalClusterCapacity } from '../types';
import { USE_MOCK } from '../mock';
import { mockPhysicalClusterApi } from '../mock/physicalClusters';

const realApi = {
  list: () => apiClient.get<PhysicalCluster[]>('/physical-clusters'),
  create: (data: PhysicalClusterCreateRequest) =>
    apiClient.post<PhysicalCluster>('/admin/physical-clusters', data),
  capacity: (id: string) =>
    apiClient.get<PhysicalClusterCapacity>(`/physical-clusters/${id}/capacity`),
  delete: (id: string) =>
    apiClient.delete<{ message: string }>(`/physical-clusters/${id}`),
};

export const physicalClusterApi = USE_MOCK ? mockPhysicalClusterApi : realApi;
