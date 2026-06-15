/**
 * Mock 数据集 —— 所有假数据集中于此，覆盖 API v2.0 全量接口。
 */
import type {
  PhysicalCluster, ComputeSpec, ResourcePool, Workspace, ModelDeployment,
  ClusterNodeInfo, NodeScanResponse, HamiGpuConfig, HamiVgpuUnit, Model,
} from '../types';

// ============================================================
// 物理集群
// ============================================================
export const mockClusters: PhysicalCluster[] = [
  {
    id: 'c1-nvidia-uuid',
    name: 'beijing-nvidia-01',
    description: '北京 NVIDIA RTX4090 集群',
    status: 'active',
    totalGpuSlots: 16,
    gpuTypes: 'NVIDIA',
    location: 'beijing',
    nodeLabels: '{"pool":"nvidia-gpu"}',
    taints: '[{"key":"nvidia.com/gpu","value":"present","effect":"NoSchedule"}]',
    maxCpuCores: 64,
    maxMemoryGib: 256,
    createdAt: '2026-05-20T08:00:00Z',
  },
  {
    id: 'c2-dcu-uuid',
    name: 'beijing-dcu-01',
    description: '北京海光 DCU 集群',
    status: 'active',
    totalGpuSlots: 8,
    gpuTypes: 'HYGON',
    location: 'beijing',
    nodeLabels: '{"pool":"hygon-dcu"}',
    taints: '[{"key":"amd.com/dcu","value":"present","effect":"NoSchedule"}]',
    maxCpuCores: 64,
    maxMemoryGib: 256,
    createdAt: '2026-05-21T10:00:00Z',
  },
  {
    id: 'c3-ascend-uuid',
    name: 'shenzhen-ascend-01',
    description: '深圳华为昇腾 910B 集群',
    status: 'active',
    totalGpuSlots: 32,
    gpuTypes: 'HUAWEI_ASCEND',
    location: 'shenzhen',
    nodeLabels: '{"pool":"huawei-ascend"}',
    taints: '[{"key":"huawei.com/ascend910","value":"present","effect":"NoSchedule"}]',
    maxCpuCores: 64,
    maxMemoryGib: 512,
    createdAt: '2026-05-22T14:00:00Z',
  },
];

// ============================================================
// 算力规格
// ============================================================
export const mockSpecs: ComputeSpec[] = [
  {
    id: 'spec-nvidia-a100-80g',
    name: 'nvidia-a100-80g',
    displayName: 'NVIDIA A100 80GB',
    gpuBrand: 'NVIDIA',
    memoryGb: 80,
    defaultGpuCount: 1,
    defaultCpuCores: 16,
    defaultMemoryGib: 128,
    defaultGpumemMb: 81920,
    nodeSelector: '{"pool":"nvidia-gpu"}',
    tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/nvidia-a100-80g',
    description: '顶级训练卡',
    createdAt: '2026-05-01T00:00:00Z',
  },
  {
    id: 'spec-nvidia-a100-40g',
    name: 'nvidia-a100-40g',
    displayName: 'NVIDIA A100 40GB',
    gpuBrand: 'NVIDIA',
    memoryGb: 40,
    defaultGpuCount: 1,
    defaultCpuCores: 12,
    defaultMemoryGib: 96,
    defaultGpumemMb: 40960,
    nodeSelector: '{"pool":"nvidia-gpu"}',
    tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/nvidia-a100-40g',
    createdAt: '2026-05-01T00:00:00Z',
  },
  {
    id: 'spec-nvidia-rtx4090-24g',
    name: 'nvidia-rtx4090-24g',
    displayName: 'NVIDIA RTX 4090 24GB',
    gpuBrand: 'NVIDIA',
    memoryGb: 24,
    defaultGpuCount: 1,
    defaultCpuCores: 8,
    defaultMemoryGib: 32,
    defaultGpumemMb: 24576,
    nodeSelector: '{"pool":"nvidia-gpu"}',
    tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/nvidia-rtx4090-24g',
    description: '性价比推理卡',
    createdAt: '2026-05-01T00:00:00Z',
  },
  {
    id: 'spec-hygon-dcu-32g',
    name: 'hygon-dcu-32g',
    displayName: '海光 DCU 32GB',
    gpuBrand: 'HYGON',
    memoryGb: 32,
    defaultGpuCount: 1,
    defaultCpuCores: 8,
    defaultMemoryGib: 32,
    defaultGpumemMb: 32768,
    nodeSelector: '{"pool":"hygon-dcu"}',
    tolerations: '[{"key":"amd.com/dcu","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/hygon-dcu-32g',
    createdAt: '2026-05-01T00:00:00Z',
  },
  {
    id: 'spec-huawei-ascend-910b',
    name: 'huawei-ascend-910b',
    displayName: '华为昇腾 910B',
    gpuBrand: 'HUAWEI_ASCEND',
    memoryGb: 64,
    defaultGpuCount: 1,
    defaultCpuCores: 16,
    defaultMemoryGib: 64,
    defaultGpumemMb: 65536,
    nodeSelector: '{"pool":"huawei-ascend"}',
    tolerations: '[{"key":"huawei.com/ascend910","operator":"Exists","effect":"NoSchedule"}]',
    resourceQuotaKey: 'platform.io/huawei-ascend-910b',
    createdAt: '2026-05-01T00:00:00Z',
  },
];

