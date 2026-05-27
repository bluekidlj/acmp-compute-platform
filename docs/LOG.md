# 修改记录

## 2026-05-26 异构算力调度核心改造 + ComputeSpec 资源键注释

### 背景
ACMP-Compute 定位为异构算力管理平台，需要支持同一逻辑池关联多个不同类型物理集群（NVIDIA GPU / Hygon DCU / Huawei Ascend），部署时根据规格动态路由到对应节点。

### 修改内容

#### 1. 数据库变更
- **新增表** `workspace_pool_cluster`：工作空间 ↔ 物理集群多对多关联，记录工作空间涉及的物理集群列表

#### 2. 核心逻辑变更

**WorkspaceService**
- 移除"所有规格必须指向同一物理集群"的约束（原 `targetClusterIds.size() > 1` 抛异常逻辑）
- 改为遍历所有涉及的物理集群，在每个集群上创建 Namespace + ResourceQuota + SA + Role + RoleBinding + Volcano Queue
- `workspace.primaryClusterId` 废弃，设为 null
- 删除工作空间时，遍历所有关联集群分别删除 K8s Namespace

**PoolMetadataService**
- `pickClusterForSpec` 增加 `workspaceId` 可选参数，限定集群选择范围
- `loadPhysicalClustersByPool` 支持从 `workspace_pool_cluster` 查工作空间涉及的物理集群
- 新增重载方法：`pickClusterForSpec(String, ComputeSpec)` 兼容旧调用

**ModelDeploymentService**
- 注入 `PoolMetadataService`
- 部署时调用 `poolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)` 动态选定目标集群
- K8s 提交改为使用动态选定的 `clusterId`，而非 `workspace.primaryClusterId`

**TrainingJobService**
- 注入 `PoolMetadataService`
- 提交时调用 `poolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)` 动态选定目标集群
- K8s 提交改为使用动态选定的 `clusterId`

#### 3. Mapper 变更
- **WorkspaceMapper**：新增 `insertCluster`/`findClusterIds`/`deleteClusters`
- **ResourcePoolMapper**：新增 `findPhysicalClusterIdsByWorkspaceId`

### 涉及文件
- `schema-h2.sql`
- `Workspace.java`
- `WorkspaceMapper.java` + `WorkspaceMapper.xml`
- `ResourcePoolMapper.java` + `ResourcePoolMapper.xml`
- `WorkspaceService.java`
- `PoolMetadataService.java`
- `ModelDeploymentService.java`
- `TrainingJobService.java`
- `ComputeSpec.java`（新增两套资源键详细注释 + 磁盘/存储说明 + 完整字段清单）

### 文档
- 新增 `docs/HETEROGENEOUS-COMPUTE.md`
- 新增 `docs/HETEROGENEOUS-EXAMPLE.md`
- 新增 `docs/COMPUTE-PARTITION.md`（算力切分机制）
- 新增 `docs/HAMI-PARTITION.md`（HAMi vGPU 算力切分管理）

## 2026-05-26 ComputeSpec 两套资源键说明

`ComputeSpec` 新增类注释，解释：

**真实硬件资源键**（K8s 调度器用）：
- `defaultGpuCount` → `limits["nvidia.com/gpu"]`
- `defaultCpuCores` → `limits["cpu"]`
- `defaultMemoryGib` → `limits["memory"]`

**平台计量键**（仅用于 ResourceQuota 副本数计量）：
- `resourceQuotaKey` → `limits["platform.io/{spec}"] = 1`

两套键协同工作：Pod 因 `defaultGpuCount` 被调度到有 GPU 的节点，又因 `resourceQuotaKey=1` 在 ResourceQuota 计量中占 1 份配额。

## 2026-05-27 HAMi vGPU 切分管理（MVP 简化版）

### 设计原则
平台预设切分规格（GpuSplitSpec 枚举），用户创建资源池时选择切分类型，平台自动生成 ComputeSpec。

### 修改内容

**新增 GpuSplitSpec 枚举**
- 预置 NVIDIA A100-80GB、RTX4090-24GB、Hygon DCU 32GB 的 1/2/1/4/1/8 切分规格
- 每个规格包含：specName、gpuBrand、gpuType、gpumemMb、gpucores

