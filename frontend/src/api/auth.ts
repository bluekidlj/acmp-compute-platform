import apiClient from './client';
import type { LoginRequest, LoginResponse } from '../types';
import { USE_MOCK } from '../mock';
import { mockAuthApi } from '../mock/auth';

const realApi = {
  login: (data: LoginRequest) => apiClient.post<LoginResponse>('/auth/login', data),
};

export const authApi = USE_MOCK ? mockAuthApi : realApi;
