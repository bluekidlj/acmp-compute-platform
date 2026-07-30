# 1.0 对象模型与字段

## 1. PhysicalCluster（物理集群）

```sql
id, name, description, kubeconfig_base64_encrypted (AES),
gpu_types, hami_splits (JSON), location, node_labels (JSON), taints (JSON),
max_cpu_cores, max_memory_gib, status
```

| 字段 | 说明 |
|---|---|
| `kubeconfig_base64_encrypted` | AES 加密的 kubeconfig |
| `gpu_types` | 扫描后回写的 GPU 型号 CSV |
| `hami_splits` | 扫描后回写的 HAMi 切分 JSON 数组 |
| `max_cpu_cores` / `max_memory_gib` | 集群内 allocatable 最大值（扫描回写） |

## 2. ComputeSpec（算力规格，全局库）

```sql
id, name (UNIQUE), display_name, gpu_brand, spec_type, pool_type,
default_gpu_count, default_gpumem_mb, default_gpucores,
default_cpu_cores, default_memory_gib,
node_selector (JSON), tolerations (JSON), resource_quota_key,
memory_gb, description
```

| 字段 | 生成的 K8s 资源键 |
|---|---|
| `default_gpu_count` | `limits["nvidia.com/gpu"]` 或 `amd.com/dcu` 或 `huawei.com/ascend910` |
| `default_gpumem_mb` | `limits["nvidia.com/gpumem"]`（仅 NVIDIA） |
| `default_gpucores` | `limits["nvidia.com/gpucores"]`（仅 NVIDIA） |
| `default_cpu_cores` | `limits["cpu"]` |
| `default_memory_gib` | `limits["memory"]` |
| `node_selector` | `Pod.spec.nodeSelector` |
| `tolerations` | `Pod.spec.tolerations` |
| `resource_quota_key` | `limits["platform.io/{name}"] = 1`（每副本 1 单位，触发 K8s 配额累计） |

`specType` → `poolType` 派生：
- `PHYSICAL` → `EXCLUSIVE`
- `VIRTUAL` → `SHARED`
- `OVERSELL` → `OVERSELL`

## 3. Workspace（工作空间 = 租户）

```sql
id, name, description,
primary_cluster_id, namespace, service_account_name, volcano_queue_name,
max_pods, created_by, status
```

| 字段 | 说明 |
|---|---|
| `primary_cluster_id` | 1.0 单集群下唯一物理集群 ID |
| `namespace` | K8s Namespace 名称（`ws-{name}-{8字符}`） |
| `max_pods` | Pod 数量上限（与规格无关） |

**创建时自动建 3 个 ResourcePool**（EXCLUSIVE / SHARED / OVERSELL，初始 `total_nodes=0`）。

## 4. ResourcePool（资源池，WS 私有三类池）

```sql
id, workspace_id, pool_type (EXCLUSIVE/SHARED/OVERSELL),
name, description, primary_cluster_id,
total_nodes, allocated_nodes, status
UNIQUE (workspace_id, pool_type)
```

| 字段 | 说明 |
|---|---|
| `total_nodes` | 池总容量（卡数 / vGPU 数） |
| `allocated_nodes` | 已分配给各 Project 的 total 之和 |

关联表 `resource_pool_spec (resource_pool_id, spec_id)`：池-规格多对多。

## 5. Project（项目 = 子租户）

```sql
id, workspace_id, name, description, created_by, status
UNIQUE (workspace_id, name)
```

独立成员表 `project_member (project_id, user_id)`，与 WS 成员**互不依赖**。

## 6. ProjectResourceQuota（项目从池获得的配额）

```sql
id, project_id, resource_pool_id, spec_id, total_nodes, used_nodes
UNIQUE (project_id, resource_pool_id, spec_id)
```

| 字段 | 说明 |
|---|---|
| `total_nodes` | 管理员分给该项目的该规格节点数 |
| `used_nodes` | 已部署数（部署时扣减） |

## 7. ModelDeployment（推理服务）

```sql
id, project_id, workspace_id, resource_pool_id, spec_id, pool_type,
name, model_name, model_source, model_id_or_path, vllm_image,
gpu_per_replica, gpumem_mb, gpucores, replicas,
k8s_deployment_name, k8s_service_name, status, service_url,
actual_cluster_id, created_by
```

| 字段 | 说明 |
|---|---|
| `pool_type` | 冗余字段，方便查询 |
| `actual_cluster_id` | 1.0 = workspace.primary_cluster_id |
| `status` | `pending` / `running` / `failed` |

## 8. 关联关系图

```mermaid
erDiagram
    physical_cluster ||--o{ workspace                    : "1:N"
    workspace        ||--|| resource_pool(EXCLUSIVE)    : "auto-create"
    workspace        ||--|| resource_pool(SHARED)       : "auto-create"
    workspace        ||--|| resource_pool(OVERSELL)     : "auto-create"
    resource_pool    ||--o{ resource_pool_spec          : "M:N"
    compute_spec     ||--o{ resource_pool_spec          : "M:N"
    workspace        ||--o{ project                     : "1:N"
    project          ||--o{ project_member              : "M:N"
    project          ||--o{ project_resource_quota      : "1:N"
    resource_pool    ||--o{ project_resource_quota      : "1:N"
    compute_spec     ||--o{ project_resource_quota      : "1:N"
    project          ||--o{ model_deployment            : "1:N"
```
