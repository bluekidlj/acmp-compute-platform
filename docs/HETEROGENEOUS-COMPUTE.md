# 异构算力调度设计

## 1. 背景与目标

ACMP-Compute 定位为**异构算力管理平台**，需要支持：
- 同一逻辑资源池关联多个不同类型的物理集群（NVIDIA GPU / Hygon DCU / Huawei Ascend）
- 同一工作空间下，部署推理服务时自动根据规格路由到对应节点
- 单次部署请求，根据规格（ComputeSpec）动态选择最优物理集群

## 2. 核心修改

### 2.1 移除"单集群绑定"约束

**原逻辑**：工作空间创建时强制所有规格落在同一物理集群，写死 `workspace.primaryClusterId`。部署时直接使用该字段。

**新逻辑**：工作空间创建时允许多规格跨集群，`workspace.primaryClusterId` 废弃（设为 null）。通过 `workspace_pool_cluster` 关联表记录工作空间涉及的物理集群。部署时由 `PoolMetadataService.pickClusterForSpec` 根据请求的 spec 动态选定目标集群。

### 2.2 新增关联表

```sql
CREATE TABLE workspace_pool_cluster (
    workspace_id         VARCHAR(36) NOT NULL,
    physical_cluster_id  VARCHAR(36) NOT NULL,
    PRIMARY KEY (workspace_id, physical_cluster_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (physical_cluster_id) REFERENCES physical_cluster(id)
);
```

### 2.3 调度核心方法签名变更

```java
// 旧：
public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec)

// 新（增加 workspaceId 参数限定集群范围）：
public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec, String workspaceId)
public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec) // 兼容旧调用
```

### 2.4 部署时动态选定集群

**ModelDeploymentService** 和 **TrainingJobService**：
1. 配额校验通过后，调用 `poolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)` 动态获取目标集群
2. K8s 资源提交到动态选定的 `clusterId`，而非 `workspace.primaryClusterId`

## 3. 调度流程

```
用户部署请求（specName="nvidia-a100-80g", replicas=2）
        ↓
PoolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)
        ↓
loadPhysicalClustersByPool(poolId, workspaceId)
    → 从 workspace_pool_cluster 查工作空间涉及的物理集群
    → 若无结果，fallback 到 resource_pool_physical_cluster
        ↓
遍历物理集群，按 spec.nodeSelector 匹配 cluster.nodeLabels
    例：spec.nodeSelector={"pool":"nvidia-gpu"}
        cluster.nodeLabels={"pool":"nvidia-gpu"} → 命中，返回该集群
        cluster.nodeLabels={"pool":"hygon-dcu"} → 不匹配
        ↓
返回 TargetCluster（clusterId + nodeLabelsJson + taintsJson）
        ↓
K8s Deployment/VolcanoJob 提交到该集群
```

## 4. 测试用例

### 场景：1 个 NVIDIA 节点 + 1 个 DCU 节点，逻辑池包含两者，部署异构推理服务

#### 前置条件

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 注册物理集群 NVIDIA | nodeLabels: `{"pool":"nvidia-gpu"}`, taints 容忍 `nvidia.com/gpu` |
| 2 | 注册物理集群 DCU | nodeLabels: `{"pool":"hygon-dcu"}`, taints 容忍 `amd.com/dcu` |
| 3 | 创建规格 `nvidia-a100-80g` | nodeSelector: `{"pool":"nvidia-gpu"}`, gpuBrand: NVIDIA |
| 4 | 创建规格 `hygon-dcu-32g` | nodeSelector: `{"pool":"hygon-dcu"}`, gpuBrand: HYGON |
| 5 | 创建逻辑池，关联上述两个物理集群 + 两个规格配额 | totalQuota 各若干 |
| 6 | 创建工作空间，申请上述两个规格 | 自动在两个集群上创建 NS |

#### 部署验证

