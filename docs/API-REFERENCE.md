# ACMP-Compute HTTP API 参考文档（v2.0）

> 本文档作为前端工程及验收测试的唯一权威接口契约。
> 所有路径以 `/api/v1` 为前缀，端口默认 `8080`。

---

## 0. 通用说明

### 0.1 Base URL

```
http://<host>:8080
```

### 0.2 认证

除 `/api/v1/auth/login` 外，所有接口必须携带 JWT：

```
Authorization: Bearer <token>
```

JWT 中携带 `userId / username / role / resourcePoolIds`，由 `JwtAuthenticationFilter` 解析为 `UserPrincipal` 并写入 `SecurityContext`。

### 0.3 角色

| 角色 | 说明 | K8s 权限级别 |
|---|---|---|
| `PLATFORM_ADMIN` | 系统管理员，全局可见 | ClusterRole |
| `ORG_ADMIN` | 部门管理员，可建工作空间、加成员 | Role × 部门 NS |
| `TRAINING_USER` | 训练用户，工作空间内提交任务 | Role × 单 NS |
| `INFERENCE_USER` | 推理用户，工作空间内部署模型 | Role × 单 NS |

### 0.4 通用错误响应

```json
{
  "timestamp": "2026-05-26T08:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "L1 配额不足: 规格=nvidia-rtx4090-24g, total=2, allocated=2, 申请=1",
  "path": "/api/v1/workspaces"
}
```

| 状态码 | 含义 |
|---|---|
| 400 | 入参非法 / 配额不足 / 业务校验失败（`BadRequestException`） |
| 401 | 未登录或 token 失效 |
| 403 | 已登录但无权限（`ForbiddenException`） |
| 404 | 资源不存在（`ResourceNotFoundException`） |
| 500 | 内部错误（含 K8s 调用失败） |

---

## 1. 接口总览（按业务）

| 业务 | 方法 | 路径 | 角色 |
|---|---|---|---|
| **认证** | POST | `/api/v1/auth/login` | 公开 |
| **物理集群** | POST | `/api/v1/admin/physical-clusters` | PLATFORM_ADMIN |
| | GET | `/api/v1/physical-clusters` | PLATFORM_ADMIN |
| | GET | `/api/v1/physical-clusters/{id}/capacity` | 已认证 |
| | DELETE | `/api/v1/physical-clusters/{id}` | PLATFORM_ADMIN |
| **算力规格** | POST | `/api/v1/specs` | PLATFORM_ADMIN |
| | GET | `/api/v1/specs` | 已认证 |
| | GET | `/api/v1/specs/{id}` | 已认证 |
| | DELETE | `/api/v1/specs/{id}` | PLATFORM_ADMIN |
| **逻辑资源池** | POST | `/api/v1/admin/resource-pools` | PLATFORM_ADMIN |
| | GET | `/api/v1/resource-pools` | PLATFORM_ADMIN / ORG_ADMIN |
| | GET | `/api/v1/resource-pools/{id}` | 已认证 |
| **工作空间** | POST | `/api/v1/workspaces` | PLATFORM_ADMIN / ORG_ADMIN |
| | PUT | `/api/v1/workspaces/{id}` | PLATFORM_ADMIN / ORG_ADMIN |
| | DELETE | `/api/v1/workspaces/{id}` | PLATFORM_ADMIN / ORG_ADMIN |
| | GET | `/api/v1/workspaces` | 已认证 |
| | GET | `/api/v1/workspaces/{id}` | 已认证 |
| | POST | `/api/v1/workspaces/{id}/members` | PLATFORM_ADMIN / ORG_ADMIN |
| | DELETE | `/api/v1/workspaces/{id}/members/{userId}` | PLATFORM_ADMIN / ORG_ADMIN |
| | GET | `/api/v1/workspaces/{id}/members` | 已认证 |
| **凭证发放** | POST | `/api/v1/admin/workspaces/{workspaceId}/issue-credential` | PLATFORM_ADMIN |
| **模型推理** | POST | `/api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments` | 工作空间成员 |
| | GET | `/api/v1/workspaces/{workspaceId}/model-deployments` | 工作空间成员 |
| | GET | `/api/v1/workspaces/{workspaceId}/model-deployments/{id}` | 工作空间成员 |
| | DELETE | `/api/v1/workspaces/{workspaceId}/model-deployments/{id}` | 工作空间成员 |
| **训练任务** | POST | `/api/v1/workspaces/{workspaceId}/training-jobs` | 工作空间成员 |

