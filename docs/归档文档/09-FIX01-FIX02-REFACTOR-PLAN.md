# ACMP-Compute 核心流程改造执行计划

> 需求来源：`fix/fix01.md`、`fix/fix02.md`  
> 执行原则：功能完整优先、设计精简、便于内网调试、不使用 Lambda/Stream、关键连接与资源操作提供清晰注释。  
> 环境定义：部署环境没有公网，但可以访问内网 Kubernetes API、镜像仓库、模型存储及 HAMi。

## 本轮核心优先精简结果

本轮只保留以下单体同步主链：

`Cluster → Node/Gpu → EXCLUSIVE/SHARED Pool → Spec → Tenant Quota → Project → Deployment/Service`

已删除或取消：

- 旧 GPU 实时扫描、聚合视图及兼容 API，Node/Gpu 库存统一读取同步后的数据库记录；
- `resource_pool_spec` 多对多表，Spec 直接通过 `resource_pool_id` 绑定固定池；
- `poolType`、`defaultGpu*`、部署 GPU 参数等重复字段；
- OVERSELL、TrainingJob、Volcano Queue、Workspace RBAC 和 ResourceQuota 占位实现；
- 手工维护的资源池容量，池内 GPU 数量以 `gpu_device` 实际记录为准；
- 集群注册请求中的 GPU 类型、标签、污点和位置等非核心字段，这些信息由 K8s Node 同步获得。

本轮检查边界按要求仅为 `mvn -o -DskipTests clean compile`，不启动服务、不执行功能验收。

模型部署通过 `port` 配置容器监听端口和 Service 端口，默认值为 `8000`，
合法范围为 `1～65535`。Deployment、Service 和集群内访问 URL 必须使用同一个值。

## 1. 本轮目标

将当前以 Workspace 私有资源池和项目配额为核心的流程，改造成以下主流程：

```text
Cluster
  → Node
  → GPU Device
  → Resource Pool（平台固定：独享池、共享池）
  → Compute Spec
  → Tenant
  → Tenant Spec Quota
  → Project
  → Model Deployment
  → Kubernetes + HAMi
```

本轮先完成后端及验证，不修改前端。 

## 2. 第一性原则

本项目是单体 Spring Boot 服务，只采用同步调用和本地数据库事务。
不设计分布式事务、消息队列、Saga、Outbox、后台对账任务或复杂一致性机制。

### 2.1 只有核心事实可以阻断流程

必须阻断：

- kubeconfig 无法解析；
- Kubernetes 认证失败或 API Server 不可达；
- 无法读取 Node 列表；
- GPU 不存在或已属于其他资源池；
- Spec 与资源池类型不匹配；
- 租户 Spec 配额不足；
- 模型、项目、Spec 不存在；
- Kubernetes Deployment/Service 创建失败。

不能阻断：

- Kubernetes Version 暂时读取失败；
- GPU 型号、显存、UUID、Driver Version 或 CUDA Version 无法发现；
- Node Labels/Taints 中存在不认识的字段；
- GPU 使用状态暂时无法精确判断；
- Service URL 暂时不可从集群外访问；
- Deployment 已提交但 Pod 尚未 Ready。

辅助字段获取失败时保存 `null`、`UNKNOWN` 或原始 JSON，并记录日志。

### 2.2 不复制 Kubernetes 和 HAMi 的职责

平台负责：

- 集群接入和资源库存；
- GPU 池归属；
- Spec、租户配额和项目；
- 将 Spec 转换为 Pod 资源请求；
- 提交 Deployment/Service；
- 保存业务状态并查询 K8s 实际状态。

平台不负责：

- 修改 Kubernetes Scheduler；
- 修改 HAMi；
- 自研 GPU 调度算法；
- 在数据库里指定某次部署必须使用某一张物理 GPU；
- 模拟真实 K8s 调度结果。

### 2.3 内网运行要求

- 运行时不能依赖公网 API。
- Maven/npm 依赖由内网仓库或预下载缓存提供。
- vLLM 镜像必须允许配置为内网镜像地址。
- 模型路径使用内网 NFS 或平台可访问的存储。
- 应用启动不主动连接所有集群；连接发生在注册、同步和工作负载操作时。
- 客户端必须配置连接及读取超时，避免网络故障造成请求长期挂起。

## 3. 核心对象

## 3.1 Cluster

必需字段：

- `id`
- `name`
- `description`
- `kubeconfigEncrypted`
- `status`

同步字段：

