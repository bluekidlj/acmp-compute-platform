// API 客户端 + Mock 切换
import axios from 'axios';

// Mock 开关（从 localStorage 读取，支持运行时切换）
export const USE_MOCK = localStorage.getItem('ACMP_USE_MOCK') !== 'false';

export function setUseMock(val: boolean): void {
  localStorage.setItem('ACMP_USE_MOCK', val ? 'true' : 'false');
  window.location.reload();
}

const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers = config.headers ?? {};
    (config.headers as any).Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  },
);

export default apiClient;
export { apiClient };

// 通用：调用真实后端
export async function callApi<T>(fn: () => Promise<{ data: T }>): Promise<T> {
  if (USE_MOCK) throw new Error('USE_MOCK=true, 应走 mock');
  const r = await fn();
  return r.data;
}