**ResourcePoolCreateRequest 新增字段**
- `splitType`：切分类型，如 "1/4"
- `gpuType`：GPU 型号，如 "A100-80GB-SXM"

**ResourcePoolService.create 新增逻辑**
- 当 splitType 非空时，调用 `createWithGpuSplit()` 模式
- 根据 gpuType + splitType 查找 GpuSplitSpec 枚举
- 自动创建 ComputeSpec（name、nodeSelector、gpumemMb、gpucores）
- 插入 resource_pool_spec_quota

**ComputeSpecMapper.xml 更新**
- 新增 spec_type、hami_vgpu_unit_id 字段映射
- 新增 update 方法

### 涉及文件
- `GpuSplitSpec.java`（新增）
- `ResourcePoolService.java`
- `ResourcePoolCreateRequest.java`
- `ComputeSpecMapper.xml`
- `ComputeSpecMapper.java`

### 文档
- 更新 `docs/HAMI-PARTITION.md`
- 更新 `docs/HAMI-OPERATION.md`

## 2026-05-27 节点纳管功能

### 功能说明
当新主机加入集群后，平台提供 HTTP 接口扫描集群节点，展示节点的算力资源信息，供前端进行纳管操作。

### 修改内容

**新增 NodeInfoResponse DTO**
- 节点名称、状态、GPU 类型、GPU 卡数、显存、算力、CPU、内存、pool 标签

**PhysicalClusterService 新增方法**
- `scanNodes(clusterId)` - 扫描集群所有节点，收集算力信息

**PhysicalClusterController 新增接口**
- `GET /api/v1/physical-clusters/{id}/nodes` - 返回节点列表

### 涉及文件
- `NodeInfoResponse.java`（新增）
- `PhysicalClusterService.java`
- `PhysicalClusterController.java`

### 文档
- 新增 `docs/NODE-MANAGEMENT.md`

## 2026-05-27 poolLabel → poolLabels 多规格支持（已修正）

### 问题
原设计假设每个节点只有一个 poolLabel（一种切分规格），但实际上同一 GPU 节点可同时支持多种切分规格（如 1/2、1/4、1/8 共存）。

### 修改内容

**NodeInfoResponse**
- `poolLabel` (String) → `poolLabels` (Set<String>)
- 节点可报告多个 pool 标签值

**NodeScanResponse**
- `poolLabels` 聚合逻辑改为支持逗号分隔多规格

**ResourcePoolCreateRequest**
- `poolLabel` (String) - 用户选择单一规格创建资源池，非多选

**PhysicalClusterService.scanNodes()**
- 支持逗号分隔多规格解析

### K8s 节点标签格式（支持多规格）
```bash
# 逗号分隔多规格
kubectl label node ${NODE_NAME} --overwrite pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4,nvidia-a100-80g-1/8
```

### 设计说明
- `poolLabels`（扫描返回）：展示节点支持的所有规格，用户从中选择
- `poolLabel`（创建请求）：用户选择的单一规格，一个资源池 = 一种规格
- 不同规格需分别创建不同的资源池

### 涉及文件
- `NodeInfoResponse.java`
- `ResourcePoolCreateRequest.java`
- `PhysicalClusterService.java`
- `ResourcePoolService.java`

### 文档
- 更新 `docs/NODE-ONBOARDING.md`
- 更新 `docs/HAMI-OPERATION.md`
- 重写 `docs/HAMI-PARTITION.md`（修正规格来源说明，移除 splitType/gpuType 旧 API）

## 2026-05-27 部署预检验增强：CPU/内存资源上限校验

### 背景
用户只关心 GPU 数量（gpuCount）和规格（gpuType），但平台需要确保用户填的 CPU/内存不超过节点上限。

### 修改内容
**PhysicalCluster 新增字段**
- `maxCpuCores`：单节点最大 CPU 核数
- `maxMemoryGib`：单节点最大内存 GiB

**HomogeneousScheduler.validateDeployment**
- 保留 gpuType 匹配校验
- 新增 CPU 上限校验：`request.cpuCores > cluster.maxCpuCores`
- 新增内存上限校验：`request.memoryGib > cluster.maxMemoryGib`

