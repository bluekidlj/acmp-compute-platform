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

## 2026-05-26 ComputeSpec 两套资源键说明

`ComputeSpec` 新增类注释，解释：

**真实硬件资源键**（K8s 调度器用）：
- `defaultGpuCount` → `limits["nvidia.com/gpu"]`
- `defaultCpuCores` → `limits["cpu"]`
- `defaultMemoryGib` → `limits["memory"]`

**平台计量键**（仅用于 ResourceQuota 副本数计量）：
- `resourceQuotaKey` → `limits["platform.io/{spec}"] = 1`

两套键协同工作：Pod 因 `defaultGpuCount` 被调度到有 GPU 的节点，又因 `resourceQuotaKey=1` 在 ResourceQuota 计量中占 1 份配额。