import apiClient from './client';
import type { ComputeSpec, SpecCreateRequest } from '../types';
import { USE_MOCK } from '../mock';
import { mockSpecApi } from '../mock/specs';

const realApi = {
  list: () => apiClient.get<ComputeSpec[]>('/specs'),
  get: (id: string) => apiClient.get<ComputeSpec>(`/specs/${id}`),
  create: (data: SpecCreateRequest) => apiClient.post<ComputeSpec>('/specs', data),
  delete: (id: string) => apiClient.delete<{ message: string }>(`/specs/${id}`),
};

export const specApi = USE_MOCK ? mockSpecApi : realApi;