| 步骤 | 操作 | 预期 |
|------|------|------|
| 7 | 用规格 `nvidia-a100-80g` 部署推理服务 replicas=2 | Deployment 提交到 NVIDIA 集群，Pod nodeSelector 命中 `nvidia-gpu` 节点 |
| 8 | 用规格 `hygon-dcu-32g` 部署推理服务 replicas=1 | Deployment 提交到 DCU 集群，Pod nodeSelector 命中 `hygon-dcu` 节点 |
| 9 | 确认两个 Deployment 在各自正确节点运行 | nvidia-a100-80g 的 Pod 在 NVIDIA 节点，hygon-dcu-32g 的 Pod 在 DCU 节点 |

#### 删除验证

| 步骤 | 操作 | 预期 |
|------|------|------|
| 10 | 删除工作空间 | 在 NVIDIA 和 DCU 两个集群上分别删除同名 Namespace |

## 5. 关键代码路径

```
WorkspaceService.create
  ├── 移除单集群约束（targetClusterIds.size() > 1 不再抛异常）
  ├── 遍历所有 targetClusterIds，分别创建 K8s 资源
  ├── workspace.primaryClusterId = null（废弃）
  └── 写入 workspace_pool_cluster 关联表

PoolMetadataService.loadPhysicalClustersByPool(poolId, workspaceId)
  ├── workspaceId 非空 → 从 workspace_pool_cluster 查
  └── workspaceId 为空 → 从 resource_pool_physical_cluster 查

PoolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)
  ├── loadPhysicalClustersByPool → 候选集群列表
  └── 按 spec.nodeSelector ⊆ cluster.nodeLabels 匹配

ModelDeploymentService.deploy
  └── poolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)
        → 动态 clusterId
        → clientManager.createVllmDeploymentAndService(clusterId, ns, yaml)

TrainingJobService.submit
  └── poolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)
        → 动态 clusterId
        → clientManager.applyYamlInNamespace(clusterId, ns, yaml)
```

## 6. 限制说明

1. **同一次部署请求内不分跨集群**：replicas=4 时，4 个 Pod 都会提交到同一个通过 spec 匹配选定的物理集群，不会分散到 NVIDIA 和 DCU 上。要实现分散调度，需要多次部署请求指定不同 spec。
2. **跨集群 Volcano Gang Scheduling 未支持**：Volcano Queue 是集群级资源，每个集群各自创建一份，不做跨集群队列联合调度。
3. **HAMi vGPU 配额未直接计量**：ResourceQuota 目前用 `platform.io/{spec}=1` 计量副本数，而非直接限制 `nvidia.com/gpumem`/`nvidia.com/gpucores`。此为待优化项。
4. **磁盘/存储未纳入配额**：存储配额由 K8s 集群侧 ResourceQuota 的 storage limits 或 StorageClass 策略控制，不在平台配额体系中。平台 Role 权限中已包含 PVC 操作权限。

## 7. 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `schema-h2.sql` | 新增 `workspace_pool_cluster` 表 |
| `Workspace.java` | 类注释更新，废弃 `primaryClusterId` |
| `WorkspaceMapper.java` | 新增 `insertCluster`/`findClusterIds`/`deleteClusters` |
| `WorkspaceMapper.xml` | 新增上述 3 个 SQL |
| `ResourcePoolMapper.java` | 新增 `findPhysicalClusterIdsByWorkspaceId` |
| `ResourcePoolMapper.xml` | 新增上述 SQL |
| `WorkspaceService.java` | 类注释更新，移除单集群约束，多集群 K8s 资源创建，删除时多集群清理 |
| `PoolMetadataService.java` | 新增 `workspaceId` 参数重载，`loadPhysicalClustersByPool` 支持工作空间范围限定 |
| `ModelDeploymentService.java` | 类注释更新，注入 `PoolMetadataService`，部署时动态选定集群 |
| `TrainingJobService.java` | 类注释更新，注入 `PoolMetadataService`，提交时动态选定集群 |