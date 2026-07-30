# 1.0 整体架构

## 一、一句话定位

> **ACMP-Compute 1.0 = 把"集群 → 工作空间（租户）→ 三类资源池 → 项目 → 推理服务"翻译成 K8s 原生对象的语义化包装层。**
>
> 平台不发明新调度器，而是把"用户、规格、配额、项目"翻译成 K8s 已经能听懂的对象。

## 二、核心对象模型

```
PhysicalCluster                       ← 真实 K8s Cluster
    │
    └── Workspace (租户) 1:1          ← 1 K8s Namespace + 1 Volcano Queue
           │                            primaryClusterId (1.0 单集群)
           ├── ResourcePool × 3        ← EXCLUSIVE / SHARED / OVERSELL
           │     │                      totalNodes / allocatedNodes
           │     └── ComputeSpec × N   ← 池关联的规格
           │
           └── Project × N             ← 配额真正拥有者
                  │
                  ├── ProjectMember    ← 独立于 WS 成员
                  ├── ProjectResourceQuota
                  │     (按 pool × spec 维度：totalNodes / usedNodes)
                  │
                  └── ModelDeployment  ← 推理服务
                        spec.poolType → 路由到 Project 拥有的同类型池
                        replicas = 1 （1.0 限制）
```

## 三、关键设计原则

| 原则 | 实现 |
|---|---|
| 物理属性归物理集群 | 节点 labels/taints/allocatable 由 K8s 真实查询 |
| 算力规格全局唯一 | `compute_spec` 单表，所有池引用同一份 |
| 池私有化 | `resource_pool` 按 `workspace_id` 隔离；每 WS 自动建三类池 |
| 项目拥有配额 | `project_resource_quota` 是部署真正扣减对象 |
| 调度靠规格 | spec.poolType 决定 deployment 路由到哪类池 |
| 单集群（1.0） | 1 workspace = 1 cluster；project 部署自动落到该 cluster |

## 四、规格 (ComputeSpec) 与池类型的对应

| specType | poolType | 资源键 | 说明 |
|---|---|---|---|
| `PHYSICAL` | `EXCLUSIVE` | `nvidia.com/gpu=1` / `amd.com/dcu=1` | 整卡独占 |
| `VIRTUAL` | `SHARED` | `nvidia.com/gpumem + gpucores` | HAMi vGPU 切分 |
| `OVERSELL` | `OVERSELL` | `platform.io/{spec}=1`（仅记账）| 1.0 占位，**不实际提交 K8s** |

预置 7 条标准规格（schema-h2.sql 末尾）：
- `exclusive-nvidia-a100-80g` / `exclusive-nvidia-h100-80g` / `exclusive-hygon-dcu`
- `shared-hami-a100-1/2` / `shared-hami-a100-1/4` / `shared-hami-a100-1/8`
- `oversell-a100-mig-1/2`

## 五、三层配额体系

```
池容量层     resource_pool.total_nodes              (管理员设定)
项目配额层   project_resource_quota.total_nodes     (管理员分配)
部署使用层   project_resource_quota.used_nodes      (部署时扣减)
```

约束：
- `project.used ≤ project.total`
- 同一项目所有 project.total 之和 ≤ 池.allocated ≤ 池.total

## 六、K8s 资源落地

工作空间创建时一次性落地：

| 资源 | K8s 形态 | 命名 |
|---|---|---|
| Namespace | `Namespace` | `ws-{name}-{8字符}` |
| ServiceAccount | `ServiceAccount` | `sa-{ns}` |
| Role | `Role` | `role-{ns}` |
| RoleBinding | `RoleBinding` | `rb-{ns}` |
| Volcano Queue | `scheduling.volcano.sh/v1beta1 Queue` | `queue-{ns}` |
| ResourceQuota | `ResourceQuota` | `quota-{type}-{poolId前8}`（按池补丁时建） |

## 七、模块清单

| 模块 | 关键类 |
|---|---|
| 集群 | `PhysicalClusterService`, `PhysicalClusterController`, `GpuInventoryService` |
| 显卡库存 | `GpuInventoryService`, `GpuController`（含在 `PhysicalClusterController`） |
| 规格 | `ComputeSpecService`, `ComputeSpecController` |
| 工作空间（租户） | `WorkspaceService`, `WorkspaceController` |
| 资源池 | `ResourcePoolService`, `ResourcePoolController` |
| 项目 | `ProjectService`, `ProjectController` |
| 项目配额 | `ProjectQuotaService`, `ProjectQuotaController` |
| 模型广场 | `ModelService`, `ModelController` |
| 推理部署 | `ModelDeploymentService`, `ModelDeploymentController` |
| K8s 客户端 | `KubernetesClientManager`（fabric8 缓存） |
| K8s 资源构建 | `K8sResourceBuilder`（fabric8 builder API） |

## 八、文档索引

- [02-RESOURCE-MODEL.md](./02-RESOURCE-MODEL.md) — 详细对象模型与字段
- [03-API-REFERENCE.md](./03-API-REFERENCE.md) — 完整 API 列表
- [04-DEPLOYMENT-FLOW.md](./04-DEPLOYMENT-FLOW.md) — 部署推理服务全流程
- [05-EXAMPLE.md](./05-EXAMPLE.md) — curl 示例
- [DEPLOY.md](./DEPLOY.md) — Docker 部署