---

## 2. 接口详细规范

### 2.1 认证

#### `POST /api/v1/auth/login`

请求：
```json
{
  "username": "admin",
  "password": "admin123"
}
```

响应：
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "admin",
  "role": "PLATFORM_ADMIN",
  "expiresInMs": 86400000
}
```

---

### 2.2 物理集群

#### `POST /api/v1/admin/physical-clusters`

注册一个 K8s 集群。`nodeLabels` 和 `taints` 是物理池的唯一存储点 —— 用于后续按规格匹配目标集群。

请求：
```json
{
  "name": "beijing-nvidia-01",
  "description": "北京 NVIDIA RTX4090 集群",
  "kubeconfigBase64": "YXBpVmVyc2lvbjogdjEK...",
  "gpuTypes": "NVIDIA",
  "location": "beijing",
  "nodeLabels": "{\"pool\":\"nvidia-gpu\"}",
  "taints": "[{\"key\":\"nvidia.com/gpu\",\"value\":\"present\",\"effect\":\"NoSchedule\"}]"
}
```

响应（201）：
```json
{
  "id": "c1-uuid",
  "name": "beijing-nvidia-01",
  "description": "北京 NVIDIA RTX4090 集群",
  "status": "active",
  "totalGpuSlots": 0,
  "gpuTypes": "NVIDIA",
  "location": "beijing",
  "createdAt": "2026-05-26T08:00:00Z"
}
```

#### `GET /api/v1/physical-clusters`

响应：
```json
[
  { "id":"c1-uuid", "name":"beijing-nvidia-01", "status":"active", "gpuTypes":"NVIDIA", "location":"beijing" }
]
```

#### `GET /api/v1/physical-clusters/{id}/capacity`

实时从 K8s 节点 `allocatable` 汇总。

响应：
```json
{ "gpuSlots": 16, "cpu": "128", "memory": "549755813888" }
```

#### `DELETE /api/v1/physical-clusters/{id}`

响应：
```json
{ "message": "已删除" }
```

---

### 2.3 算力规格

规格 = 预设的 K8s `ResourceRequirements` 模板 + `nodeSelector` + `tolerations` + `resourceQuotaKey`。

**命名规范**：`{brand}-{model}-{memory}`，如 `nvidia-rtx4090-24g`。

初始内置规格：

| name | gpuBrand | gpuKey（自动） | resourceQuotaKey |
|---|---|---|---|
| nvidia-a100-80g | NVIDIA | nvidia.com/gpu | platform.io/nvidia-a100-80g |
| nvidia-a100-40g | NVIDIA | nvidia.com/gpu | platform.io/nvidia-a100-40g |
| nvidia-rtx4090-24g | NVIDIA | nvidia.com/gpu | platform.io/nvidia-rtx4090-24g |
| hygon-dcu-32g | HYGON | amd.com/dcu | platform.io/hygon-dcu-32g |
| huawei-ascend-910b | HUAWEI_ASCEND | huawei.com/ascend910 | platform.io/huawei-ascend-910b |

#### `POST /api/v1/specs`

请求：
```json
{
  "name": "nvidia-h100-80g",
  "displayName": "NVIDIA H100 80GB",
  "gpuBrand": "NVIDIA",
  "memoryGb": 80,
  "description": "下一代 NVIDIA H100"
}
```

#### `GET /api/v1/specs`

响应：
```json
[
  { "id":"spec-nvidia-rtx4090-24g", "name":"nvidia-rtx4090-24g", "displayName":"NVIDIA RTX 4090 24GB", "gpuBrand":"NVIDIA", "memoryGb":24 },
  { "id":"spec-hygon-dcu-32g", "name":"hygon-dcu-32g", "displayName":"Hygon DCU 32GB", "gpuBrand":"HYGON", "memoryGb":32 }
]
```

---

### 2.4 逻辑资源池

逻辑池是**纯 DB 聚合容器**，本身不创建任何 K8s 资源。资源量按"规格 + 总配额"维度管理。

#### `POST /api/v1/admin/resource-pools`

请求：
```json
{
  "physicalClusterIds": ["c1-nvidia-uuid", "c2-dcu-uuid"],
  "name": "算法部资源池",
  "description": "算法部跨硬件资源池",
  "departmentCode": "algo",
  "departmentName": "算法部",
  "specQuotas": [
    { "specName": "nvidia-rtx4090-24g", "totalQuota": 1 },
    { "specName": "hygon-dcu-32g",       "totalQuota": 1 }
  ]
}
```

响应（201）：
```json
{
  "id": "pool-uuid",
  "name": "算法部资源池",
  "departmentCode": "algo",
  "departmentName": "算法部",
  "status": "active",
  "physicalClusterIds": ["c1-nvidia-uuid", "c2-dcu-uuid"],
  "specQuotas": [
    { "specId":"spec-nvidia-rtx4090-24g", "specName":"nvidia-rtx4090-24g", "totalQuota":1, "allocatedQuota":0, "availableQuota":1 },
    { "specId":"spec-hygon-dcu-32g",       "specName":"hygon-dcu-32g",       "totalQuota":1, "allocatedQuota":0, "availableQuota":1 }
  ],
  "createdAt": "2026-05-26T08:10:00Z"
}
```

#### `GET /api/v1/resource-pools`

可选查询参数 `?physicalClusterId=<id>` 过滤。

#### `GET /api/v1/resource-pools/{id}`

返回同 POST 创建响应。

---

### 2.5 工作空间

工作空间 = K8s Namespace（100% 对应），是用户唯一可见的资源边界。

**创建工作空间会触发**：
1. L1 配额校验：`resource_pool_spec_quota.allocated + 申请 ≤ total`
2. 按规格 `nodeSelector` 选定目标物理集群（所有 spec 必须落到同一集群）
3. K8s 创建：`Namespace` → `ResourceQuota`(`platform.io/{spec}=max`) → `SA` → `Role` → `RoleBinding` → `VolcanoQueue`
4. 写 `resource_pool_spec_quota.allocated += max`、`workspace_pool_spec_quota.max = req`

#### `POST /api/v1/workspaces`

请求：
```json
{
  "name": "llm-training",
  "description": "Qwen3 大模型训练",
  "resourcePoolId": "pool-uuid",
  "specQuotas": [
    { "specName": "nvidia-rtx4090-24g", "maxQuota": 1 }
  ],
  "maxPods": 50
}
```

响应（201）：
```json
{
  "id": "ws-uuid",
  "name": "llm-training",
  "description": "Qwen3 大模型训练",
  "resourcePoolId": "pool-uuid",
  "resourcePoolName": "算法部资源池",
  "namespace": "ws-llm-training-a1b2c3d4",
  "volcanoQueueName": "queue-ws-llm-training-a1b2c3d4",
  "primaryClusterId": "c1-nvidia-uuid",
  "maxPods": 50,
  "createdBy": "user-admin",
  "status": "active",
  "specQuotas": [
    { "specId":"spec-nvidia-rtx4090-24g", "specName":"nvidia-rtx4090-24g", "maxQuota":1, "usedQuota":0, "availableQuota":1 }
  ],
  "createdAt": "2026-05-26T08:15:00Z"
}
```

#### `PUT /api/v1/workspaces/{id}`

只更新 `name` 与 `description`。

请求：
```json
{ "name": "llm-training-v2", "description": "...", "resourcePoolId": "pool-uuid" }
```

#### `DELETE /api/v1/workspaces/{id}`

级联删除 K8s Namespace 内所有资源，并释放 `resource_pool_spec_quota.allocated`。

#### `GET /api/v1/workspaces` / `GET /api/v1/workspaces/{id}`

返回同创建响应。

#### 成员管理

`POST /api/v1/workspaces/{id}/members`
```json
{ "userId": "user-zhangsan-uuid" }
```

`DELETE /api/v1/workspaces/{id}/members/{userId}` — 移除成员
`GET /api/v1/workspaces/{id}/members` — 返回 `["user-id-1", "user-id-2"]`

---

### 2.6 凭证发放

#### `POST /api/v1/admin/workspaces/{workspaceId}/issue-credential`

平台代理：从工作空间的 SA 提取 token + CA，生成限定 namespace 的 kubeconfig。

请求：
```json
{ "username": "zhangsan", "expireDays": 30 }
```

响应：
```json
{
  "kubeconfig": "apiVersion: v1\nkind: Config\n...",
  "namespace": "ws-llm-training-a1b2c3d4",
  "clusterName": "beijing-nvidia-01",
  "serviceAccountName": "sa-ws-llm-training-a1b2c3d4",
  "message": "凭证已生成，有效期 30 天，用户: zhangsan"
}
```

---

### 2.7 模型推理部署（vLLM）

#### `POST /api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments`

请求：
```json
{
  "name": "qwen3-svc",
  "specName": "nvidia-rtx4090-24g",
  "replicas": 1,
  "modelName": "Qwen3-7B-Instruct",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models/qwen3",
  "vllmImage": "vllm/vllm-openai:latest",
  "hostModelPath": "/data/models/Qwen3"
}
```

平台行为：
1. 校验调用者 ∈ workspace_member
2. 校验 workspace.resourcePoolId == poolId
3. 加载 spec
4. **双层配额校验** (L1 pool / L2 workspace)
5. **双层配额预扣**
6. 构建 Deployment YAML —— `limits` 内含：
   - `nvidia.com/gpu = 1` （按 spec.gpuBrand 自动选键）
   - `cpu = 8`, `memory = 32Gi`
   - `platform.io/nvidia-rtx4090-24g = 1` （让 ResourceQuota 真实生效）
7. `nodeSelector = spec.nodeSelector`，`tolerations = spec.tolerations`
8. 提交 K8s；失败时回滚配额

响应（201）：
```json
{
  "id": "dep-uuid",
  "workspaceId": "ws-uuid",
  "resourcePoolId": "pool-uuid",
  "specId": "spec-nvidia-rtx4090-24g",
  "name": "qwen3-svc",
  "modelName": "Qwen3-7B-Instruct",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models/qwen3",
  "vllmImage": "vllm/vllm-openai:latest",
  "gpuPerReplica": 1,
  "replicas": 1,
  "k8sDeploymentName": "vllm-qwen3-svc",
  "k8sServiceName": "vllm-qwen3-svc-svc",
  "status": "running",
  "serviceUrl": "http://vllm-qwen3-svc-svc.ws-llm-training-a1b2c3d4.svc.cluster.local:8000",
  "readyReplicas": 1,
  "createdBy": "user-zhangsan-uuid",
  "createdAt": "2026-05-26T08:20:00Z"
}
```

#### `GET /api/v1/workspaces/{workspaceId}/model-deployments`

列表。

#### `GET /api/v1/workspaces/{workspaceId}/model-deployments/{id}`

含 K8s 实时 `readyReplicas`。

#### `DELETE /api/v1/workspaces/{workspaceId}/model-deployments/{id}`

删 K8s Deployment + Service → 回滚双层配额 → 删 DB。

---

### 2.8 训练任务（VolcanoJob）

#### `POST /api/v1/workspaces/{workspaceId}/training-jobs`

请求：
```json
{
  "jobName": "qwen3-finetune-01",
  "image": "registry.local/training:torch-2.1",
  "replicas": 2,
  "specName": "nvidia-rtx4090-24g",
  "command": ["python", "train.py", "--epochs", "3"]
}
```

平台行为：与推理部署相同的"规格→Pod→双层配额→K8s"路径，落到 VolcanoJob。

响应（201）：
```json
{ "jobName": "qwen3-finetune-01", "message": "已提交" }
```

---

## 3. 核心流程示例

### 流程 A：系统管理员（PLATFORM_ADMIN）搭建一个跨硬件平台

> 目标：把两台机器（一台 NVIDIA、一台 DCU）纳入平台，建好算法部的逻辑池。

#### A-1. 登录

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "admin", "password": "admin123" }
```

