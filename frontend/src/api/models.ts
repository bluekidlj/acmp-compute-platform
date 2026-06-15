import apiClient from './client';
import type { Model, ModelRequest } from '../types';
import { USE_MOCK } from '../mock';
import { mockModelApi } from '../mock/models';

const realApi = {
  list: () => apiClient.get<Model[]>('/models'),
  get: (id: string) => apiClient.get<Model>(`/models/${id}`),
  create: (data: ModelRequest) => apiClient.post<Model>('/models', data),
  update: (id: string, data: Partial<ModelRequest>) =>
    apiClient.put<Model>(`/models/${id}`, data),
  delete: (id: string) =>
    apiClient.delete<{ message: string }>(`/models/${id}`),
};

export const modelApi = USE_MOCK ? mockModelApi : realApi;