// ============================================================
// 逻辑资源池
// ============================================================
export const mockPools: ResourcePool[] = [
  {
    id: 'pool-algo-uuid',
    name: '算法部资源池',
    description: '算法部跨硬件统一资源池',
    departmentCode: 'algo',
    departmentName: '算法部',
    status: 'active',
    poolMode: 'HETEROGENEOUS',
    physicalClusterIds: ['c1-nvidia-uuid', 'c2-dcu-uuid'],
    specQuotas: [
      { specId: 'spec-nvidia-rtx4090-24g', specName: 'nvidia-rtx4090-24g', totalNodes: 4, allocatedNodes: 2, availableNodes: 2 },
      { specId: 'spec-hygon-dcu-32g', specName: 'hygon-dcu-32g', totalNodes: 2, allocatedNodes: 1, availableNodes: 1 },
    ],
    createdAt: '2026-05-23T08:00:00Z',
  },
  {
    id: 'pool-infra-uuid',
    name: '基础架构部资源池',
    description: '基础架构部专用于推理服务',
    departmentCode: 'infra',
    departmentName: '基础架构部',
    status: 'active',
    poolMode: 'HOMOGENEOUS',
    physicalClusterIds: ['c1-nvidia-uuid'],
    specQuotas: [
      { specId: 'spec-nvidia-a100-40g', specName: 'nvidia-a100-40g', totalNodes: 8, allocatedNodes: 3, availableNodes: 5 },
      { specId: 'spec-nvidia-rtx4090-24g', specName: 'nvidia-rtx4090-24g', totalNodes: 6, allocatedNodes: 0, availableNodes: 6 },
    ],
    createdAt: '2026-05-24T09:00:00Z',
  },
  {
    id: 'pool-ai-lab-uuid',
    name: 'AI Lab 资源池',
    description: 'AI Lab 多架构研究资源池',
    departmentCode: 'ailab',
    departmentName: 'AI Lab',
    status: 'active',
    poolMode: 'HETEROGENEOUS',
    physicalClusterIds: ['c1-nvidia-uuid', 'c3-ascend-uuid'],
    specQuotas: [
      { specId: 'spec-nvidia-a100-80g', specName: 'nvidia-a100-80g', totalNodes: 2, allocatedNodes: 0, availableNodes: 2 },
      { specId: 'spec-huawei-ascend-910b', specName: 'huawei-ascend-910b', totalNodes: 4, allocatedNodes: 0, availableNodes: 4 },
    ],
    createdAt: '2026-05-25T11:00:00Z',
  },
];

// ============================================================
// 工作空间
// ============================================================
export const mockWorkspaces: Workspace[] = [
  {
    id: 'ws-llm-uuid',
    name: 'llm-training',
    description: 'Qwen3 大模型微调工作空间',
    resourcePoolId: 'pool-algo-uuid',
    resourcePoolName: '算法部资源池',
    namespace: 'ws-llm-training-a1b2c3d4',
    volcanoQueueName: 'queue-ws-llm-training-a1b2c3d4',
    primaryClusterId: 'c1-nvidia-uuid',
    maxPods: 30,
    createdBy: 'admin',
    status: 'active',
    specQuotas: [
      { specId: 'spec-nvidia-rtx4090-24g', specName: 'nvidia-rtx4090-24g', maxNodes: 2, usedNodes: 1, availableNodes: 1 },
    ],
    createdAt: '2026-05-25T14:00:00Z',
  },
  {
    id: 'ws-cv-uuid',
    name: 'cv-training',
    description: 'CV 模型训练（DCU）',
    resourcePoolId: 'pool-algo-uuid',
    resourcePoolName: '算法部资源池',
    namespace: 'ws-cv-training-e5f6g7h8',
    volcanoQueueName: 'queue-ws-cv-training-e5f6g7h8',
    primaryClusterId: 'c2-dcu-uuid',
    maxPods: 20,
    createdBy: 'admin',
    status: 'active',
    specQuotas: [
      { specId: 'spec-hygon-dcu-32g', specName: 'hygon-dcu-32g', maxNodes: 1, usedNodes: 0, availableNodes: 1 },
    ],
    createdAt: '2026-05-25T15:30:00Z',
  },
  {
    id: 'ws-inference-uuid',
    name: 'inference-svc',
    description: '在线推理服务空间（多模型）',
    resourcePoolId: 'pool-infra-uuid',
    resourcePoolName: '基础架构部资源池',
    namespace: 'ws-inference-svc-i9j0k1l2',
    volcanoQueueName: 'queue-ws-inference-svc-i9j0k1l2',
    primaryClusterId: 'c1-nvidia-uuid',
    maxPods: 50,
    createdBy: 'admin',
    status: 'active',
    specQuotas: [
      { specId: 'spec-nvidia-a100-40g', specName: 'nvidia-a100-40g', maxNodes: 2, usedNodes: 1, availableNodes: 1 },
      { specId: 'spec-nvidia-rtx4090-24g', specName: 'nvidia-rtx4090-24g', maxNodes: 0, usedNodes: 0, availableNodes: 0 },
    ],
    createdAt: '2026-05-26T09:00:00Z',
  },
];

