import { mockResponse } from './index';
import { mockSpecs } from './data';
import type { ComputeSpec, SpecCreateRequest } from '../types';

export const mockSpecApi = {
  list: () => mockResponse<ComputeSpec[]>(mockSpecs),

  get: (id: string) => {
    const spec = mockSpecs.find((s) => s.id === id);
    return spec
      ? mockResponse<ComputeSpec>(spec)
      : Promise.reject({ response: { status: 404, data: { message: '规格不存在' } } });
  },

  create: async (data: SpecCreateRequest) => {
    const newSpec: ComputeSpec = {
      id: 'spec-' + data.name,
      name: data.name,
      displayName: data.displayName,
      gpuBrand: data.gpuBrand,
      memoryGb: data.memoryGb,
      defaultGpuCount: 1,
      defaultCpuCores: 8,
      defaultMemoryGib: 32,
      resourceQuotaKey: 'platform.io/' + data.name,
      description: data.description,
      createdAt: new Date().toISOString(),
    };
    mockSpecs.push(newSpec);
    return mockResponse<ComputeSpec>(newSpec);
  },

  delete: (id: string) => {
    const idx = mockSpecs.findIndex((s) => s.id === id);
    if (idx >= 0) mockSpecs.splice(idx, 1);
    return mockResponse<{ message: string }>({ message: '已删除' });
  },
};