返回 `token`，后续所有请求带 `Authorization: Bearer <token>`。

#### A-2. 注册物理集群 1（NVIDIA RTX4090）

K8s 侧前置工作（管理员手动）：
```bash
kubectl label node node-nvidia pool=nvidia-gpu
kubectl taint node node-nvidia nvidia.com/gpu=present:NoSchedule
```

```http
POST /api/v1/admin/physical-clusters
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "name": "beijing-nvidia-01",
  "description": "北京 NVIDIA 集群",
  "kubeconfigBase64": "<base64 encoded kubeconfig>",
  "gpuTypes": "NVIDIA",
  "location": "beijing",
  "nodeLabels": "{\"pool\":\"nvidia-gpu\"}",
  "taints": "[{\"key\":\"nvidia.com/gpu\",\"value\":\"present\",\"effect\":\"NoSchedule\"}]"
}
```

返回 `id = c1-nvidia-uuid`。

#### A-3. 注册物理集群 2（Hygon DCU）

```bash
kubectl label node node-dcu pool=hygon-dcu
kubectl taint node node-dcu amd.com/dcu=present:NoSchedule
```

```http
POST /api/v1/admin/physical-clusters
{
  "name": "beijing-dcu-01",
  "kubeconfigBase64": "<base64>",
  "gpuTypes": "HYGON",
  "location": "beijing",
  "nodeLabels": "{\"pool\":\"hygon-dcu\"}",
  "taints": "[{\"key\":\"amd.com/dcu\",\"value\":\"present\",\"effect\":\"NoSchedule\"}]"
}
```

