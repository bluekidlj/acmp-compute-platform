import { mockResponse } from './index';
import type { LoginRequest, LoginResponse } from '../types';

export const mockAuthApi = {
  login: async (data: LoginRequest) => {
    const isAdmin = data.username === 'admin';
    return mockResponse<LoginResponse>({
      token: 'mock-jwt-token-' + data.username + '-xxxxx',
      username: data.username,
      role: isAdmin ? 'PLATFORM_ADMIN' : 'TRAINING_USER',
      expiresInMs: 86400000,
    });
  },
};
