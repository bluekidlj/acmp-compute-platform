import apiClient from './client';
import type { PhysicalCluster, PhysicalClusterCreateRequest, PhysicalClusterCapacity } from '../types';

export const physicalClusterApi = {
  list: () => apiClient.get<PhysicalCluster[]>('/physical-clusters'),
  create: (data: PhysicalClusterCreateRequest) =>
    apiClient.post<PhysicalCluster>('/admin/physical-clusters', data),
  capacity: (id: string) =>
    apiClient.get<PhysicalClusterCapacity>(`/physical-clusters/${id}/capacity`),
  delete: (id: string) =>
    apiClient.delete<{ message: string }>(`/physical-clusters/${id}`),
};