返回 `id = c2-dcu-uuid`。

#### A-4. 创建算法部逻辑池（关联两个集群）

```http
POST /api/v1/admin/resource-pools
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "physicalClusterIds": ["c1-nvidia-uuid", "c2-dcu-uuid"],
  "name": "算法部资源池",
  "description": "算法部跨硬件资源池",
  "departmentCode": "algo",
  "departmentName": "算法部",
  "specQuotas": [
    { "specName": "nvidia-rtx4090-24g", "totalQuota": 1 },
    { "specName": "hygon-dcu-32g",       "totalQuota": 1 }
  ]
}
```

返回 `id = pool-algo-uuid`。

**验证点**：
- 数据库：`resource_pool` 有一行；`resource_pool_physical_cluster` 有 2 行；`resource_pool_spec_quota` 有 2 行（allocated 都是 0）
- K8s 侧：**无变化**（逻辑池不创建任何 K8s 资源）

#### A-5. 查询容量（核对节点上报）

```http
GET /api/v1/physical-clusters/c1-nvidia-uuid/capacity
Authorization: Bearer <admin-token>
```

期望：`gpuSlots ≥ 1`（节点真有一张卡），否则 4090 没正确暴露给 device plugin。

---

### 流程 B：平台管理员/部门管理员（ORG_ADMIN）建工作空间 + 发凭证

