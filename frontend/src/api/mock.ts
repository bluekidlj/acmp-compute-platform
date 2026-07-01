// 监控数据（纯 mock，后端无）
import { mockMonitoring, mockAlerts, mockAlertRules, mockTrainingJobs, mockStorage } from '../mock/data';

export const monitoringApi = {
  get: () => Promise.resolve(mockMonitoring),
};

export const alertsApi = {
  list: () => Promise.resolve(mockAlerts),
};

export const alertRulesApi = {
  list: () => Promise.resolve(mockAlertRules),
};

export const trainingApi = {
  list: () => Promise.resolve(mockTrainingJobs),
};

export const storageApi = {
  list: () => Promise.resolve(mockStorage),
};