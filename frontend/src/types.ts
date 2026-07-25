export type UserRole = 'PLATFORM_ADMIN' | 'ORG_ADMIN' | 'INFERENCE_USER';
export type ClusterStatus = 'ACTIVE' | 'INACTIVE' | 'ERROR';
export type SpecType = 'EXCLUSIVE' | 'SHARED';
export type DeploymentStatus = 'PENDING' | 'SUBMITTED' | 'RUNNING' | 'FAILED';

export const ROLE_LABELS: Record<UserRole, string> = {
  PLATFORM_ADMIN: '平台管理员',
  ORG_ADMIN: '租户管理员',
  INFERENCE_USER: '推理用户',
};

export interface LoginResponse {
  token: string;
  username: string;
  role: UserRole;
  expiresInMs: number;
}

export interface PhysicalCluster {
  id: string;
  name: string;
  description: string | null;
  status: ClusterStatus;
  kubernetesVersion: string | null;
  nodeCount: number;
  gpuCount: number;
  lastSyncAt: string | null;
  syncMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ClusterNode {
  id: string;
  clusterId: string;
  name: string;
  cpuCores: number;
  memoryBytes: number;
  gpuCount: number;
  status: string;
  labelsJson: string | null;
  taintsJson: string | null;
  lastSyncAt: string;
}

export interface GpuDevice {
  id: string;
  clusterId: string;
  nodeId: string;
  nodeName: string;
  gpuIndex: number;
  uuid: string | null;
  gpuModel: string | null;
  memoryMb: number | null;
  driverVersion: string | null;
  cudaVersion: string | null;
  status: string;
  resourcePoolId: string | null;
  computeSpecId: string | null;
  usageStatus: string;
  lastSyncAt: string;
}

export interface ResourcePoolSpecBrief {
  id: string;
  name: string;
  displayName: string;
  specType: SpecType;
}

export interface ResourcePool {
  id: string;
  poolType: SpecType;
  name: string;
  description: string | null;
  gpuCount: number;
  status: string;
  specs: ResourcePoolSpecBrief[];
  createdAt: string;
  updatedAt: string;
}

export interface ComputeSpec {
  id: string;
  name: string;
  displayName: string | null;
  gpuBrand: 'NVIDIA' | 'HYGON' | 'HUAWEI_ASCEND';
  specType: SpecType;
  resourcePoolId: string;
  gpuModel: string | null;
  gpuCount: number;
  cpuCores: number;
  memoryGib: number;
  gpuShare: '1/8' | '1/4' | '1/2' | null;
  description: string | null;
  status: string;
  capacityNodes: number;
  allocatedNodes: number;
  usedNodes: number;
  sourceGpuId: string | null;
  sourceGpuUuid: string | null;
  sourceGpuIndex: number | null;
  sourceNodeName: string | null;
  resourcePoolName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SpecRequest {
  name: string;
  displayName?: string;
  gpuBrand: 'NVIDIA' | 'HYGON' | 'HUAWEI_ASCEND';
  specType: SpecType;
  resourcePoolId: string;
  gpuModel?: string;
  gpuCount: number;
  cpuCores: number;
  memoryGib: number;
  gpuShare?: '1/8' | '1/4' | '1/2';
  description?: string;
}

export interface Tenant {
  id: string;
  name: string;
  description: string | null;
  createdBy: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantSpecQuota {
  id: string;
  tenantId: string;
  specId: string;
  specName: string;
  specDisplayName: string | null;
  resourcePoolId: string;
  resourcePoolName: string | null;
  poolType: SpecType;
  gpuModel: string | null;
  gpuShare: '1/8' | '1/4' | '1/2' | null;
  cpuCores: number;
  memoryGib: number;
  capacityNodes: number;
  total: number;
  used: number;
  remaining: number;
}

export interface Project {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  createdBy: string;
  status: string;
  memberIds: string[];
  quotaByPoolType: Record<string, unknown[]>;
  createdAt: string;
  updatedAt: string;
}

export interface Model {
  id: string;
  name: string;
  displayName: string | null;
  description: string | null;
  modelSource: string;
  storageBackend: string;
  storagePath: string;
  fileSizeMb: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface ModelDeployment {
  id: string;
  projectId: string;
  tenantId: string;
  resourcePoolId: string;
  specId: string;
  name: string;
  modelName: string | null;
  modelSource: string | null;
  modelIdOrPath: string | null;
  vllmImage: string | null;
  port: number;
  replicas: number;
  k8sDeploymentName: string;
  k8sServiceName: string;
  status: DeploymentStatus;
  serviceUrl: string | null;
  readyReplicas: number | null;
  actualClusterId: string;
  createdBy: string;
  failureMessage: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface DeploymentRequest {
  name: string;
  specName: string;
  replicas: number;
  image: string;
  port: number;
  command?: string;
  args?: string;
  envVars?: Record<string, string>;
  modelId?: string;
  modelSource?: string;
  modelIdOrPath: string;
  modelName: string;
}

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

export interface ChatCompletionResponse {
  id: string;
  choices: Array<{
    index: number;
    message: ChatMessage;
    finish_reason: string | null;
  }>;
  usage?: {
    prompt_tokens: number;
    completion_tokens: number;
    total_tokens: number;
  };
}
