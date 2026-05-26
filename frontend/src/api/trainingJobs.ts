import apiClient from './client';
import type { TrainingJobRequest, TrainingJobResponse } from '../types';

export const trainingJobApi = {
  submit: (workspaceId: string, data: TrainingJobRequest) =>
    apiClient.post<TrainingJobResponse>(`/workspaces/${workspaceId}/training-jobs`, data),
};
