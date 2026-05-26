import apiClient from './client';
import type { ComputeSpec, SpecCreateRequest } from '../types';

export const specApi = {
  list: () => apiClient.get<ComputeSpec[]>('/specs'),
  get: (id: string) => apiClient.get<ComputeSpec>(`/specs/${id}`),
  create: (data: SpecCreateRequest) => apiClient.post<ComputeSpec>('/specs', data),
  delete: (id: string) => apiClient.delete<{ message: string }>(`/specs/${id}`),
};