- `kubernetesVersion`
- `nodeCount`
- `gpuCount`
- `lastSyncAt`
- `syncMessage`

注册流程：

```text
解析 kubeconfig
→ 创建临时 ApiClient
→ 执行最小连接检查（读取 Node）
→ 加密并保存 kubeconfig
→ 缓存正式客户端
→ 同步 Node/GPU
→ 更新集群汇总
```

注册时必须能连接 Kubernetes。版本或 GPU 辅助字段失败不影响注册。

## 3.2 Node

字段：

- `id`
- `clusterId`
- `name`
- `cpuCores`
- `memoryBytes`
- `gpuCount`
- `status`
- `labelsJson`
- `taintsJson`
- `lastSyncAt`

唯一键：`clusterId + name`。

同步时使用 upsert。本次同步未发现的旧 Node 标记为 `OFFLINE`，不直接删除。

## 3.3 GPU Device

字段：

- `id`
- `clusterId`
- `nodeId`
- `nodeName`
- `gpuIndex`
- `uuid`
- `gpuModel`
- `memoryMb`
- `driverVersion`
- `cudaVersion`
- `status`
- `resourcePoolId`
- `usageStatus`
- `lastSyncAt`

GPU 只能由集群同步发现，不提供人工新增。

稳定身份优先级：

1. GPU UUID；
2. `clusterId + nodeName + gpuIndex`。

若 Kubernetes 只能暴露节点 GPU 总数，则按照 allocatable 数量生成节点内编号。编号仅用于库存标识，不宣称是厂商物理序号。

同步不得覆盖已有 `resourcePoolId`。

## 3.4 Resource Pool

平台固定两条：

| ID | 类型 | 说明 |
|---|---|---|
| `pool-exclusive` | `EXCLUSIVE` | 整卡独占 |
| `pool-shared` | `SHARED` | HAMi 虚拟 GPU |

规则：

- 不允许创建、删除和修改池类型；
- GPU 可以从未归池状态加入一个池；
- 一张 GPU 只能属于一个池；
- 本轮不提供移出和转池；
- 池容量由归属 GPU 实时统计，不人工填写。

## 3.5 Compute Spec

代码、数据库、API 和文档统一使用 `Spec`。

字段：

- `id`
- `name`
- `displayName`
- `resourcePoolId`
- `specType`: `EXCLUSIVE` / `SHARED`
- `cpuCores`
- `memoryGib`
- `gpuCount`
- `gpuModel`，可空
- `gpuShare`，共享规格必填，只允许 `1/8`、`1/4`、`1/2`
- `status`
- `description`

规则：

- Spec 必须绑定一个固定资源池；
- `EXCLUSIVE` 只能绑定独享池；
- `SHARED` 只能绑定共享池；
- GPU 型号为空表示不限制；
- 共享比例取值 `1..100`；
- Spec 不绑定具体 GPU。

为了兼容 fix02 之前的旧部署记录，旧字段在数据库中暂时保留，但新 API 不再暴露 `OVERSELL`。

## 3.6 Tenant

Tenant 替代 Workspace。

字段：

- `id`
- `name`
- `description`
- `createdBy`
- `status`
- 时间字段

Tenant 是纯业务租户，不绑定 Cluster、Namespace、ServiceAccount、Volcano Queue 或 Resource Pool。

## 3.7 Tenant Spec Quota

字段：

- `id`
- `tenantId`
- `specId`
- `total`
- `used`

唯一键：`tenantId + specId`。

```text
remaining = total - used
```

项目直接继承租户可用 Spec，本轮不增加项目二级配额。

## 3.8 Project

字段：

- `id`
- `tenantId`
- `name`
- `description`
- `createdBy`
- `status`
- 成员

项目通过 Tenant Spec Quota 获得可用 Spec。

## 3.9 Model Deployment

字段保留当前主要结构，并调整资源归属：

- `projectId`
- `tenantId`
- `specId`
- `resourcePoolId`
- `modelId`
- `name`
- `image`
- `replicas`
- K8s Deployment/Service 名称
- `actualClusterId`
- `status`
- `serviceUrl`
- `failureMessage`

不再要求项目拥有旧的 ProjectResourceQuota，也不再从 Workspace 私有池路由。

## 4. Kubernetes 客户端设计

## 4.1 创建

`KubernetesClientManager` 负责：

- 从数据库读取并解密 kubeconfig；
- 构造官方 Kubernetes `ApiClient`；
- 设置连接、读取和调用超时；
- 按 Cluster ID 缓存；
- kubeconfig 更新时关闭旧客户端并重建；
- 集群删除时释放连接池和线程资源。

