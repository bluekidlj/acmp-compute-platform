// ============================================================
// 演示数据 — 严格按后端 1.0 字段定义
// 故事线：AI 部门"ai-rd"组织
// ============================================================

import type {
  PhysicalCluster, ClusterNode, ClusterGpu, ClusterGpuSplit, ScanResult,
  ComputeSpec, ResourcePool, PoolCard,
  Workspace, Project, ProjectQuota, ModelDeployment, Model,
  ClusterCapacity,
} from '../types';

// ── 时间戳辅助 ──
const now = () => new Date().toISOString();
const daysAgo = (d: number) => new Date(Date.now() - d * 86400000).toISOString();

// ── 物理集群 ──
export const mockClusters: PhysicalCluster[] = [
  {
    id: 'cluster-bj-01',
    name: '北京生产 K8s 集群',
    description: 'K8s 1.28.5, 3 节点 (2 GPU + 1 CPU)',
    status: 'active',
    gpuTypes: 'NVIDIA,HYGON',
    hamiSplits: JSON.stringify([
      { poolLabel: 'nvidia-7b', memMb: 6000, coresPct: 16 },
      { poolLabel: 'nvidia-14b', memMb: 12000, coresPct: 33 },
      { poolLabel: 'nvidia-28b', memMb: 24000, coresPct: 50 },
    ]),
    location: '北京-亦庄',
    nodeLabels: null,
    taints: null,
    maxCpuCores: 40,
    maxMemoryGib: 256,
    createdAt: daysAgo(30),
    updatedAt: daysAgo(1),
  },
  {
    id: 'cluster-sh-01',
    name: '上海测试 K8s 集群',
    description: 'K8s 1.28.5, 1 节点 (NVIDIA H100 x2)',
    status: 'active',
    gpuTypes: 'NVIDIA',
    hamiSplits: JSON.stringify([
      { poolLabel: 'nvidia-70b', memMb: 40000, coresPct: 50 },
    ]),
    location: '上海-张江',
    nodeLabels: null,
    taints: null,
    maxCpuCores: 24,
    maxMemoryGib: 128,
    createdAt: daysAgo(15),
    updatedAt: daysAgo(2),
  },
];

export const mockNodes: ClusterNode[] = [
  {
    name: 'gpu-node-01',
    labels: { 'kubernetes.io/hostname': 'gpu-node-01', 'nvidia.com/gpu.product': 'NVIDIA-A100-SXM4-80GB' },
    annotations: { 'nvidia.com/gpu-memory': '81920', 'nvidia.com/gpu.family': 'a100' },
    allocatable: { 'cpu': '16', 'memory': '64Gi', 'nvidia.com/gpu': '4', 'amd.com/dcu': '1', 'pods': '110' },
    capacity: { 'cpu': '16', 'memory': '64Gi', 'nvidia.com/gpu': '4', 'amd.com/dcu': '1', 'pods': '110' },
    status: 'Ready',
  },
  {
    name: 'gpu-node-02',
    labels: { 'kubernetes.io/hostname': 'gpu-node-02', 'nvidia.com/gpu.product': 'NVIDIA-H100-SXM5-80GB' },
    annotations: { 'nvidia.com/gpu-memory': '81920', 'nvidia.com/gpu.family': 'h100' },
    allocatable: { 'cpu': '24', 'memory': '128Gi', 'nvidia.com/gpu': '2', 'pods': '110' },
    capacity: { 'cpu': '24', 'memory': '128Gi', 'nvidia.com/gpu': '2', 'pods': '110' },
    status: 'Ready',
  },
  {
    name: 'cpu-node-01',
    labels: { 'kubernetes.io/hostname': 'cpu-node-01' },
    annotations: {},
    allocatable: { 'cpu': '32', 'memory': '128Gi', 'pods': '110' },
    capacity: { 'cpu': '32', 'memory': '128Gi', 'pods': '110' },
    status: 'Ready',
  },
];

export const mockGpus: ClusterGpu[] = [
  { model: 'NVIDIA-A100-SXM4-80GB', memoryMb: 81920, nodeCount: 1, totalCards: 4, nodeNames: ['gpu-node-01'] },
  { model: 'NVIDIA-H100-SXM5-80GB', memoryMb: 81920, nodeCount: 1, totalCards: 2, nodeNames: ['gpu-node-02'] },
  { model: 'HYGON-DCU', memoryMb: 16384, nodeCount: 1, totalCards: 1, nodeNames: ['gpu-node-01'] },
];

