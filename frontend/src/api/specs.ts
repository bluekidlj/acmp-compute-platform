import { USE_MOCK, callApi, apiClient } from './client';
import type { ComputeSpec, SpecType, GpuBrand, PoolType } from '../types';
import { mockSpecs } from '../mock/data';

export const specsApi = {
  list: async (params?: { poolType?: SpecType | PoolType }): Promise<ComputeSpec[]> => {
    if (USE_MOCK) {
      let r = mockSpecs;
      if (params?.poolType) r = r.filter((s) => s.poolType === params.poolType);
      return r;
    }
    return callApi(() => apiClient.get<ComputeSpec[]>('/specs', { params }));
  },
  get: async (id: string): Promise<ComputeSpec> => {
    if (USE_MOCK) {
      const s = mockSpecs.find((x) => x.id === id);
      if (!s) throw new Error('规格不存在');
      return s;
    }
    return callApi(() => apiClient.get<ComputeSpec>(`/specs/${id}`));
  },
  create: async (req: {
    name: string; displayName: string; gpuBrand: GpuBrand; specType: SpecType;
    defaultGpuCount: number; defaultCpuCores: number; defaultMemoryGib: number;
    resourceQuotaKey: string; memoryGb: number; defaultGpumemMb?: number; defaultGpucores?: number;
    description?: string;
  }): Promise<ComputeSpec> => {
    if (USE_MOCK) {
      const id = 'spec-' + Date.now();
      const poolType = (req.specType === 'PHYSICAL' ? 'EXCLUSIVE' : req.specType === 'VIRTUAL' ? 'SHARED' : 'OVERSELL') as ComputeSpec['poolType'];
      const s: ComputeSpec = {
        id,
        name: req.name,
        displayName: req.displayName,
        gpuBrand: req.gpuBrand,
        specType: req.specType,
        poolType,
        defaultGpuCount: req.defaultGpuCount,
        defaultGpumemMb: req.defaultGpumemMb ?? null,
        defaultGpucores: req.defaultGpucores ?? null,
        defaultCpuCores: req.defaultCpuCores,
        defaultMemoryGib: req.defaultMemoryGib,
        nodeSelector: '{}',
        tolerations: '[]',
        resourceQuotaKey: req.resourceQuotaKey,
        memoryGb: req.memoryGb,
        description: req.description ?? null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockSpecs.push(s);
      return s;
    }
    return callApi(() => apiClient.post<ComputeSpec>('/specs', req));
  },
  remove: async (id: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockSpecs.findIndex((x) => x.id === id);
      if (idx >= 0) mockSpecs.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/specs/${id}`));
  },
};