> 目标：为 LLM 训练团队创建工作空间 `llm-training`，关联 RTX4090 规格，添加成员张三。

#### B-1. 登录（这里用 PLATFORM_ADMIN，ORG_ADMIN 流程相同）

```http
POST /api/v1/auth/login
{ "username": "admin", "password": "admin123" }
```

#### B-2. 查看可用资源池

```http
GET /api/v1/resource-pools
Authorization: Bearer <admin-token>
```

响应里看到 `pool-algo-uuid`，及其 `specQuotas` 中 `nvidia-rtx4090-24g.availableQuota=1`。

#### B-3. 创建工作空间（绑定 RTX4090 规格 1 张卡）

```http
POST /api/v1/workspaces
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "name": "llm-training",
  "description": "Qwen3 微调工作空间",
  "resourcePoolId": "pool-algo-uuid",
  "specQuotas": [
    { "specName": "nvidia-rtx4090-24g", "maxQuota": 1 }
  ],
  "maxPods": 30
}
```

返回 `id = ws-llm-uuid`，含 `namespace`、`primaryClusterId=c1-nvidia-uuid`（按 spec 自动选定）。

**验证点**：
- K8s `kubectl get ns | grep ws-llm-training` 应能看到新 namespace
- `kubectl get resourcequota -n <ns>` 看到 `platform.io/nvidia-rtx4090-24g: 1`
- `kubectl get sa -n <ns>` 看到 `sa-ws-llm-training-...`
- DB `resource_pool_spec_quota.allocated_quota` 从 0 变 1

