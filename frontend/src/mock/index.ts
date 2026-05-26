/**
 * Mock 开关 —— 所有 API 模块通过此常量决定走 Mock 还是真实后端。
 *
 * 切换方式：
 *   改下面 true/false 即可，无需重启（HMR 热更新）
 *
 * 未来可升级为 VITE_USE_MOCK 环境变量：
 *   export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';
 */
export const USE_MOCK = true;

/**
 * 模拟网络延迟（毫秒）
 */
export const MOCK_DELAY_MS = 300;

/**
 * 包装 Mock 响应，模拟 axios 响应格式 + 网络延迟
 */
export function mockResponse<T>(data: T, delay = MOCK_DELAY_MS): Promise<{ data: T }> {
  return new Promise((resolve) => {
    setTimeout(() => resolve({ data: structuredClone(data) }), delay);
  });
}
