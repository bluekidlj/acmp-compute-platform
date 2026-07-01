import { USE_MOCK, callApi, apiClient } from './client';
import type { LoginRequest, LoginResponse } from '../types';
import { mockUsers } from '../mock/data';

export const authApi = {
  login: async (req: LoginRequest): Promise<LoginResponse> => {
    if (USE_MOCK) {
      const user = mockUsers.find((u) => u.username === req.username);
      if (!user || req.password !== 'admin123') {
        throw new Error('用户名或密码错误');
      }
      const token = 'mock-jwt-token-' + user.id + '-' + Date.now();
      return {
        token,
        username: user.username,
        role: user.role,
        expiresInMs: 86400000,
      };
    }
    return callApi(() => apiClient.post<LoginResponse>('/auth/login', req));
  },
};