export const mockGpuSplits: ClusterGpuSplit[] = [
  { poolLabel: 'nvidia-7b', memMb: 6000, coresPct: 16, nodeCount: 1, nodeNames: ['gpu-node-01'] },
  { poolLabel: 'nvidia-14b', memMb: 12000, coresPct: 33, nodeCount: 1, nodeNames: ['gpu-node-01'] },
  { poolLabel: 'nvidia-28b', memMb: 24000, coresPct: 50, nodeCount: 1, nodeNames: ['gpu-node-01'] },
];

export const mockScan: ScanResult = {
  scannedAt: now(),
  nodeCount: 3,
  gpuModelCount: 3,
  splitCount: 3,
  maxCpuCores: 40,
  maxMemoryGib: 256,
  gpuTypes: ['NVIDIA-A100-SXM4-80GB', 'NVIDIA-H100-SXM5-80GB', 'HYGON-DCU'],
  splits: mockGpuSplits,
};

export const mockCapacity: ClusterCapacity = {
  gpuSlots: 7,
  cpu: '72',
  memory: '320Gi',
};

// ── 算力规格（与后端预置 7 条一致）──
export const mockSpecs: ComputeSpec[] = [
  {
    id: 'spec-exclusive-a100', name: 'exclusive-nvidia-a100-80g', displayName: 'NVIDIA A100 80GB (独占整卡)',
    gpuBrand: 'NVIDIA', specType: 'PHYSICAL', poolType: 'EXCLUSIVE',
    defaultGpuCount: 1, defaultGpumemMb: 0, defaultGpucores: 0, defaultCpuCores: 8, defaultMemoryGib: 32,
    nodeSelector: '{}', tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/exclusive-nvidia-a100-80g', memoryGb: 80,
    description: 'NVIDIA A100 80GB 整卡独占', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
  {
    id: 'spec-exclusive-h100', name: 'exclusive-nvidia-h100-80g', displayName: 'NVIDIA H100 80GB (独占整卡)',
    gpuBrand: 'NVIDIA', specType: 'PHYSICAL', poolType: 'EXCLUSIVE',
    defaultGpuCount: 1, defaultGpumemMb: 0, defaultGpucores: 0, defaultCpuCores: 8, defaultMemoryGib: 32,
    nodeSelector: '{}', tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/exclusive-nvidia-h100-80g', memoryGb: 80,
    description: 'NVIDIA H100 80GB 整卡独占', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
  {
    id: 'spec-exclusive-dcu', name: 'exclusive-hygon-dcu', displayName: '海光 DCU (独占整卡)',
    gpuBrand: 'HYGON', specType: 'PHYSICAL', poolType: 'EXCLUSIVE',
    defaultGpuCount: 1, defaultGpumemMb: 0, defaultGpucores: 0, defaultCpuCores: 8, defaultMemoryGib: 32,
    nodeSelector: '{}', tolerations: '[{"key":"amd.com/dcu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/exclusive-hygon-dcu', memoryGb: 16,
    description: '海光 DCU 整卡独占', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
  {
    id: 'spec-shared-a100-12', name: 'shared-hami-a100-1/2', displayName: 'A100 80GB 1/2 卡 (HAMi 切分)',
    gpuBrand: 'NVIDIA', specType: 'VIRTUAL', poolType: 'SHARED',
    defaultGpuCount: 1, defaultGpumemMb: 40960, defaultGpucores: 50, defaultCpuCores: 4, defaultMemoryGib: 16,
    nodeSelector: '{}', tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/shared-hami-a100-1-2', memoryGb: 40,
    description: 'A100 80GB 切 1/2 = 40GB', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
  {
    id: 'spec-shared-a100-14', name: 'shared-hami-a100-1/4', displayName: 'A100 80GB 1/4 卡 (HAMi 切分)',
    gpuBrand: 'NVIDIA', specType: 'VIRTUAL', poolType: 'SHARED',
    defaultGpuCount: 1, defaultGpumemMb: 20480, defaultGpucores: 25, defaultCpuCores: 2, defaultMemoryGib: 8,
    nodeSelector: '{}', tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/shared-hami-a100-1-4', memoryGb: 20,
    description: 'A100 80GB 切 1/4 = 20GB', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
  {
    id: 'spec-shared-a100-18', name: 'shared-hami-a100-1/8', displayName: 'A100 80GB 1/8 卡 (HAMi 切分)',
    gpuBrand: 'NVIDIA', specType: 'VIRTUAL', poolType: 'SHARED',
    defaultGpuCount: 1, defaultGpumemMb: 10240, defaultGpucores: 12, defaultCpuCores: 1, defaultMemoryGib: 4,
    nodeSelector: '{}', tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/shared-hami-a100-1-8', memoryGb: 10,
    description: 'A100 80GB 切 1/8 = 10GB', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
  {
    id: 'spec-oversell-a100', name: 'oversell-a100-mig-1/2', displayName: 'A100 MIG 1/2 (超分占位)',
    gpuBrand: 'NVIDIA', specType: 'OVERSELL', poolType: 'OVERSELL',
    defaultGpuCount: 1, defaultGpumemMb: 0, defaultGpucores: 0, defaultCpuCores: 4, defaultMemoryGib: 16,
    nodeSelector: '{}', tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/oversell-a100-mig-1-2', memoryGb: 40,
    description: 'A100 MIG 1/2 超分占位（1.0 不实际提交 K8s）', createdAt: daysAgo(30), updatedAt: daysAgo(30),
  },
];

// ── 异构卡（1.5 新增）──
export const mockPoolCards: PoolCard[] = [
  // 4 张 A100 → shared 池 (1/4 切分 → slots=4/张)
  { id: 'pcard-a100-1', poolId: 'pool-ai-rd-shared', gpuBrand: 'NVIDIA', gpuModel: 'NVIDIA-A100-SXM4-80GB', nodeName: 'gpu-node-01', serialNo: 'GPU-A100-001', specId: 'spec-shared-a100-14', slots: 4, status: 'active', createdAt: daysAgo(7), updatedAt: daysAgo(7) },
  { id: 'pcard-a100-2', poolId: 'pool-ai-rd-shared', gpuBrand: 'NVIDIA', gpuModel: 'NVIDIA-A100-SXM4-80GB', nodeName: 'gpu-node-01', serialNo: 'GPU-A100-002', specId: 'spec-shared-a100-14', slots: 4, status: 'active', createdAt: daysAgo(7), updatedAt: daysAgo(7) },
  { id: 'pcard-a100-3', poolId: 'pool-ai-rd-shared', gpuBrand: 'NVIDIA', gpuModel: 'NVIDIA-A100-SXM4-80GB', nodeName: 'gpu-node-01', serialNo: 'GPU-A100-003', specId: 'spec-shared-a100-14', slots: 4, status: 'active', createdAt: daysAgo(7), updatedAt: daysAgo(7) },
  { id: 'pcard-a100-4', poolId: 'pool-ai-rd-shared', gpuBrand: 'NVIDIA', gpuModel: 'NVIDIA-A100-SXM4-80GB', nodeName: 'gpu-node-01', serialNo: 'GPU-A100-004', specId: 'spec-shared-a100-14', slots: 4, status: 'active', createdAt: daysAgo(7), updatedAt: daysAgo(7) },
  // 1 张 A100 → shared 池 (1/2 切分 → slots=2)
  { id: 'pcard-a100-5', poolId: 'pool-ai-rd-shared', gpuBrand: 'NVIDIA', gpuModel: 'NVIDIA-A100-SXM4-80GB', nodeName: 'gpu-node-01', serialNo: 'GPU-A100-005', specId: 'spec-shared-a100-12', slots: 2, status: 'active', createdAt: daysAgo(5), updatedAt: daysAgo(5) },
  // 1 张 DCU → exclusive 池 (整卡 → slots=1)
  { id: 'pcard-dcu-1', poolId: 'pool-ai-rd-exclusive', gpuBrand: 'HYGON', gpuModel: 'DCU', nodeName: 'gpu-node-01', serialNo: 'DCU-001', specId: 'spec-exclusive-dcu', slots: 1, status: 'active', createdAt: daysAgo(7), updatedAt: daysAgo(7) },
];

// ── 物理资源池（3 类，totalNodes 由 poolCards 自动累加）──
export const mockPools: ResourcePool[] = [
  {
    id: 'pool-ai-rd-exclusive', workspaceId: 'ws-ai-rd',
    poolType: 'EXCLUSIVE', name: 'ai-rd-exclusive', description: 'ai-rd 的 EXCLUSIVE 池',
    primaryClusterId: 'cluster-bj-01',
    totalNodes: 1, allocatedNodes: 1, status: 'active', capacityStrategy: 'SUM_SLOTS',
    createdAt: daysAgo(10), updatedAt: daysAgo(7),
  },
  {
    id: 'pool-ai-rd-shared', workspaceId: 'ws-ai-rd',
    poolType: 'SHARED', name: 'ai-rd-shared', description: 'ai-rd 的 SHARED 池',
    primaryClusterId: 'cluster-bj-01',
    totalNodes: 18, allocatedNodes: 6, status: 'active', capacityStrategy: 'SUM_SLOTS',
    createdAt: daysAgo(10), updatedAt: daysAgo(2),
  },
  {
    id: 'pool-ai-rd-oversell', workspaceId: 'ws-ai-rd',
    poolType: 'OVERSELL', name: 'ai-rd-oversell', description: 'ai-rd 的 OVERSELL 池',
    primaryClusterId: 'cluster-bj-01',
    totalNodes: 0, allocatedNodes: 0, status: 'active', capacityStrategy: 'SUM_SLOTS',
    createdAt: daysAgo(10), updatedAt: daysAgo(10),
  },
  {
    id: 'pool-cv-exclusive', workspaceId: 'ws-cv',
    poolType: 'EXCLUSIVE', name: 'cv-exclusive', description: 'cv-team 的 EXCLUSIVE 池',
    primaryClusterId: 'cluster-bj-01',
    totalNodes: 0, allocatedNodes: 0, status: 'active', capacityStrategy: 'SUM_SLOTS',
    createdAt: daysAgo(8), updatedAt: daysAgo(8),
  },
  {
    id: 'pool-cv-shared', workspaceId: 'ws-cv',
    poolType: 'SHARED', name: 'cv-shared', description: 'cv-team 的 SHARED 池',
    primaryClusterId: 'cluster-bj-01',
    totalNodes: 0, allocatedNodes: 0, status: 'active', capacityStrategy: 'SUM_SLOTS',
    createdAt: daysAgo(8), updatedAt: daysAgo(8),
  },
  {
    id: 'pool-cv-oversell', workspaceId: 'ws-cv',
    poolType: 'OVERSELL', name: 'cv-oversell', description: 'cv-team 的 OVERSELL 池',
    primaryClusterId: 'cluster-bj-01',
    totalNodes: 0, allocatedNodes: 0, status: 'active', capacityStrategy: 'SUM_SLOTS',
    createdAt: daysAgo(8), updatedAt: daysAgo(8),
  },
];

// ── 工作空间 ──
export const mockWorkspaces: Workspace[] = [
  {
    id: 'ws-ai-rd', name: 'ai-rd', description: 'AI 算法研发部门',
    primaryClusterId: 'cluster-bj-01', primaryClusterName: '北京生产 K8s 集群',
    namespace: 'ws-ai-rd-1a2b3c4d', serviceAccountName: 'sa-ws-ai-rd-1a2b3c4d',
    volcanoQueueName: 'queue-ws-ai-rd-1a2b3c4d', maxPods: 100,
    createdBy: 'user-admin', status: 'active',
    pools: [
      { id: 'pool-ai-rd-exclusive', poolType: 'EXCLUSIVE', name: 'ai-rd-exclusive', description: 'ai-rd 的 EXCLUSIVE 池', totalNodes: 1, allocatedNodes: 1, availableNodes: 0, specCount: 1 },
      { id: 'pool-ai-rd-shared', poolType: 'SHARED', name: 'ai-rd-shared', description: 'ai-rd 的 SHARED 池', totalNodes: 18, allocatedNodes: 6, availableNodes: 12, specCount: 2 },
      { id: 'pool-ai-rd-oversell', poolType: 'OVERSELL', name: 'ai-rd-oversell', description: 'ai-rd 的 OVERSELL 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
    ],
    memberIds: ['user-admin', 'user-alice', 'user-bob'],
    createdAt: daysAgo(10), updatedAt: daysAgo(1),
  },
  {
    id: 'ws-cv', name: 'cv-team', description: '计算机视觉组',
    primaryClusterId: 'cluster-bj-01', primaryClusterName: '北京生产 K8s 集群',
    namespace: 'ws-cv-2b3c4d5e', serviceAccountName: 'sa-ws-cv-2b3c4d5e',
    volcanoQueueName: 'queue-ws-cv-2b3c4d5e', maxPods: 50,
    createdBy: 'user-admin', status: 'active',
    pools: [
      { id: 'pool-cv-exclusive', poolType: 'EXCLUSIVE', name: 'cv-exclusive', description: 'cv-team 的 EXCLUSIVE 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
      { id: 'pool-cv-shared', poolType: 'SHARED', name: 'cv-shared', description: 'cv-team 的 SHARED 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
      { id: 'pool-cv-oversell', poolType: 'OVERSELL', name: 'cv-oversell', description: 'cv-team 的 OVERSELL 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
    ],
    memberIds: ['user-admin', 'user-carol'],
    createdAt: daysAgo(8), updatedAt: daysAgo(8),
  },
  {
    id: 'ws-nlp', name: 'nlp-team', description: '自然语言处理组',
    primaryClusterId: 'cluster-bj-01', primaryClusterName: '北京生产 K8s 集群',
    namespace: 'ws-nlp-3c4d5e6f', serviceAccountName: 'sa-ws-nlp-3c4d5e6f',
    volcanoQueueName: 'queue-ws-nlp-3c4d5e6f', maxPods: 50,
    createdBy: 'user-admin', status: 'active',
    pools: [
      { id: 'pool-nlp-exclusive', poolType: 'EXCLUSIVE', name: 'nlp-exclusive', description: 'nlp 的 EXCLUSIVE 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
      { id: 'pool-nlp-shared', poolType: 'SHARED', name: 'nlp-shared', description: 'nlp 的 SHARED 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
      { id: 'pool-nlp-oversell', poolType: 'OVERSELL', name: 'nlp-oversell', description: 'nlp 的 OVERSELL 池', totalNodes: 0, allocatedNodes: 0, availableNodes: 0, specCount: 0 },
    ],
    memberIds: ['user-admin', 'user-dave'],
    createdAt: daysAgo(6), updatedAt: daysAgo(6),
  },
];

// ── 项目配额 ──
export const mockQuotas: ProjectQuota[] = [
  // llm-team：shared 1/4 切分 4 节点（used 1）
  { id: 'prq-llm-1', projectId: 'proj-llm', poolId: 'pool-ai-rd-shared', specId: 'spec-shared-a100-14', totalNodes: 4, usedNodes: 1, availableNodes: 3, createdAt: daysAgo(5), updatedAt: daysAgo(1) },
  // llm-team：shared 1/2 切分 2 节点（used 0）
  { id: 'prq-llm-2', projectId: 'proj-llm', poolId: 'pool-ai-rd-shared', specId: 'spec-shared-a100-12', totalNodes: 2, usedNodes: 0, availableNodes: 2, createdAt: daysAgo(5), updatedAt: daysAgo(5) },
  // llm-team：exclusive DCU 1 节点（used 0）
  { id: 'prq-llm-3', projectId: 'proj-llm', poolId: 'pool-ai-rd-exclusive', specId: 'spec-exclusive-dcu', totalNodes: 1, usedNodes: 0, availableNodes: 1, createdAt: daysAgo(5), updatedAt: daysAgo(5) },
  // cv-team：shared 1/4 切分 2 节点
  { id: 'prq-cv-1', projectId: 'proj-cv', poolId: 'pool-cv-shared', specId: 'spec-shared-a100-14', totalNodes: 2, usedNodes: 0, availableNodes: 2, createdAt: daysAgo(3), updatedAt: daysAgo(3) },
  // nlp-team：oversell 占位 3
  { id: 'prq-nlp-1', projectId: 'proj-nlp', poolId: 'pool-nlp-oversell', specId: 'spec-oversell-a100', totalNodes: 3, usedNodes: 1, availableNodes: 2, createdAt: daysAgo(4), updatedAt: daysAgo(2) },
];

// ── 项目 ──
export const mockProjects: Project[] = [
  {
    id: 'proj-llm', workspaceId: 'ws-ai-rd',
    name: 'llm-team', description: 'LLM 推理服务组', createdBy: 'user-admin', status: 'active',
    memberIds: ['user-admin', 'user-alice'],
    quotaByPoolType: {
      SHARED: [
        { quotaId: 'prq-llm-1', poolId: 'pool-ai-rd-shared', poolName: 'ai-rd-shared', specId: 'spec-shared-a100-14', specName: 'shared-hami-a100-1/4', specType: 'VIRTUAL', totalNodes: 4, usedNodes: 1, availableNodes: 3 },
        { quotaId: 'prq-llm-2', poolId: 'pool-ai-rd-shared', poolName: 'ai-rd-shared', specId: 'spec-shared-a100-12', specName: 'shared-hami-a100-1/2', specType: 'VIRTUAL', totalNodes: 2, usedNodes: 0, availableNodes: 2 },
      ],
      EXCLUSIVE: [
        { quotaId: 'prq-llm-3', poolId: 'pool-ai-rd-exclusive', poolName: 'ai-rd-exclusive', specId: 'spec-exclusive-dcu', specName: 'exclusive-hygon-dcu', specType: 'PHYSICAL', totalNodes: 1, usedNodes: 0, availableNodes: 1 },
      ],
    },
    createdAt: daysAgo(5), updatedAt: daysAgo(1),
  },
  {
    id: 'proj-cv', workspaceId: 'ws-cv',
    name: 'cv-team', description: 'CV 模型训练与推理', createdBy: 'user-admin', status: 'active',
    memberIds: ['user-admin', 'user-carol'],
    quotaByPoolType: {
      SHARED: [
        { quotaId: 'prq-cv-1', poolId: 'pool-cv-shared', poolName: 'cv-shared', specId: 'spec-shared-a100-14', specName: 'shared-hami-a100-1/4', specType: 'VIRTUAL', totalNodes: 2, usedNodes: 0, availableNodes: 2 },
      ],
    },
    createdAt: daysAgo(3), updatedAt: daysAgo(3),
  },
  {
    id: 'proj-nlp', workspaceId: 'ws-nlp',
    name: 'nlp-team', description: 'NLP 训练', createdBy: 'user-admin', status: 'active',
    memberIds: ['user-admin', 'user-dave'],
    quotaByPoolType: {
      OVERSELL: [
        { quotaId: 'prq-nlp-1', poolId: 'pool-nlp-oversell', poolName: 'nlp-oversell', specId: 'spec-oversell-a100', specName: 'oversell-a100-mig-1/2', specType: 'OVERSELL', totalNodes: 3, usedNodes: 1, availableNodes: 2 },
      ],
    },
    createdAt: daysAgo(4), updatedAt: daysAgo(2),
  },
];

// ── 模型部署 ──
export const mockDeployments: ModelDeployment[] = [
  {
    id: 'dep-1', projectId: 'proj-llm', workspaceId: 'ws-ai-rd',
    resourcePoolId: 'pool-ai-rd-shared', specId: 'spec-shared-a100-14', poolType: 'SHARED',
    name: 'qwen3-svc', modelName: 'Qwen3-14B', modelSource: 'with_weights', modelIdOrPath: '/mnt/nfs/models/qwen3-14b',
    vllmImage: 'vllm/vllm-openai:latest', gpuPerReplica: 1, gpumemMb: 20480, gpucores: 25, replicas: 1,
    k8sDeploymentName: 'vllm-qwen3-svc', k8sServiceName: 'vllm-qwen3-svc-svc',
    status: 'running',
    serviceUrl: 'http://vllm-qwen3-svc-svc.ws-ai-rd-1a2b3c4d.svc.cluster.local:8000',
    readyReplicas: 1, actualClusterId: 'cluster-bj-01', poolCardId: 'pcard-a100-1', resourceKey: 'platform.io/shared-hami-a100-1-4',
    createdBy: 'user-admin', createdAt: daysAgo(3), updatedAt: daysAgo(1),
  },
  {
    id: 'dep-2', projectId: 'proj-llm', workspaceId: 'ws-ai-rd',
    resourcePoolId: 'pool-ai-rd-exclusive', specId: 'spec-exclusive-dcu', poolType: 'EXCLUSIVE',
    name: 'bert-infer', modelName: 'BERT-Base', modelSource: 'without_weights', modelIdOrPath: '/mnt/nfs/models/bert-base',
    vllmImage: 'bert-server:latest', gpuPerReplica: 1, gpumemMb: null, gpucores: null, replicas: 1,
    k8sDeploymentName: 'bert-infer', k8sServiceName: 'bert-infer-svc',
    status: 'running',
    serviceUrl: 'http://bert-infer-svc.ws-ai-rd-1a2b3c4d.svc.cluster.local:8000',
    readyReplicas: 1, actualClusterId: 'cluster-bj-01', poolCardId: 'pcard-dcu-1', resourceKey: 'platform.io/exclusive-hygon-dcu',
    createdBy: 'user-alice', createdAt: daysAgo(2), updatedAt: daysAgo(1),
  },
  {
    id: 'dep-3', projectId: 'proj-nlp', workspaceId: 'ws-nlp',
    resourcePoolId: 'pool-nlp-oversell', specId: 'spec-oversell-a100', poolType: 'OVERSELL',
    name: 'stable-diffusion', modelName: 'SDXL-1.0', modelSource: 'with_weights', modelIdOrPath: '/mnt/nfs/models/sdxl-1.0',
    vllmImage: 'sdxl-server:latest', gpuPerReplica: 1, gpumemMb: null, gpucores: null, replicas: 1,
    k8sDeploymentName: 'sdxl-svc', k8sServiceName: 'sdxl-svc-svc',
    status: 'running', serviceUrl: null, readyReplicas: null,
    actualClusterId: 'cluster-bj-01', poolCardId: null, resourceKey: 'platform.io/oversell-a100-mig-1-2',
    createdBy: 'user-dave', createdAt: daysAgo(2), updatedAt: daysAgo(2),
  },
  {
    id: 'dep-4', projectId: 'proj-llm', workspaceId: 'ws-ai-rd',
    resourcePoolId: 'pool-ai-rd-shared', specId: 'spec-shared-a100-14', poolType: 'SHARED',
    name: 'llama3-svc', modelName: 'LLaMA3-70B', modelSource: 'with_weights', modelIdOrPath: '/mnt/nfs/models/llama3-70b',
    vllmImage: 'vllm/vllm-openai:latest', gpuPerReplica: 1, gpumemMb: 20480, gpucores: 25, replicas: 1,
    k8sDeploymentName: 'vllm-llama3', k8sServiceName: 'vllm-llama3-svc',
    status: 'failed', serviceUrl: null, readyReplicas: 0,
    actualClusterId: 'cluster-bj-01', poolCardId: null, resourceKey: 'platform.io/shared-hami-a100-1-4',
    createdBy: 'user-admin', createdAt: daysAgo(1), updatedAt: daysAgo(1),
  },
];

// ── 模型广场 ──
export const mockModels: Model[] = [
  { id: 'model-1', name: 'qwen3-14b', displayName: '通义千问 Qwen3-14B', description: '阿里通义千问 14B 基础模型', modelSource: 'with_weights', storageBackend: 'nfs', storagePath: '/mnt/nfs/models', fileSizeMb: 28000, createdAt: daysAgo(20), updatedAt: daysAgo(5) },
  { id: 'model-2', name: 'llama3-70b', displayName: 'Meta LLaMA 3-70B', description: 'Meta 开源 70B 模型', modelSource: 'with_weights', storageBackend: 'nfs', storagePath: '/mnt/nfs/models', fileSizeMb: 140000, createdAt: daysAgo(18), updatedAt: daysAgo(10) },
  { id: 'model-3', name: 'bert-base', displayName: 'BERT-Base 中文', description: 'BERT 基础模型（无预训练权重）', modelSource: 'without_weights', storageBackend: 'nfs', storagePath: '/mnt/nfs/models', fileSizeMb: null, createdAt: daysAgo(15), updatedAt: daysAgo(15) },
  { id: 'model-4', name: 'sdxl-1.0', displayName: 'Stable Diffusion XL 1.0', description: 'SDXL 1.0 图像生成模型', modelSource: 'with_weights', storageBackend: 'nfs', storagePath: '/mnt/nfs/models', fileSizeMb: 13000, createdAt: daysAgo(10), updatedAt: daysAgo(8) },
  { id: 'model-5', name: 'chatglm3-6b', displayName: 'ChatGLM3-6B', description: '清华 ChatGLM3-6B 对话模型', modelSource: 'with_weights', storageBackend: 'nfs', storagePath: '/mnt/nfs/models', fileSizeMb: 12000, createdAt: daysAgo(8), updatedAt: daysAgo(3) },
];

// ── 监控数据 ──
export const mockMonitoring = {
  cluster: {
    totalCpuCores: 40,
    usedCpuCores: 23,
    totalMemoryGib: 256,
    usedMemoryGib: 130,
    totalGpuCards: 7,
    usedGpuCards: 4,
  },
  nodes: [
    { name: 'gpu-node-01', cpuUsage: 78, memUsage: 65, gpuUsage: 82, gpuMemUsage: 71, gpuTemp: 84, status: 'Ready' },
    { name: 'gpu-node-02', cpuUsage: 45, memUsage: 50, gpuUsage: 30, gpuMemUsage: 28, gpuTemp: 62, status: 'Ready' },
    { name: 'cpu-node-01', cpuUsage: 22, memUsage: 30, gpuUsage: 0, gpuMemUsage: 0, gpuTemp: 0, status: 'Ready' },
  ],
};

// ── 告警 ──
export const mockAlerts = [
  { id: 'alert-1', level: 'critical', source: 'gpu-node-01', message: 'GPU 温度过高：84°C（阈值 80°C）', firedAt: daysAgo(0.05), status: 'firing' },
  { id: 'alert-2', level: 'warning', source: 'ws-ai-rd', message: '项目 llm-team 配额使用率 87%（1/4 切分）', firedAt: daysAgo(0.3), status: 'firing' },
  { id: 'alert-3', level: 'warning', source: 'nfs-models', message: '存储卷 nfs-models 使用率 91%', firedAt: daysAgo(0.5), status: 'firing' },
  { id: 'alert-4', level: 'info', source: 'cpu-node-01', message: '节点已稳定运行 24 小时', firedAt: daysAgo(1), status: 'resolved' },
  { id: 'alert-5', level: 'info', source: 'proj-llm', message: '检测到新部署 qwen3-svc', firedAt: daysAgo(3), status: 'resolved' },
];

// ── 告警规则 ──
export const mockAlertRules = [
  { id: 'rule-1', name: 'GPU 高温告警', metric: 'gpu_temperature', condition: '>', threshold: 80, level: 'critical', enabled: true },
  { id: 'rule-2', name: '配额使用率告警', metric: 'pool_quota_usage', condition: '>', threshold: 80, level: 'warning', enabled: true },
  { id: 'rule-3', name: 'Pod OOMKilled', metric: 'pod_oom_killed', condition: '>=', threshold: 1, level: 'warning', enabled: false },
];

// ── 训练任务（mock）──
export const mockTrainingJobs = [
  { id: 'train-1', name: 'llama3-finetune', image: 'pytorch/pytorch:2.1.0', replicas: 1, status: 'running', spec: 'shared-hami-a100-1/4', createdBy: 'user-alice', createdAt: daysAgo(2) },
  { id: 'train-2', name: 'sdxl-train', image: 'pytorch/pytorch:2.1.0', replicas: 4, status: 'pending', spec: 'exclusive-nvidia-a100-80g', createdBy: 'user-dave', createdAt: daysAgo(0.5) },
  { id: 'train-3', name: 'bert-pretrain', image: 'tensorflow/tensorflow:2.14.0', replicas: 8, status: 'completed', spec: 'shared-hami-a100-1/2', createdBy: 'user-carol', createdAt: daysAgo(5) },
];

// ── 存储（mock）──
export const mockStorage = [
  { id: 'vol-1', name: 'nfs-models', backend: 'NFS', server: '10.0.1.50', path: '/mnt/nfs/models', totalGib: 5000, usedGib: 1200, status: 'active' },
  { id: 'vol-2', name: 'nfs-datasets', backend: 'NFS', server: '10.0.1.51', path: '/mnt/nfs/datasets', totalGib: 5000, usedGib: 800, status: 'active' },
  { id: 'vol-3', name: 'nfs-checkpoints', backend: 'NFS', server: '10.0.1.52', path: '/mnt/nfs/checkpoints', totalGib: 5000, usedGib: 2000, status: 'active' },
];

// ── 演示用户 ──
export const mockUsers = [
  { id: 'user-admin', username: 'admin', role: 'PLATFORM_ADMIN' as const, displayName: '系统管理员' },
  { id: 'user-alice', username: 'alice', role: 'INFERENCE_USER' as const, displayName: 'Alice（LLM 组）' },
  { id: 'user-bob', username: 'bob', role: 'INFERENCE_USER' as const, displayName: 'Bob（LLM 组）' },
  { id: 'user-carol', username: 'carol', role: 'INFERENCE_USER' as const, displayName: 'Carol（CV 组）' },
  { id: 'user-dave', username: 'dave', role: 'INFERENCE_USER' as const, displayName: 'Dave（NLP 组）' },
];
