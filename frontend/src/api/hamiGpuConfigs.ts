import apiClient from './client';
import type {
  HamiGpuConfig,
  HamiGpuConfigCreateRequest,
  HamiVgpuUnit,
  HamiVgpuUnitCreateRequest,
} from '../types';
import { USE_MOCK } from '../mock';
import { mockHamiGpuConfigApi } from '../mock/hamiGpuConfigs';

const realApi = {
  list: () => apiClient.get<HamiGpuConfig[]>('/hami-gpu-configs'),
  get: (id: string) => apiClient.get<HamiGpuConfig>(`/hami-gpu-configs/${id}`),
  create: (data: HamiGpuConfigCreateRequest) =>
    apiClient.post<HamiGpuConfig>('/hami-gpu-configs', data),
  update: (id: string, data: Partial<HamiGpuConfigCreateRequest>) =>
    apiClient.put<HamiGpuConfig>(`/hami-gpu-configs/${id}`, data),
  delete: (id: string) =>
    apiClient.delete<{ message: string }>(`/hami-gpu-configs/${id}`),

  listByCluster: (clusterId: string) =>
    apiClient.get<HamiGpuConfig[]>(`/hami-gpu-configs/cluster/${clusterId}`),

  addVgpuUnit: (configId: string, data: HamiVgpuUnitCreateRequest) =>
    apiClient.post<HamiVgpuUnit>(`/hami-gpu-configs/${configId}/vgpu-units`, data),
  listVgpuUnits: (configId: string) =>
    apiClient.get<HamiVgpuUnit[]>(`/hami-gpu-configs/${configId}/vgpu-units`),
  deleteVgpuUnit: (configId: string, unitId: string) =>
    apiClient.delete<{ message: string }>(`/hami-gpu-configs/${configId}/vgpu-units/${unitId}`),

  sync: (configId: string, clusterId: string, vgpuUnitId: string) =>
    apiClient.post<{ message: string }>(`/hami-gpu-configs/${configId}/sync`, {
      clusterId,
      vgpuUnitId,
    }),
};

export const hamiGpuConfigApi = USE_MOCK ? mockHamiGpuConfigApi : realApi;
