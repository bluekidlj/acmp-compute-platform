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