**HeterogeneousScheduler.validateDeployment**
- 同样的 CPU/内存上限校验（用匹配到的集群来校验）

### 涉及文件
- `PhysicalCluster.java`
- `schema-h2.sql`
- `PhysicalClusterMapper.xml`
- `HomogeneousScheduler.java`
- `HeterogeneousScheduler.java`

## 2026-05-27 新增部署推理服务完整流程文档

### 新增文档
- `docs/DEPLOYMENT-FLOW.md` - 部署推理服务完整流程说明

文档内容：
- ComputeSpec 是什么、字段说明、生成的 K8s 资源
- specName 生成规则（auto-{gpuType}-{gpuCount}g-{cpuCores}c-{memoryGib}g）
- 完整部署 6 步流程（validateDeployment → ensureComputeSpec → 配额校验 → pickCluster → K8s 提交）
- validateDeployment 为什么重要（提前拒绝，不扣配额）
- 资源池两种模式（HOMOGENEOUS / HETEROGENEOUS）

## 2026-05-27 资源池调度器抽象：同构/异构两种模式

### 背景
将资源池分为两种模式：
- HOMOGENEOUS（同构）：单一物理集群，简单场景
- HETEROGENEOUS（异构）：多物理集群，异构算力路由

### 修改内容

**新增调度器接口和实现**
- `PoolScheduler.java` - 调度器接口：pickCluster + validateDeployment
- `HomogeneousScheduler.java` - 同构调度器：直接返回唯一物理集群，支持部署前预检验
- `HeterogeneousScheduler.java` - 异构调度器：按 nodeSelector 匹配集群

**PoolMetadataService 重构**
- 改为调度器工厂，根据 poolMode 分发到对应调度器
- pickClusterForSpec 返回 PhysicalCluster 而非 TargetCluster

**实体变更**
- ResourcePool 新增 poolMode 字段（HOMOGENEOUS/HETEROGENEOUS）
- schema-h2.sql 新增 pool_mode 列
- ResourcePoolMapper.xml 同步更新

**ModelDeploymentService 适配**
- 新增 validateDeployment 部署预检验（gpuType 是否在池支持范围内）
- pickClusterForSpec 返回 PhysicalCluster，直接用 cluster.getId()

**TrainingJobService 适配**
- 同 ModelDeploymentService

### 涉及文件
- `PoolScheduler.java`（新增）
- `HomogeneousScheduler.java`（新增）
- `HeterogeneousScheduler.java`（新增）
- `PoolMetadataService.java`
- `ResourcePool.java`
- `ResourcePoolMapper.xml`
- `schema-h2.sql`
- `ModelDeploymentService.java`
- `TrainingJobService.java`

## 2026-05-27 部署推理服务单元测试

### 测试覆盖
- `ensureComputeSpec` - 复用已有 ComputeSpec、自动创建、未知类型默认
- `deployBySpec` - 成功部署（自动创建 spec）、复用已有 spec、workspace 不存在、poolId 不匹配、无权限、K8s 失败回滚配额
- `delete` - 成功删除、K8s 删除失败时仍回滚配额、status=failed 不回滚配额
- `listByWorkspace` - 返回部署列表

### 涉及文件
- `src/test/java/com/acmp/compute/service/ModelDeploymentServiceTest.java`（新增）

## 2026-05-27 部署推理服务旧代码清理

### 背景
部署推理服务已完成重构（用户直接指定每副本资源），清理旧代码。

### 修改内容
- **删除** `VllmDeployRequest.java`（旧接口 DTO）
- **删除** `ModelDeploymentService` 中 `deploy(VllmDeployRequest)` / `deployBySpec(VllmDeployRequest)` / `deployNew()` / `deployBySpecNew()` 等旧方法
- **保留** `deploy(String workspaceId, ModelDeploymentRequest)` 和 `deployBySpec(String poolId, String workspaceId, ModelDeploymentRequest)` 两种重载
- **Controller** 调用 `deployBySpec(poolId, workspaceId, request)` 即可

## 2026-05-27 部署推理服务重构（用户视角流程）

