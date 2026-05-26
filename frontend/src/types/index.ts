// ============================================================
// ACMP 异构计算平台 — 前端类型定义（与 API v2.0 契约对齐）
// ============================================================

// ── 认证 ──
export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  username: string;
  role: UserRole;
  expiresInMs: number;
}

export type UserRole = 'PLATFORM_ADMIN' | 'ORG_ADMIN' | 'TRAINING_USER' | 'INFERENCE_USER';

export const ROLE_LABELS: Record<UserRole, string> = {
  PLATFORM_ADMIN: '系统管理员',
  ORG_ADMIN: '部门管理员',
  TRAINING_USER: '训练用户',
  INFERENCE_USER: '推理用户',
};

// ── 物理集群 ──
export interface PhysicalCluster {
  id: string;
  name: string;
  description?: string;
  status: 'active' | 'inactive';
  totalGpuSlots: number;
  gpuTypes: string;
  location: string;
  nodeLabels?: string;
  taints?: string;
  createdAt: string;
}

export interface PhysicalClusterCreateRequest {
  name: string;
  description?: string;
  kubeconfigBase64: string;
  gpuTypes?: string;
  location?: string;
  nodeLabels?: string;
  taints?: string;
}

export interface PhysicalClusterCapacity {
  gpuSlots: number;
  cpu: string;
  memory: string;
}

// ── 算力规格 ──
export interface ComputeSpec {
  id: string;
  name: string;
  displayName: string;
  gpuBrand: GpuBrand;
  memoryGb: number;
  defaultGpuCount: number;
  defaultCpuCores: number;
  defaultMemoryGib: number;
  defaultGpumemMb?: number;
  defaultGpucores?: number;
  nodeSelector?: string;
  tolerations?: string;
  resourceQuotaKey: string;
  description?: string;
  createdAt: string;
}

export type GpuBrand = 'NVIDIA' | 'HYGON' | 'HUAWEI_ASCEND';

export const GPU_BRAND_LABELS: Record<GpuBrand, string> = {
  NVIDIA: 'NVIDIA',
  HYGON: '海光 DCU',
  HUAWEI_ASCEND: '华为昇腾',
};

export interface SpecCreateRequest {
  name: string;
  displayName: string;
  gpuBrand: GpuBrand;
  memoryGb: number;
  description?: string;
}

// ── 逻辑资源池 ──
export interface SpecQuota {
  specId: string;
  specName: string;
  totalQuota: number;
  allocatedQuota: number;
  availableQuota: number;
}

export interface ResourcePool {
  id: string;
  name: string;
  description?: string;
  departmentCode: string;
  departmentName: string;
  status: 'active' | 'inactive';
  physicalClusterIds: string[];
  specQuotas: SpecQuota[];
  createdAt: string;
}

export interface ResourcePoolCreateRequest {
  physicalClusterIds: string[];
  name: string;
  description?: string;
  departmentCode: string;
  departmentName: string;
  specQuotas: { specName: string; totalQuota: number }[];
}

// ── 工作空间 ──
export interface WorkspaceSpecQuota {
  specId: string;
  specName: string;
  maxQuota: number;
  usedQuota: number;
  availableQuota: number;
}

export interface Workspace {
  id: string;
  name: string;
  description?: string;
  resourcePoolId: string;
  resourcePoolName: string;
  namespace: string;
  volcanoQueueName: string;
  primaryClusterId: string;
  maxPods: number;
  createdBy: string;
  status: 'active' | 'inactive';
  specQuotas: WorkspaceSpecQuota[];
  createdAt: string;
}

export interface WorkspaceCreateRequest {
  name: string;
  description?: string;
  resourcePoolId: string;
  specQuotas: { specName: string; maxQuota: number }[];
  maxPods?: number;
}

export interface WorkspaceUpdateRequest {
  name: string;
  description?: string;
  resourcePoolId: string;
}

// ── 成员管理 ──
export interface AddMemberRequest {
  userId: string;
}

// ── 凭证发放 ──
export interface IssueCredentialRequest {
  username: string;
  expireDays: number;
}

export interface IssueCredentialResponse {
  kubeconfig: string;
  namespace: string;
  clusterName: string;
  serviceAccountName: string;
  message: string;
}

// ── 模型部署 ──
export interface VllmDeployRequest {
  name: string;
  specName: string;
  replicas: number;
  modelName?: string;
  modelSource: string;
  modelIdOrPath?: string;
  vllmImage?: string;
  hostModelPath?: string;
}

export interface ModelDeployment {
  id: string;
  workspaceId: string;
  resourcePoolId: string;
  specId: string;
  name: string;
  modelName?: string;
  modelSource: string;
  modelIdOrPath?: string;
  vllmImage: string;
  gpuPerReplica: number;
  replicas: number;
  k8sDeploymentName: string;
  k8sServiceName: string;
  status: 'pending' | 'running' | 'failed';
  serviceUrl?: string;
  readyReplicas?: number;
  createdBy: string;
  createdAt: string;
}

// ── 训练任务 ──
export interface TrainingJobRequest {
  jobName: string;
  image: string;
  replicas: number;
  specName: string;
  command?: string[];
}

export interface TrainingJobResponse {
  jobName: string;
  message: string;
}

// ── 通用响应 ──
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
