import { USE_MOCK, callApi, apiClient } from './client';
import type { PhysicalCluster, ClusterNode, ClusterGpu, ClusterGpuSplit, ClusterCapacity, ScanResult } from '../types';
import { mockClusters, mockNodes, mockGpus, mockGpuSplits, mockCapacity, mockScan } from '../mock/data';

export const clustersApi = {
  list: async (): Promise<PhysicalCluster[]> => {
    if (USE_MOCK) return mockClusters;
    return callApi(() => apiClient.get<PhysicalCluster[]>('/clusters'));
  },
  get: async (id: string): Promise<PhysicalCluster> => {
    if (USE_MOCK) {
      const c = mockClusters.find((x) => x.id === id);
      if (!c) throw new Error('集群不存在');
      return c;
    }
    return callApi(() => apiClient.get<PhysicalCluster>(`/clusters/${id}`));
  },
  create: async (req: { name: string; kubeconfigBase64: string; gpuTypes?: string; location?: string }): Promise<PhysicalCluster> => {
    if (USE_MOCK) {
      const id = 'cluster-' + Date.now();
      const c: PhysicalCluster = {
        id,
        name: req.name,
        description: null,
        status: 'active',
        gpuTypes: req.gpuTypes ?? null,
        hamiSplits: null,
        location: req.location ?? null,
        nodeLabels: null,
        taints: null,
        maxCpuCores: null,
        maxMemoryGib: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockClusters.push(c);
      return c;
    }
    return callApi(() => apiClient.post<PhysicalCluster>('/clusters', req));
  },
  remove: async (id: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockClusters.findIndex((x) => x.id === id);
      if (idx >= 0) mockClusters.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/clusters/${id}`));
  },
  capacity: async (id: string): Promise<ClusterCapacity> => {
    if (USE_MOCK) return mockCapacity;
    return callApi(() => apiClient.get<ClusterCapacity>(`/clusters/${id}/capacity`));
  },
  nodes: async (id: string): Promise<ClusterNode[]> => {
    if (USE_MOCK) return mockNodes;
    return callApi(() => apiClient.get<ClusterNode[]>(`/clusters/${id}/nodes`));
  },
  gpus: async (id: string): Promise<ClusterGpu[]> => {
    if (USE_MOCK) return mockGpus;
    return callApi(() => apiClient.get<ClusterGpu[]>(`/clusters/${id}/gpus`));
  },
  gpuSplits: async (id: string): Promise<ClusterGpuSplit[]> => {
    if (USE_MOCK) return mockGpuSplits;
    return callApi(() => apiClient.get<ClusterGpuSplit[]>(`/clusters/${id}/gpu-splits`));
  },
  scan: async (id: string): Promise<ScanResult> => {
    if (USE_MOCK) return mockScan;
    return callApi(() => apiClient.post<ScanResult>(`/clusters/${id}/scan`));
  },
};