### 背景
原有接口要求用户选择预定义 `specName`，限制了灵活性。用户希望直接指定每副本资源（gpuCount/cpu/memory），平台自动匹配或创建 ComputeSpec。

### 修改内容

**新增 DTO：`ModelDeploymentRequest`**
- 基本信息：`name`、`description`
- 算力资源（用户直接指定）：`replicas`、`gpuCount`、`cpuCores`、`memoryGib`、`gpuType`
- 服务配置：`image`、`envVars`、`command`、`args`
- 模型配置：`modelSource`、`modelIdOrPath`、`modelName`

**Service 层重构**
- `ModelDeploymentService` 新增 `deployNew()` / `deployBySpecNew()` 方法
- 新增 `ensureComputeSpec()` 自动匹配/创建 ComputeSpec：
  - spec 名称格式：`auto-{gpuType}-{gpuCount}g-{cpuCores}c-{memoryGib}g`
  - `nodeSelector = {"pool": gpuType}` 匹配资源池规格
  - GPU 显存/算力从 `GpuSplitSpec` 枚举自动推导

**K8sResourceBuilder 增强**
- 新增支持 `envVars`/`command`/`args` 的重载方法
- 新增 `parseCommand()` 辅助方法（支持 JSON 数组/逗号分隔/空格分隔）

**Controller 层**
- 复用 `ModelDeploymentController` 已有接口，路径不变

### API 示例
```
POST /api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments
{
  "name": "qwen3-deployment",
  "replicas": 2,
  "gpuCount": 1,
  "cpuCores": 4,
  "memoryGib": 16,
  "gpuType": "nvidia-a100-80g-1/4",
  "image": "vllm/vllm-openai:latest",
  "envVars": {"MODEL_NAME": "Qwen3-14B"},
  "command": ["python", "-m", "vllm.entrypoints.openai.api_server"],
  "args": "--model /models/Qwen3-14B --host 0.0.0.0 --port 8000",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models/Qwen3-14B",
  "modelName": "Qwen3-14B"
}
```

### 涉及文件
- `ModelDeploymentRequest.java`（新增）
- `ModelDeploymentService.java`
- `K8sResourceBuilder.java`

### 文档
- 新增 `docs/MODEL-DEPLOYMENT.md`（部署推理服务操作手册）

## 2026-05-27 配额字段重命名：节点数替代资源量

### 问题
配额使用"资源量"（total_quota/allocated_quota）概念，用户需要自己换算 vGPU 实例数与节点数的关系，使用门槛高。

### 修改内容

**NodeInfoResponse 字段重命名：**
| 原字段 | 新字段 | 含义 |
|--------|--------|------|
| `gpuCount` | `nodeCount` | 可用节点数（vGPU 实例数） |
| `gpuMemMb` | `nodeMemMb` | 每节点显存 |
| `gpuCores` | `nodeCores` | 每节点算力 |

**数据库字段重命名：**
| 原字段 | 新字段 | 含义 |
|--------|--------|------|
| `total_quota` | `total_nodes` | 池内总节点数 |
| `allocated_quota` | `allocated_nodes` | 已分配节点数 |
| `max_quota` | `max_nodes` | 工作空间最大节点数 |
| `used_quota` | `used_nodes` | 已使用节点数 |

### 涉及文件

**Java 源码：**
- `NodeInfoResponse.java` - 字段重命名
- `PhysicalClusterService.java` - toNodeInfo() 方法
- `QuotaService.java` - L1/L2 配额校验和扣减逻辑
- `ResourcePoolService.java` - toResponse() 配额展示
- `WorkspaceService.java` - 工作空间配额分配/释放
- `ComputeSpecService.java` - SpecQuotaView 构建

**Mapper XML：**
- `ComputeSpecMapper.xml` - SQL 列名更新

**SQL Schema：**
- `schema-h2.sql` - 表结构变更

### 文档更新
- `docs/NODE-ONBOARDING.md` - 更新字段说明
- `docs/NODE-MANAGEMENT.md` - 重写文档
- `docs/API-REFERENCE.md` - 更新字段说明
- `docs/ARCHITECTURE.md` - 更新公式说明
- `docs/RESOURCE-POOL-DESIGN.md` - 更新配额说明