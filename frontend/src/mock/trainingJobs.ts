import { mockResponse } from './index';
import type { TrainingJobRequest, TrainingJobResponse } from '../types';

export const mockTrainingJobApi = {
  submit: async (_workspaceId: string, data: TrainingJobRequest) => {
    return mockResponse<TrainingJobResponse>(
      { jobName: data.jobName, message: '已提交' },
      500,
    );
  },
};