#### B-4. 添加成员

预设 DB 中有用户 `zhangsan`，role=TRAINING_USER。

```http
POST /api/v1/workspaces/ws-llm-uuid/members
Authorization: Bearer <admin-token>
Content-Type: application/json

{ "userId": "user-zhangsan-uuid" }
```

#### B-5. 给张三签发 kubeconfig（可选）

```http
POST /api/v1/admin/workspaces/ws-llm-uuid/issue-credential
Authorization: Bearer <admin-token>
Content-Type: application/json

{ "username": "zhangsan", "expireDays": 30 }
```

返回 `kubeconfig` 字符串可发给张三让他直接 `kubectl` 使用。

---

### 流程 C：训练用户（TRAINING_USER）部署推理服务 + 跑训练

> 目标：张三登录平台，看到自己的工作空间，部署 vLLM 推理 + 提交训练任务。

#### C-1. 登录

```http
POST /api/v1/auth/login
{ "username": "zhangsan", "password": "<his-password>" }
```

返回的 token 中 `role=TRAINING_USER`。

#### C-2. 查看自己的工作空间

```http
GET /api/v1/workspaces
Authorization: Bearer <zhangsan-token>
```

响应中能看到 `llm-training`（因为他是该 ws 的 member）。

```http
GET /api/v1/workspaces/ws-llm-uuid
Authorization: Bearer <zhangsan-token>
```

观察 `specQuotas[0]`：`maxQuota=1, usedQuota=0, availableQuota=1`。

#### C-3. 部署 vLLM 推理服务

```http
POST /api/v1/resource-pools/pool-algo-uuid/workspaces/ws-llm-uuid/model-deployments
Authorization: Bearer <zhangsan-token>
Content-Type: application/json

{
  "name": "qwen3-svc",
  "specName": "nvidia-rtx4090-24g",
  "replicas": 1,
  "modelName": "Qwen3-7B-Instruct",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models/qwen3",
  "vllmImage": "vllm/vllm-openai:latest",
  "hostModelPath": "/data/models/Qwen3"
}
```

平台逐步执行（**核心隔离链全程在这里）**：
1. ✅ 校验张三 ∈ workspace_member(ws-llm-uuid)
2. ✅ 校验 ws.resourcePoolId == pool-algo-uuid
3. ✅ 加载 spec `nvidia-rtx4090-24g`
4. ✅ L1 校验：`resource_pool_spec_quota.allocated(=1) + 1 ≤ total(=1)`? **❌ 不通过**
   - 因为 B-3 已经占了 1，再申请 1 会超
   - 期望返回 400：`L1 配额不足: 规格=nvidia-rtx4090-24g, total=1, allocated=1, 申请=1`

**注意**：本例 B-3 已经把 1 张卡全部"分给"工作空间了，C-3 再申请会被拦住。如果想让 C-3 成功，需要在 A-4 把 `nvidia-rtx4090-24g.totalQuota` 提到 2，或在 B-3 把 `maxQuota` 留 1 给后续部署。

下面假设容量充裕，部署成功，返回：
```json
{
  "id": "dep-uuid",
  "workspaceId": "ws-llm-uuid",
  "resourcePoolId": "pool-algo-uuid",
  "specId": "spec-nvidia-rtx4090-24g",
  "name": "qwen3-svc",
  "status": "running",
  "serviceUrl": "http://vllm-qwen3-svc-svc.ws-llm-training-a1b2c3d4.svc.cluster.local:8000",
  "readyReplicas": 1,
  ...
}
```