// ============================================================
// 工作空间成员
// ============================================================
export const mockMembers: Record<string, string[]> = {
  'ws-llm-uuid': ['user-admin-uuid', 'user-zhangsan-uuid'],
  'ws-cv-uuid': ['user-admin-uuid', 'user-lisi-uuid'],
  'ws-inference-uuid': ['user-admin-uuid', 'user-zhangsan-uuid', 'user-wangwu-uuid'],
};

// ============================================================
// 模型部署
// ============================================================
export const mockDeployments: ModelDeployment[] = [
  {
    id: 'dep-qwen3-uuid',
    workspaceId: 'ws-llm-uuid',
    resourcePoolId: 'pool-algo-uuid',
    specId: 'spec-nvidia-rtx4090-24g',
    name: 'qwen3-svc',
    modelName: 'Qwen3-7B-Instruct',
    modelSource: 'with_weights',
    modelIdOrPath: '/models/qwen3',
    vllmImage: 'vllm/vllm-openai:latest',
    gpuPerReplica: 1,
    replicas: 1,
    k8sDeploymentName: 'vllm-qwen3-svc',
    k8sServiceName: 'vllm-qwen3-svc-svc',
    status: 'running',
    serviceUrl: 'http://vllm-qwen3-svc-svc.ws-llm-training-a1b2c3d4.svc.cluster.local:8000',
    readyReplicas: 1,
    createdBy: 'user-zhangsan-uuid',
    createdAt: '2026-05-26T10:00:00Z',
    updatedAt: '2026-05-26T10:00:00Z',
  },
  {
    id: 'dep-deepseek-uuid',
    workspaceId: 'ws-inference-uuid',
    resourcePoolId: 'pool-infra-uuid',
    specId: 'spec-nvidia-a100-40g',
    name: 'deepseek-r1-svc',
    modelName: 'DeepSeek-R1-Distill',
    modelSource: 'with_weights',
    modelIdOrPath: '/models/deepseek-r1',
    vllmImage: 'vllm/vllm-openai:latest',
    gpuPerReplica: 2,
    replicas: 1,
    k8sDeploymentName: 'vllm-deepseek-r1-svc',
    k8sServiceName: 'vllm-deepseek-r1-svc-svc',
    status: 'running',
    serviceUrl: 'http://vllm-deepseek-r1-svc-svc.ws-inference-svc-i9j0k1l2.svc.cluster.local:8000',
    readyReplicas: 1,
    createdBy: 'user-admin-uuid',
    createdAt: '2026-05-26T08:30:00Z',
    updatedAt: '2026-05-26T08:30:00Z',
  },
  {
    id: 'dep-whisper-uuid',
    workspaceId: 'ws-inference-uuid',
    resourcePoolId: 'pool-infra-uuid',
    specId: 'spec-nvidia-rtx4090-24g',
    name: 'whisper-svc',
    modelName: 'Whisper-Large-v3',
    modelSource: 'with_weights',
    modelIdOrPath: '/models/whisper',
    vllmImage: 'vllm/vllm-openai:latest',
    gpuPerReplica: 1,
    replicas: 2,
    k8sDeploymentName: 'vllm-whisper-svc',
    k8sServiceName: 'vllm-whisper-svc-svc',
    status: 'pending',
    serviceUrl: undefined,
    readyReplicas: 0,
    createdBy: 'user-wangwu-uuid',
    createdAt: '2026-05-26T11:00:00Z',
    updatedAt: '2026-05-26T11:00:00Z',
  },
  {
    id: 'dep-llama3-uuid',
    workspaceId: 'ws-llm-uuid',
    resourcePoolId: 'pool-algo-uuid',
    specId: 'spec-nvidia-rtx4090-24g',
    name: 'llama3-svc',
    modelName: 'Llama-3-8B-Instruct',
    modelSource: 'with_weights',
    modelIdOrPath: '/models/llama3',
    vllmImage: 'vllm/vllm-openai:latest',
    gpuPerReplica: 1,
    replicas: 1,
    k8sDeploymentName: 'vllm-llama3-svc',
    k8sServiceName: 'vllm-llama3-svc-svc',
    status: 'running',
    serviceUrl: 'http://vllm-llama3-svc-svc.ws-llm-training-a1b2c3d4.svc.cluster.local:8000',
    readyReplicas: 1,
    createdBy: 'user-zhangsan-uuid',
    createdAt: '2026-05-27T09:00:00Z',
    updatedAt: '2026-05-27T09:00:00Z',
  },
];