禁止使用 `computeIfAbsent` 隐藏连接构造异常，改用显式检查和同步创建，使错误日志能区分：

- 集群不存在；
- kubeconfig 解密失败；
- kubeconfig 解析失败；
- TLS/网络失败；
- Kubernetes 认证或授权失败。

## 4.2 连接检查

注册时使用临时客户端执行 `listNode`：

- 成功：允许保存；
- 401/403：明确返回认证或权限错误；
- 超时/连接拒绝：明确返回网络错误；
- 其他 API 错误：保留 HTTP code 和精简响应。

临时客户端无论成功失败都必须释放。

## 4.3 缓存与关闭

关闭客户端时：

- 从缓存移除；
- 取消排队请求；
- 关闭 Dispatcher Executor；
- 清空 ConnectionPool；
- 不关闭应用无关的共享资源。

资源操作失败时不自动无限重试。调用方可再次发起同步或部署。

## 5. 集群同步流程

```text
读取 Node 列表（核心）
→ 标记旧 Node/GPU 为 OFFLINE
→ upsert Node
→ 从 allocatable/labels/annotations 发现 GPU
→ upsert GPU，保留池归属
→ 尝试读取 Kubernetes Version（辅助）
→ 更新 Cluster 汇总与状态
```

同步成功后：

- Cluster `status=ACTIVE`
- 更新 Node/GPU 数量和时间
- `syncMessage` 保存简短结果

同步失败后：

- 保留上次库存；
- Cluster `status=ERROR`
- 记录可诊断错误；
- 不删除已有 GPU 池归属。

## 6. GPU 入池流程

接口：

```http
GET  /api/v1/resource-pools
GET  /api/v1/resource-pools/{poolId}
GET  /api/v1/resource-pools/{poolId}/gpus
POST /api/v1/resource-pools/{poolId}/gpus
```

添加请求传 GPU ID 列表。

事务校验：

1. 目标池存在且为固定池；
2. 每个 GPU 存在；
3. 每个 GPU 尚未归池；
4. 每个 GPU 状态不是已删除；
5. 全部通过后一次性更新。

任意 GPU 不合法则整批不更新。

## 7. Spec 与配额

Spec API：

```http
POST   /api/v1/specs
GET    /api/v1/specs
GET    /api/v1/specs/{id}
PUT    /api/v1/specs/{id}
DELETE /api/v1/specs/{id}
```

租户配额 API：

```http
POST   /api/v1/tenants/{tenantId}/spec-quotas
GET    /api/v1/tenants/{tenantId}/spec-quotas
PATCH  /api/v1/tenants/{tenantId}/spec-quotas/{quotaId}
DELETE /api/v1/tenants/{tenantId}/spec-quotas/{quotaId}
```

配额规则：

- `total >= used`；
- 同一租户同一 Spec 只能有一条；
- 新建部署前在同一个 Service 方法中检查并增加 `used`；
- Kubernetes 创建失败时在当前请求的异常处理中恢复 `used`；
- 删除部署后减少 `used`，最低为 0；
- Spec 被租户引用时不允许删除。

## 8. Tenant 与 Project API

```http
POST   /api/v1/tenants
GET    /api/v1/tenants
GET    /api/v1/tenants/{id}
PUT    /api/v1/tenants/{id}
DELETE /api/v1/tenants/{id}

POST   /api/v1/tenants/{tenantId}/projects
GET    /api/v1/tenants/{tenantId}/projects
GET    /api/v1/projects/{id}
PUT    /api/v1/projects/{id}
DELETE /api/v1/projects/{id}
GET    /api/v1/projects/{id}/available-specs
```

删除保护：

- Tenant 下有 Project 时不能删除；
- Tenant 有已使用配额时不能删除；
- Project 下有 Deployment 时不能删除。

## 9. 推理部署流程

请求：

- Project
- Model
- Image
- Replicas
- Spec
- 部署名称及必要模型参数

流程：

```text
读取 Project 与 Tenant
→ 读取 Model 与 Spec
→ 检查 TenantSpecQuota 剩余量
→ 根据 Spec 的资源池类型选择候选 GPU 范围
→ 选择可用 Cluster
→ 原子增加 TenantSpecQuota.used
→ 创建 pending 部署记录
→ 生成 Kubernetes Deployment/Service
→ 提交 Kubernetes
→ 成功后状态改为 submitted
→ 失败则状态改为 failed 并回退配额
```