**验证点（关键，证明隔离生效）**：
```bash
kubectl describe resourcequota -n ws-llm-training-a1b2c3d4
# 期望看到：
# Resource                                  Used  Hard
# platform.io/nvidia-rtx4090-24g            1     1
```
若 `Used=0` 说明 Pod 没带 `platform.io/*` 字段——隔离链断了。

```bash
kubectl get pod -n ws-llm-training-a1b2c3d4 -o jsonpath='{.items[0].spec.nodeSelector}'
# 期望：{"pool":"nvidia-gpu"}
kubectl get pod -n ws-llm-training-a1b2c3d4 -o jsonpath='{.items[0].spec.tolerations}'
# 期望：[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]
```

#### C-4. 查看部署状态

```http
GET /api/v1/workspaces/ws-llm-uuid/model-deployments/dep-uuid
Authorization: Bearer <zhangsan-token>
```

#### C-5. 提交训练任务

```http
POST /api/v1/workspaces/ws-llm-uuid/training-jobs
Authorization: Bearer <zhangsan-token>
Content-Type: application/json

{
  "jobName": "qwen3-finetune-01",
  "image": "registry.local/training:torch-2.1",
  "replicas": 1,
  "specName": "nvidia-rtx4090-24g",
  "command": ["python", "train.py", "--epochs", "3"]
}
```

走与推理部署相同的"规格→双层配额→VolcanoJob"路径。返回：
```json
{ "jobName": "qwen3-finetune-01", "message": "已提交" }
```

**验证点**：
```bash
kubectl get job.batch.volcano.sh -n ws-llm-training-a1b2c3d4
kubectl describe pod <volcano-pod> -n ws-llm-training-a1b2c3d4 \
  | grep -E "nodeSelector|tolerations|nvidia.com/gpu|platform.io"
```

#### C-6. 跨硬件：在同一逻辑池里跑 DCU 任务

> 假设管理员另外建了工作空间 `cv-training`，绑 `hygon-dcu-32g` 规格。

部署到 DCU 集群只需把 `specName` 改为 `hygon-dcu-32g`。平台自动：
- 在 `PoolMetadataService` 内匹配 `nodeSelector={"pool":"hygon-dcu"}` 找到 `c2-dcu-uuid`
- 在 `K8sResourceBuilder` 内把 `limits.nvidia.com/gpu` 换成 `limits.amd.com/dcu`
- ResourceQuota 计量键自动是 `platform.io/hygon-dcu-32g`

#### C-7. 删除部署 → 配额自动归还

```http
DELETE /api/v1/workspaces/ws-llm-uuid/model-deployments/dep-uuid
Authorization: Bearer <zhangsan-token>
```

**验证点**：
```http
GET /api/v1/workspaces/ws-llm-uuid
# specQuotas[0].usedQuota 从 1 → 0
# specQuotas[0].availableQuota 从 0 → 1
```

```bash
kubectl describe resourcequota -n ws-llm-training-a1b2c3d4
# platform.io/nvidia-rtx4090-24g  Used: 0  Hard: 1
```

---

## 4. 资源量流转一图汇总

```
1. 物理服务器上架 ─────────────► kubectl label/taint node → K8s 自动 capacity 上报
                                  ↓
2. POST /admin/physical-clusters ► DB: physical_cluster (node_labels, taints)
                                  ↓
3. POST /specs ─────────────────► DB: compute_spec (nodeSelector, tolerations, resourceQuotaKey)
                                  ↓
4. POST /admin/resource-pools ──► DB: resource_pool + resource_pool_spec_quota (total)
                                  ↓
5. POST /workspaces ────────────► L1 配额校验 → 选目标集群 → K8s: Namespace + ResourceQuota(platform.io/{spec}=max) + SA/RBAC + VolcanoQueue
                                  DB: workspace + workspace_pool_spec_quota (max)
                                  DB: resource_pool_spec_quota.allocated += max
                                  ↓
6. POST /.../model-deployments ─► L1+L2 校验 → 预扣 → K8s Deployment (resources含platform.io/{spec}=1)
                                  Pod nodeSelector + tolerations 来自 spec
                                  ↓
7. K8s Scheduler ──────────────► nodeSelector + taint 匹配 node-nvidia
                                  ↓
8. Kubelet 创建容器 ───────────► Cgroup 限制 cpu/mem，HAMi 限制 GPU
                                  K8s ResourceQuota.used += 1
                                  ↓
9. DELETE /.../{id} ───────────► 删 Deployment → 回滚双层配额 → 删 DB 记录
                                  K8s ResourceQuota.used -= 1
```

