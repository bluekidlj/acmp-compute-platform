import { USE_MOCK, callApi, apiClient } from './client';
import type { Model, ModelRequest } from '../types';
import { mockModels } from '../mock/data';

export const modelsApi = {
  list: async (): Promise<Model[]> => {
    if (USE_MOCK) return mockModels;
    return callApi(() => apiClient.get<Model[]>('/models'));
  },
  get: async (id: string): Promise<Model> => {
    if (USE_MOCK) {
      const m = mockModels.find((x) => x.id === id);
      if (!m) throw new Error('模型不存在');
      return m;
    }
    return callApi(() => apiClient.get<Model>(`/models/${id}`));
  },
  create: async (req: ModelRequest): Promise<Model> => {
    if (USE_MOCK) {
      const id = 'model-' + Date.now();
      const m: Model = {
        id, name: req.name, displayName: req.displayName ?? null, description: req.description ?? null,
        modelSource: req.modelSource ?? 'with_weights',
        storageBackend: req.storageBackend ?? 'nfs',
        storagePath: req.storagePath,
        fileSizeMb: req.fileSizeMb ?? null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      mockModels.push(m);
      return m;
    }
    return callApi(() => apiClient.post<Model>('/models', req));
  },
  update: async (id: string, req: ModelRequest): Promise<Model> => {
    if (USE_MOCK) {
      const m = mockModels.find((x) => x.id === id);
      if (!m) throw new Error('模型不存在');
      Object.assign(m, req, { updatedAt: new Date().toISOString() });
      return m;
    }
    return callApi(() => apiClient.put<Model>(`/models/${id}`, req));
  },
  remove: async (id: string): Promise<void> => {
    if (USE_MOCK) {
      const idx = mockModels.findIndex((x) => x.id === id);
      if (idx >= 0) mockModels.splice(idx, 1);
      return;
    }
    return callApi(() => apiClient.delete<void>(`/models/${id}`));
  },
};