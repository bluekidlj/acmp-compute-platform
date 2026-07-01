// ============================================================
// ACMP 算力管理平台 — TypeScript 类型定义（与后端 1.0 字段对齐）
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

export type UserRole = 'PLATFORM_ADMIN' | 'ORG_ADMIN' | 'INFERENCE_USER';

export const ROLE_LABELS: Record<UserRole, string> = {
  PLATFORM_ADMIN: '系统管理员',
  ORG_ADMIN: '部门管理员',
  INFERENCE_USER: '推理用户',
};

// ── 物理集群 ──
export interface PhysicalCluster {
  id: string;
  name: string;
  description: string | null;
  status: 'active' | 'inactive';
  gpuTypes: string | null;
  hamiSplits: string | null;
  location: string | null;
  nodeLabels: string | null;
  taints: string | null;
  maxCpuCores: number | null;
  maxMemoryGib: number | null;
  createdAt: string;
  updatedAt: string;
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

export interface ClusterNode {
  name: string;
  labels: Record<string, string>;
  annotations: Record<string, string>;
  allocatable: Record<string, string>;
  capacity: Record<string, string>;
  status: string;
}

export interface ClusterGpu {
  model: string;
  memoryMb: number;
  nodeCount: number;
  totalCards: number;
  nodeNames: string[];
}

export interface ClusterGpuSplit {
  poolLabel: string;
  memMb: number;
  coresPct: number;
  nodeCount: number;
  nodeNames: string[];
}

export interface ClusterCapacity {
  gpuSlots: number;
  cpu: string;
  memory: string;
}

export interface ScanResult {
  scannedAt: string;
  nodeCount: number;
  gpuModelCount: number;
  splitCount: number;
  maxCpuCores: number;
  maxMemoryGib: number;
  gpuTypes: string[];
  splits: ClusterGpuSplit[];
}

// ── 算力规格 ──
export type SpecType = 'PHYSICAL' | 'VIRTUAL' | 'OVERSELL';
export type PoolType = 'EXCLUSIVE' | 'SHARED' | 'OVERSELL';
export type GpuBrand = 'NVIDIA' | 'HYGON' | 'HUAWEI_ASCEND';

export interface ComputeSpec {
  id: string;
  name: string;
  displayName: string;
  gpuBrand: GpuBrand | null;
  specType: SpecType;
  poolType: PoolType;
  defaultGpuCount: number;
  defaultGpumemMb: number | null;
  defaultGpucores: number | null;
  defaultCpuCores: number;
  defaultMemoryGib: number;
  nodeSelector: string | null;
  tolerations: string | null;
  resourceQuotaKey: string;
  memoryGb: number | null;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SpecCreateRequest {
  name: string;
  displayName: string;
  gpuBrand: GpuBrand;
  specType: SpecType;
  defaultGpuCount: number;
  defaultGpumemMb?: number;
  defaultGpucores?: number;
  defaultCpuCores: number;
  defaultMemoryGib: number;
  nodeSelector?: string;
  tolerations?: string;
  resourceQuotaKey: string;
  memoryGb: number;
  description?: string;
}

// ── 物理资源池（3 类）──
export interface ResourcePool {
  id: string;
  workspaceId: string;
  poolType: PoolType;
  name: string;
  description: string | null;
  primaryClusterId: string;
  totalNodes: number;
  allocatedNodes: number;
  status: 'active' | 'inactive';
  capacityStrategy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ResourcePoolUpdateRequest {
  specs: string[];
}

// ── 异构卡（1.5 新增）──
export interface PoolCard {
  id: string;
  poolId: string;
  gpuBrand: string;
  gpuModel: string;
  nodeName: string | null;
  serialNo: string | null;
  specId: string;
  slots: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface PoolCardRequest {
  gpuBrand: string;
  gpuModel: string;
  nodeName: string;
  serialNo?: string;
  specId: string;
}

export interface PoolCardListResponse {
  poolId: string;
  totalNodes: number;
  cards: PoolCard[];
  bySpec: Record<string, { cards: number; slots: number }>;
}

// ── 工作空间 ──
export interface Workspace {
  id: string;
  name: string;
  description: string | null;
  primaryClusterId: string;
  primaryClusterName: string;
  namespace: string;
  serviceAccountName: string;
  volcanoQueueName: string;
  maxPods: number;
  createdBy: string;
  status: 'active' | 'inactive';
  pools: WorkspacePoolSummary[];
  memberIds: string[];
  createdAt: string;
  updatedAt: string;
}

export interface WorkspacePoolSummary {
  id: string;
  poolType: PoolType;
  name: string;
  description: string | null;
  totalNodes: number;
  allocatedNodes: number;
  availableNodes: number;
  specCount: number;
}

export interface WorkspaceCreateRequest {
  name: string;
  description?: string;
  clusterId: string;
  memberIds?: string[];
  maxPods?: number;
}

export interface AddMemberRequest {
  userId: string;
}

// ── 项目 ──
export interface Project {
  id: string;
  workspaceId: string;
  name: string;
  description: string | null;
  createdBy: string;
  status: 'active' | 'inactive';
  memberIds: string[];
  quotaByPoolType: Record<string, ProjectQuotaView[]>;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectQuotaView {
  quotaId: string;
  poolId: string;
  poolName: string;
  specId: string;
  specName: string;
  specType: SpecType;
  totalNodes: number;
  usedNodes: number;
  availableNodes: number;
}

export interface ProjectCreateRequest {
  name: string;
  description?: string;
  memberIds?: string[];
}

// ── 项目配额 ──
export interface ProjectQuota {
  id: string;
  projectId: string;
  poolId: string;
  specId: string;
  totalNodes: number;
  usedNodes: number;
  availableNodes: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectQuotaRequest {
  poolId: string;
  specId: string;
  totalNodes: number;
}

export interface ProjectQuotaUpdateRequest {
  totalNodes: number;
}

// ── 模型部署 ──
export type DeploymentStatus = 'pending' | 'running' | 'failed';

export interface ModelDeployment {
  id: string;
  projectId: string;
  workspaceId: string;
  resourcePoolId: string;
  specId: string;
  poolType: PoolType;
  name: string;
  modelName: string | null;
  modelSource: string | null;
  modelIdOrPath: string | null;
  vllmImage: string | null;
  gpuPerReplica: number;
  gpumemMb: number | null;
  gpucores: number | null;
  replicas: number;
  k8sDeploymentName: string | null;
  k8sServiceName: string | null;
  status: DeploymentStatus;
  serviceUrl: string | null;
  readyReplicas: number | null;
  actualClusterId: string | null;
  poolCardId: string | null;
  resourceKey: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ModelDeploymentRequest {
  name: string;
  description?: string;
  specName: string;
  replicas: number;
  image: string;
  envVars?: Record<string, string>;
  command?: string;
  args?: string;
  modelId?: string;
  modelSource: string;
  modelIdOrPath?: string;
  modelName?: string;
}

// ── 模型广场 ──
export type ModelSource = 'with_weights' | 'without_weights';

export interface Model {
  id: string;
  name: string;
  displayName: string | null;
  description: string | null;
  modelSource: ModelSource;
  storageBackend: string;
  storagePath: string;
  fileSizeMb: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface ModelRequest {
  name: string;
  displayName?: string;
  description?: string;
  modelSource?: ModelSource;
  storageBackend?: string;
  storagePath: string;
  fileSizeMb?: number;
}