---

## 5. 验证清单（功能正确性自检）

| # | 校验点 | 工具 |
|---|---|---|
| 1 | 登录返回 JWT 中含正确 role | 解 base64 payload |
| 2 | 物理集群注册后 node_labels/taints 写入 DB | `SELECT * FROM physical_cluster` |
| 3 | 逻辑池不创建 K8s 资源 | `kubectl get ns/quota -A` 应无新增 |
| 4 | 工作空间创建后 Namespace + ResourceQuota 同时出现 | `kubectl get ns,resourcequota -n <ns>` |
| 5 | ResourceQuota 用的是 `platform.io/{spec}` 不是 `nvidia.com/gpu` | `kubectl describe rq -n <ns>` |
| 6 | 部署后 Pod 携带 `platform.io/{spec}=1` 计量 | `kubectl get pod -o yaml \| grep platform.io` |
| 7 | Pod nodeSelector / tolerations 与 spec 一致 | `kubectl get pod -o yaml` |
| 8 | DCU 任务的 Pod limits 是 `amd.com/dcu` 不是 `nvidia.com/gpu` | 同上 |
| 9 | 配额超额时部署返回 400 + 含可读 message | curl 后看响应 |
| 10 | 删除部署后 ResourceQuota.used 归零、DB used_quota 归零 | `kubectl describe rq` + `GET /workspaces/{id}` |
| 11 | 工作空间删除后 Namespace 级联删除 | `kubectl get ns` |
| 12 | 训练任务也走双层配额（不绕过） | 提交后看 `workspace_pool_spec_quota.used_quota` |

---

## 6. curl 一键复盘

```bash
# 0. 登录
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

# 1. 注册物理集群
curl -X POST http://localhost:8080/api/v1/admin/physical-clusters \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<'EOF'
{
  "name":"beijing-nvidia-01",
  "kubeconfigBase64":"...",
  "gpuTypes":"NVIDIA",
  "location":"beijing",
  "nodeLabels":"{\"pool\":\"nvidia-gpu\"}",
  "taints":"[{\"key\":\"nvidia.com/gpu\",\"value\":\"present\",\"effect\":\"NoSchedule\"}]"
}
EOF

# 2. 创建资源池（替换 c1 为上一步返回 id）
curl -X POST http://localhost:8080/api/v1/admin/resource-pools \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "physicalClusterIds":["c1"],
        "name":"算法部资源池",
        "departmentCode":"algo",
        "departmentName":"算法部",
        "specQuotas":[{"specName":"nvidia-rtx4090-24g","totalQuota":2}]
      }'

# 3. 创建工作空间
curl -X POST http://localhost:8080/api/v1/workspaces \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "name":"llm-training",
        "resourcePoolId":"pool-id",
        "specQuotas":[{"specName":"nvidia-rtx4090-24g","maxQuota":2}]
      }'

# 4. 部署 vLLM
curl -X POST http://localhost:8080/api/v1/resource-pools/pool-id/workspaces/ws-id/model-deployments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "name":"qwen3-svc",
        "specName":"nvidia-rtx4090-24g",
        "replicas":1,
        "modelSource":"with_weights",
        "modelIdOrPath":"/models/qwen3",
        "hostModelPath":"/data/models/Qwen3"
      }'

# 5. 提交训练
curl -X POST http://localhost:8080/api/v1/workspaces/ws-id/training-jobs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "jobName":"qwen3-finetune",
        "image":"registry.local/training:torch-2.1",
        "replicas":1,
        "specName":"nvidia-rtx4090-24g",
        "command":["python","train.py"]
      }'
```
