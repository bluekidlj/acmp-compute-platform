import { mockResponse } from './index';
import { mockClusters, mockCapacities, mockNodeScans } from './data';
import type { PhysicalCluster, PhysicalClusterCreateRequest, PhysicalClusterCapacity, NodeScanResponse } from '../types';

export const mockPhysicalClusterApi = {
  list: () => mockResponse<PhysicalCluster[]>(mockClusters),

  create: async (data: PhysicalClusterCreateRequest) => {
    const newCluster: PhysicalCluster = {
      id: 'c-new-' + Math.random().toString(36).slice(2, 10),
      name: data.name,
      description: data.description,
      status: 'active',
      totalGpuSlots: 0,
      gpuTypes: data.gpuTypes || 'NVIDIA',
      location: data.location || 'default',
      nodeLabels: data.nodeLabels,
      taints: data.taints,
      createdAt: new Date().toISOString(),
    };
    mockClusters.push(newCluster);
    return mockResponse<PhysicalCluster>(newCluster);
  },

  capacity: (id: string) => {
    const cap = mockCapacities[id] || { gpuSlots: 0, cpu: '0', memory: '0' };
    return mockResponse<PhysicalClusterCapacity>(cap);
  },

  nodes: (id: string) => {
    const scan = mockNodeScans[id];
    return scan
      ? mockResponse<NodeScanResponse>(scan)
      : Promise.reject({ response: { status: 404, data: { message: '集群不存在' } } });
  },

  delete: (id: string) => {
    const idx = mockClusters.findIndex((c) => c.id === id);
    if (idx >= 0) mockClusters.splice(idx, 1);
    return mockResponse<{ message: string }>({ message: '已删除' });
  },
};
