import { mockResponse } from './index';
import { mockModels } from './data';
import type { Model, ModelRequest } from '../types';

export const mockModelApi = {
  list: () => mockResponse<Model[]>(mockModels),

  get: (id: string) => {
    const m = mockModels.find((x) => x.id === id);
    return m
      ? mockResponse<Model>(m)
      : Promise.reject({ response: { status: 404, data: { message: '模型不存在' } } });
  },

  create: async (data: ModelRequest) => {
    const newModel: Model = {
      id: 'model-' + Math.random().toString(36).slice(2, 10),
      name: data.name,
      displayName: data.displayName,
      description: data.description,
      modelSource: data.modelSource || 'with_weights',
      storageBackend: data.storageBackend || 'nfs',
      storagePath: data.storagePath.replace(/\/+$/, ''),
      fileSizeMb: data.fileSizeMb,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    mockModels.push(newModel);
    return mockResponse<Model>(newModel, 201);
  },

  update: async (id: string, data: Partial<ModelRequest>) => {
    const idx = mockModels.findIndex((x) => x.id === id);
    if (idx < 0) return Promise.reject({ response: { status: 404, data: { message: '模型不存在' } } });
    const existing = mockModels[idx];
    const updated: Model = {
      ...existing,
      ...data,
      storagePath: data.storagePath
        ? data.storagePath.replace(/\/+$/, '')
        : existing.storagePath,
      updatedAt: new Date().toISOString(),
    };
    mockModels[idx] = updated;
    return mockResponse<Model>(updated);
  },

  delete: (id: string) => {
    const idx = mockModels.findIndex((x) => x.id === id);
    if (idx >= 0) mockModels.splice(idx, 1);
    return mockResponse<{ message: string }>({ message: '模型已删除' });
  },
};