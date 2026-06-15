import { mockResponse } from './index';
import { mockHamiConfigs, mockVgpuUnitsData } from './data';
import type {
  HamiGpuConfig,
  HamiGpuConfigCreateRequest,
  HamiVgpuUnit,
  HamiVgpuUnitCreateRequest,
} from '../types';

export const mockHamiGpuConfigApi = {
  list: () => mockResponse<HamiGpuConfig[]>(mockHamiConfigs),

  get: (id: string) => {
    const config = mockHamiConfigs.find((c) => c.id === id);
    if (!config) return Promise.reject({ response: { status: 404, data: { message: '配置不存在' } } });
    const units = mockVgpuUnitsData[id] || [];
    return mockResponse<HamiGpuConfig>({ ...config, vgpuUnits: units });
  },

  create: async (data: HamiGpuConfigCreateRequest) => {
    const newConfig: HamiGpuConfig = {
      id: 'hami-' + Math.random().toString(36).slice(2, 10),
      physicalClusterId: data.physicalClusterId,
      gpuType: data.gpuType,
      gpuMemMb: data.gpuMemMb,
      gpuCores: data.gpuCores,
      totalVgpuCount: data.totalVgpuCount,
      nodeSelectorKey: data.nodeSelectorKey,
      nodeSelectorPrefix: data.nodeSelectorPrefix,
      createdAt: new Date().toISOString(),
    };
    mockHamiConfigs.push(newConfig);
    if (data.vgpuUnits) {
      mockVgpuUnitsData[newConfig.id] = data.vgpuUnits.map((u) => ({
        id: 'vgpu-' + Math.random().toString(36).slice(2, 10),
        configId: newConfig.id,
        ...u,
        availableCount: 0,
        createdAt: new Date().toISOString(),
      }));
    }
    return mockResponse<HamiGpuConfig>(newConfig, 600);
  },

  update: (id: string, data: Partial<HamiGpuConfigCreateRequest>) => {
    const config = mockHamiConfigs.find((c) => c.id === id);
    if (!config) return Promise.reject({ response: { status: 404, data: { message: '配置不存在' } } });
    Object.assign(config, data);
    return mockResponse<HamiGpuConfig>(config);
  },

  delete: (id: string) => {
    const idx = mockHamiConfigs.findIndex((c) => c.id === id);
    if (idx >= 0) mockHamiConfigs.splice(idx, 1);
    delete mockVgpuUnitsData[id];
    return mockResponse<{ message: string }>({ message: '已删除' });
  },

  listByCluster: (clusterId: string) => {
    const configs = mockHamiConfigs.filter((c) => c.physicalClusterId === clusterId);
    return mockResponse<HamiGpuConfig[]>(configs);
  },

  addVgpuUnit: (configId: string, data: HamiVgpuUnitCreateRequest) => {
    if (!mockVgpuUnitsData[configId]) mockVgpuUnitsData[configId] = [];
    const newUnit: HamiVgpuUnit = {
      id: 'vgpu-' + Math.random().toString(36).slice(2, 10),
      configId,
      vgpuIndex: data.vgpuIndex,
      vgpuName: data.vgpuName,
      vgpuMemMb: data.vgpuMemMb,
      vgpuCores: data.vgpuCores,
      nodeSelectorValue: data.nodeSelectorValue,
      tolerations: data.tolerations,
      availableCount: data.availableCount || 0,
      createdAt: new Date().toISOString(),
    };
    mockVgpuUnitsData[configId].push(newUnit);
    return mockResponse<HamiVgpuUnit>(newUnit, 600);
  },

  listVgpuUnits: (configId: string) => {
    const units = mockVgpuUnitsData[configId] || [];
    return mockResponse<HamiVgpuUnit[]>(units);
  },

  deleteVgpuUnit: (configId: string, unitId: string) => {
    if (mockVgpuUnitsData[configId]) {
      const idx = mockVgpuUnitsData[configId].findIndex((u) => u.id === unitId);
      if (idx >= 0) mockVgpuUnitsData[configId].splice(idx, 1);
    }
    return mockResponse<{ message: string }>({ message: 'vGPU 单元已删除' });
  },

  sync: (_configId: string, _clusterId: string, vgpuUnitId: string) => {
    // update available count for the unit across all configs
    for (const units of Object.values(mockVgpuUnitsData)) {
      const unit = units.find((u) => u.id === vgpuUnitId);
      if (unit) {
        unit.availableCount = Math.floor(Math.random() * 4) + 1;
      }
    }
    return mockResponse<{ message: string }>({ message: '同步完成' });
  },
};