// ============================================================
// 容量数据
// ============================================================
export const mockCapacities: Record<string, { gpuSlots: number; cpu: string; memory: string }> = {
  'c1-nvidia-uuid': { gpuSlots: 16, cpu: '128', memory: '549755813888' },
  'c2-dcu-uuid': { gpuSlots: 8, cpu: '64', memory: '274877906944' },
  'c3-ascend-uuid': { gpuSlots: 32, cpu: '256', memory: '1099511627776' },
};

// ============================================================
// 节点扫描数据
// ============================================================
export const mockNodeScans: Record<string, NodeScanResponse> = {
  'c1-nvidia-uuid': {
    clusterId: 'c1-nvidia-uuid',
    nodes: [
      {
        name: 'node-nvidia-01',
        labels: { pool: 'nvidia-gpu', 'nvidia.com/gpu.product': 'NVIDIA-RTX-4090' },
        allocatable: { cpu: '64', memory: '256Gi', 'nvidia.com/gpu': '8' },
        capacity: { cpu: '64', memory: '256Gi', 'nvidia.com/gpu': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
      {
        name: 'node-nvidia-02',
        labels: { pool: 'nvidia-gpu', 'nvidia.com/gpu.product': 'NVIDIA-RTX-4090' },
        allocatable: { cpu: '64', memory: '256Gi', 'nvidia.com/gpu': '8' },
        capacity: { cpu: '64', memory: '256Gi', 'nvidia.com/gpu': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
    ],
    totalNodes: 2,
    readyNodes: 2,
  },
  'c2-dcu-uuid': {
    clusterId: 'c2-dcu-uuid',
    nodes: [
      {
        name: 'node-dcu-01',
        labels: { pool: 'hygon-dcu' },
        allocatable: { cpu: '64', memory: '256Gi', 'amd.com/dcu': '8' },
        capacity: { cpu: '64', memory: '256Gi', 'amd.com/dcu': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
    ],
    totalNodes: 1,
    readyNodes: 1,
  },
  'c3-ascend-uuid': {
    clusterId: 'c3-ascend-uuid',
    nodes: [
      {
        name: 'node-ascend-01',
        labels: { pool: 'huawei-ascend' },
        allocatable: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        capacity: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
      {
        name: 'node-ascend-02',
        labels: { pool: 'huawei-ascend' },
        allocatable: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        capacity: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
      {
        name: 'node-ascend-03',
        labels: { pool: 'huawei-ascend' },
        allocatable: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        capacity: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
      {
        name: 'node-ascend-04',
        labels: { pool: 'huawei-ascend' },
        allocatable: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        capacity: { cpu: '64', memory: '512Gi', 'huawei.com/ascend910': '8' },
        conditions: [{ type: 'Ready', status: 'True' }],
      },
    ],
    totalNodes: 4,
    readyNodes: 4,
  },
};

// ============================================================
// HAMi GPU 配置数据
// ============================================================
const mockVgpuUnits: Record<string, HamiVgpuUnit[]> = {};

export const mockHamiConfigs: HamiGpuConfig[] = [
  {
    id: 'hami-nvidia-rtx4090',
    physicalClusterId: 'c1-nvidia-uuid',
    gpuType: 'NVIDIA',
    gpuMemMb: 24576,
    gpuCores: 100,
    totalVgpuCount: 4,
    nodeSelectorKey: 'nvidia.com/gpu.product',
    nodeSelectorPrefix: 'NVIDIA-RTX-4090',
    createdAt: '2026-05-20T08:00:00Z',
  },
  {
    id: 'hami-dcu-32g',
    physicalClusterId: 'c2-dcu-uuid',
    gpuType: 'HYGON',
    gpuMemMb: 32768,
    gpuCores: 100,
    totalVgpuCount: 2,
    nodeSelectorKey: 'amd.com/dcu',
    nodeSelectorPrefix: 'DCU',
    createdAt: '2026-05-21T10:00:00Z',
  },
];

// initialize vgpu units
mockVgpuUnits['hami-nvidia-rtx4090'] = [
  {
    id: 'vgpu-rtx4090-6g',
    configId: 'hami-nvidia-rtx4090',
    vgpuIndex: 0,
    vgpuName: 'rtx4090-6g',
    vgpuMemMb: 6144,
    vgpuCores: 25,
    nodeSelectorValue: 'NVIDIA-RTX-4090',
    tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists"}]',
    availableCount: 4,
    createdAt: '2026-05-20T08:00:00Z',
  },
  {
    id: 'vgpu-rtx4090-12g',
    configId: 'hami-nvidia-rtx4090',
    vgpuIndex: 1,
    vgpuName: 'rtx4090-12g',
    vgpuMemMb: 12288,
    vgpuCores: 50,
    nodeSelectorValue: 'NVIDIA-RTX-4090',
    tolerations: '[{"key":"nvidia.com/gpu","operator":"Exists"}]',
    availableCount: 2,
    createdAt: '2026-05-20T08:00:00Z',
  },
];

mockVgpuUnits['hami-dcu-32g'] = [
  {
    id: 'vgpu-dcu-16g',
    configId: 'hami-dcu-32g',
    vgpuIndex: 0,
    vgpuName: 'dcu-16g',
    vgpuMemMb: 16384,
    vgpuCores: 50,
    nodeSelectorValue: 'DCU',
    tolerations: '[{"key":"amd.com/dcu","operator":"Exists"}]',
    availableCount: 2,
    createdAt: '2026-05-21T10:00:00Z',
  },
];

export const mockVgpuUnitsData = mockVgpuUnits;

// ============================================================
// 模型广场
// ============================================================
export const mockModels: Model[] = [
  {
    id: 'model-qwen3-7b',
    name: 'qwen3-7b',
    displayName: 'Qwen3-7B-Instruct',
    description: '通义千问 7B 指令微调版本',
    modelSource: 'with_weights',
    storageBackend: 'nfs',
    storagePath: '/mnt/nfs/models',
    fileSizeMb: 14000000,
    createdAt: '2026-05-20T10:00:00Z',
    updatedAt: '2026-05-20T10:00:00Z',
  },
  {
    id: 'model-deepseek-r1',
    name: 'deepseek-r1-distill',
    displayName: 'DeepSeek-R1-Distill-Qwen-7B',
    description: 'DeepSeek 推理模型蒸馏版',
    modelSource: 'with_weights',
    storageBackend: 'nfs',
    storagePath: '/mnt/nfs/models',
    fileSizeMb: 7200000,
    createdAt: '2026-05-21T14:00:00Z',
    updatedAt: '2026-05-21T14:00:00Z',
  },
  {
    id: 'model-llama3-8b',
    name: 'llama3-8b',
    displayName: 'Llama-3-8B-Instruct',
    description: 'Meta Llama 3 8B 指令版本',
    modelSource: 'with_weights',
    storageBackend: 'nfs',
    storagePath: '/mnt/nfs/models',
    fileSizeMb: 16000000,
    createdAt: '2026-05-22T09:00:00Z',
    updatedAt: '2026-05-22T09:00:00Z',
  },
  {
    id: 'model-whisper-large',
    name: 'whisper-large-v3',
    displayName: 'Whisper Large v3',
    description: 'OpenAI Whisper 语音识别大模型',
    modelSource: 'with_weights',
    storageBackend: 'nfs',
    storagePath: '/mnt/nfs/models',
    fileSizeMb: 3100000,
    createdAt: '2026-05-23T11:00:00Z',
    updatedAt: '2026-05-23T11:00:00Z',
  },
  {
    id: 'model-baichuan-13b',
    name: 'baichuan-13b',
    displayName: '百川 13B',
    description: '百川智能 13B 大模型',
    modelSource: 'without_weights',
    storageBackend: 'nfs',
    storagePath: '/mnt/nfs/models',
    fileSizeMb: 26000000,
    createdAt: '2026-05-24T08:00:00Z',
    updatedAt: '2026-05-24T08:00:00Z',
  },
];