### 9.1 独享 Spec 资源

```yaml
resources:
  requests:
    cpu: "16"
    memory: "64Gi"
  limits:
    cpu: "16"
    memory: "64Gi"
    nvidia.com/gpu: "1"
```

### 9.2 共享 Spec 资源

HAMi 资源键集中在配置中，默认：

```yaml
resources:
  requests:
    cpu: "4"
    memory: "16Gi"
  limits:
    cpu: "4"
    memory: "16Gi"
    nvidia.com/gpu: "1"
    nvidia.com/gpucores: "10"
    nvidia.com/gpumem-percentage: "10"
```

不同 HAMi 版本的资源键通过配置覆盖，不散落在业务代码中。

### 9.3 GPU 型号约束

Spec 配置 GPU 型号时，Deployment 添加对应 Node Selector。具体 label key 由配置指定，默认读取常见 NVIDIA GPU Feature Discovery 标签。

未配置型号时不添加型号 selector。

### 9.4 集群选择

本轮采用最简单且可解释的选择：

1. 查询 Spec 所属池中状态正常、型号匹配的 GPU；
2. 按 Cluster ID 排序；
3. 选择第一个拥有足够候选 GPU 的在线集群。

最终 GPU 由 Kubernetes/HAMi 调度，平台不指定设备 UUID。

### 9.5 状态

业务状态：

- `PENDING`
- `SUBMITTED`
- `RUNNING`
- `FAILED`
- `DELETING`
- `DELETED`

查询详情时读取 K8s Deployment：

- readyReplicas 大于等于 replicas → `RUNNING`
- 未 Ready → 保持 `SUBMITTED`
- K8s 暂时查询失败 → 返回数据库状态和诊断信息，不误改为 `FAILED`

## 10. 数据库兼容策略

为了不要求内网调试环境立即清空 H2：

- 新增 `tenant`、`tenant_spec_quota`、`cluster_node`、`gpu_device`；
- 给 `physical_cluster`、`compute_spec`、`project`、`model_deployment` 增加新字段；
- 新 schema 不再包含旧 Workspace、项目二级配额和人工 GPU 录入模型；
- 新 API 不再写入旧 Workspace 核心模型；
- 旧推理逻辑被新逻辑替换后，旧表只作为历史兼容结构；
- 不在本轮自动删除用户旧数据。

## 11. 代码约束

- 不使用 Lambda 表达式；
- 不使用 Stream API；
- 核心流程使用普通循环和显式条件；
- 客户端创建、关闭、同步、配额扣减和 K8s 提交处添加中文注释；
- Controller 只做协议转换和权限；
- Service 负责校验、事务与补偿；
- Mapper 只做持久化；
- 不引入消息队列、缓存服务、微服务或复杂策略框架。

## 12. 实施顺序

### 阶段 A：连接与库存

1. 加固 KubernetesClientManager。
2. 扩展 Cluster。
3. 新增 Node/GPU 表、实体和 Mapper。
4. 实现注册、同步和查询。

### 阶段 B：资源抽象

1. 初始化固定双池。
2. 实现 GPU 入池。
3. 重构 ComputeSpec。

### 阶段 C：租户

1. 新增 Tenant。
2. 新增 TenantSpecQuota。
3. Project 从 Workspace 改为 Tenant。
4. 实现项目继承 Spec。

### 阶段 D：推理

1. 调整 ModelDeployment 数据模型。
2. 实现租户配额扣减与补偿。
3. 根据 Spec 构建独享/HAMi 资源请求。
4. 选择可用 Cluster 并提交。
5. 实现状态查询和删除释放。

### 阶段 E：编译和文档

1. 更新 API 和部署文档。
2. 执行 Maven 编译并修复全部编译错误。
3. 整理后续验收所需的接口和配置说明，本轮不执行功能验收。

## 13. 后续验收参考（本轮不执行）

集群接入、Node/GPU 同步、GPU 入池、Spec、Tenant、Project、独享推理和
HAMi 共享推理等功能，由用户后续独立验收计划统一验证。

## 14. 完成定义

满足以下条件才算本轮完成：

- 后端编译通过；
- 不存在新增 Lambda/Stream；
- 核心 API 文档与代码一致；
- 真实 Kubernetes/HAMi 流程具备明确配置点；
- fix01 和 fix02 的后端设计已经落实；
- 前端未修改，等待下一阶段单独适配。

本轮不执行功能验收，真实环境运行结果由用户后续验收计划确认。
