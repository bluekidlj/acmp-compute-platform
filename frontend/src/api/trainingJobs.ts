import apiClient from './client';
import type { TrainingJobRequest, TrainingJobResponse } from '../types';
import { USE_MOCK } from '../mock';
import { mockTrainingJobApi } from '../mock/trainingJobs';

const realApi = {
  submit: (workspaceId: string, data: TrainingJobRequest) =>
    apiClient.post<TrainingJobResponse>(`/workspaces/${workspaceId}/training-jobs`, data),
};

export const trainingJobApi = USE_MOCK ? mockTrainingJobApi : realApi;
