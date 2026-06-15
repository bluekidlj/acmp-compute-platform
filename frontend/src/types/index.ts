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
  maxCpuCores?: number;
  maxMemoryGib?: number;
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
  maxCpuCores?: number;
  maxMemoryGib?: number;
}

export interface PhysicalClusterCapacity {
  gpuSlots: number;
  cpu: string;
  memory: string;
}

// ── 物理集群节点 ──
export interface ClusterNodeInfo {
  name: string;
  labels: Record<string, string>;
  allocatable: Record<string, string>;
  capacity: Record<string, string>;
  conditions: { type: string; status: string }[];
}

export interface NodeScanResponse {
  clusterId: string;
  nodes: ClusterNodeInfo[];
  totalNodes: number;
  readyNodes: number;
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
  totalNodes: number;
  allocatedNodes: number;
  availableNodes: number;
}

export interface ResourcePool {
  id: string;
  name: string;
  description?: string;
  departmentCode: string;
  departmentName: string;
  status: 'active' | 'inactive';
  poolMode?: 'HOMOGENEOUS' | 'HETEROGENEOUS';
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
  maxNodes: number;
  usedNodes: number;
  availableNodes: number;
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
export interface ModelDeploymentRequest {
  name: string;
  replicas: number;
  gpuCount: number;
  cpuCores: number;
  memoryGib: number;
  gpuType: string;
  image: string;
  envVars?: Record<string, string>;
  command?: string;
  args?: string;
  modelSource: string;
  modelIdOrPath?: string;
  modelName?: string;
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
  updatedAt?: string;
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

// ── HAMi GPU 配置 ──
export interface HamiVgpuUnit {
  id: string;
  configId: string;
  vgpuIndex: number;
  vgpuName: string;
  vgpuMemMb: number;
  vgpuCores: number;
  nodeSelectorValue: string;
  tolerations?: string;
  availableCount: number;
  createdAt: string;
}

export interface HamiGpuConfig {
  id: string;
  physicalClusterId: string;
  gpuType: string;
  gpuMemMb: number;
  gpuCores: number;
  totalVgpuCount: number;
  nodeSelectorKey: string;
  nodeSelectorPrefix: string;
  vgpuUnits?: HamiVgpuUnit[];
  createdAt: string;
}

export interface HamiGpuConfigCreateRequest {
  physicalClusterId: string;
  gpuType: string;
  gpuMemMb: number;
  gpuCores: number;
  totalVgpuCount: number;
  nodeSelectorKey: string;
  nodeSelectorPrefix: string;
  vgpuUnits?: {
    vgpuIndex: number;
    vgpuName: string;
    vgpuMemMb: number;
    vgpuCores: number;
    nodeSelectorValue: string;
    tolerations?: string;
  }[];
}

export interface HamiVgpuUnitCreateRequest {
  vgpuIndex: number;
  vgpuName: string;
  vgpuMemMb: number;
  vgpuCores: number;
  nodeSelectorValue: string;
  tolerations?: string;
  availableCount?: number;
}

// ── 资源池容量补丁 ──
export interface ResourcePoolCapacityPatch {
  gpuSlots: number;
  cpuCores: number;
  memoryGiB: number;
}

// ── 模型广场 ──
export interface Model {
  id: string;
  name: string;
  displayName?: string;
  description?: string;
  modelSource: 'with_weights' | 'without_weights';
  /** 存储后端类型，如 nfs */
  storageBackend: string;
  /** 存储路径前缀（不含 name），如 /mnt/nfs/models */
  storagePath: string;
  fileSizeMb?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface ModelRequest {
  name: string;
  displayName?: string;
  description?: string;
  modelSource?: 'with_weights' | 'without_weights';
  /** 存储后端类型，当前固定 nfs */
  storageBackend?: string;
  /** 存储根路径，如 /mnt/nfs/models */
  storagePath: string;
  fileSizeMb?: number;
}
