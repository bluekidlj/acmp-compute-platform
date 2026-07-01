# ACMP-Compute 1.0 核心链路调用追踪（Trace）

> 本文档按 **`verify.sh` / `verify-failures.sh` 的执行顺序**，逐个录制 acmp-compute 1.0 核心 REST API 的
> **HTTP 请求 / 响应报文** + **K8s 真实落地校验**。  
>
> **录制时间**：2026-06-15（Codespace + kind v0.20.0 + kindest/node v1.27.3）  
> **应用版本**：1.0.0-SNAPSHOT（已应用本轮 bug fix）  
> **原始报文**：`/tmp/opencode/acmp-verify/trace/*.txt`（每一步一个文件）  
> **服务**：`http://localhost:8080`，`Authorization: Bearer <JWT>`（除登录和无 token 用例外）  
> **环境变量**：
> - 节点：`acmp-test-control-plane`（1 control-plane，kind）
> - 注入了 6 个 `pool=*` label + 5 个 HAMi 注解 + `nvidia.com/gpu=1` allocatable
> - Volcano CRD `queues.scheduling.volcano.sh` 已 install（kind 默认不带）

---

## 调用链汇总表

| # | 方法 | 路径 | 状态码 | 关键校验 |
|---|---|---|---|---|
| 1 | POST | `/api/v1/auth/login` | 200 | 拿到 admin JWT |
| 2 | GET  | `/api/v1/specs` | 200 | 7 条预置规格 |
| 3 | POST | `/api/v1/clusters` | 200 | 集群注册，K8s 客户端缓存命中 |
| 4 | POST | `/api/v1/clusters/{id}/scan` | 200 | gpuTypes/hamiSplits 回写 DB |
| 5 | GET  | `/api/v1/clusters/{id}/gpus` | 200 | 识别 A100 |
| 6 | GET  | `/api/v1/clusters/{id}/gpu-splits` | 200 | 5 个 vGPU 切分 |
| 7 | POST | `/api/v1/workspaces` | 201 | 3 类池自动建 + K8s NS/SA/Role/RB/Queue 真实落地 |
| 8 | GET  | `/api/v1/workspaces/{id}` | 200 | 取得 3 个池 id |
| 8b| GET  | `/api/v1/specs?poolType=SHARED` | 200 | 取 `spec-shared-a100-14` |
| 9 | PATCH| `/api/v1/pools/{id}` | 200 | totalNodes=10 + 关联 spec；K8s ResourceQuota 数 = 1（**V1 修复 #2**） |
| 10| POST | `/api/v1/workspaces/{id}/projects` | 201 | 含 `memberIds=["user-admin"]` |
| 11| POST | `/api/v1/projects/{id}/quotas` | 201 | 配额 5 节点 |
| 12| POST | `/api/v1/projects/{id}/deployments` | 201 | SHARED 部署；K8s Deployment/Service 出现；Pod limits 含 `nvidia.com/gpumem` + `platform.io/shared-hami-a100-1-4` |
| 13| GET  | `/api/v1/projects/{id}` | 200 | usedNodes=1, availableNodes=4 |
| 14a| PATCH| `/api/v1/pools/{over-id}` | 200 | OVERSELL 池 totalNodes=5 |
| 14b| POST | `/api/v1/projects/{id}/quotas` | 201 | OVERSELL 配额 3 节点 |
| 14c| POST | `/api/v1/projects/{id}/deployments` | 201 | OVERSELL 部署；status=running；**K8s 上无 Deployment** |
| 15| DELETE| `/api/v1/projects/{id}/deployments/{id}` | 200 | SHARED Deployment 从 K8s 删；usedNodes 0/5 |
| 16| DELETE| `/api/v1/workspaces/{id}` | 200 | K8s NS 在 6s 内删除 |
| 17| GET  | `/api/v1/admin/audit/deployments` | 200 | orphanCount=0, quotaMismatchCount=0 |

**核心数据 id（本次录制）**：

| 对象 | id |
|---|---|
| `clusterId` | `f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40` |
| `wsId` | `30b7d3c7-ac13-4eff-9872-d19ed6f5a85a` |
| `wsNamespace` | `ws-ai-rd-45d76594` |
| `poolShared` | `56b96886-e5f8-4026-b8d7-5e88b2c02eea` |
| `poolOversell` | `0dd6e969-0a8c-4049-ae46-487668785b6b` |
| `poolExclusive` | `d735ff4d-3b29-4893-93c3-4099b0123030` |
| `specShared` | `spec-shared-a100-14` |
| `specOversell` | `spec-oversell-a100` |
| `projectId` | `a98d6b3e-1810-40a7-b185-d415a1c4a361` |
| `depShared` | `d172c63a-f9be-4f26-aef5-21f7b42c38d4` |
| `depOversell` | `adb22880-87d8-4649-bf9b-1a7752c97245` |

---

## Step 1 — 登录

> **断言**：返回 200 + JWT token  
> **代码**：`UserService.login()` / `JwtAuthenticationFilter`  
> **相关文件**：`AuthController.java`, `UserService.java`, `JwtTokenProvider.java`  
> **已修复 bug**：`data-h2.sql` 的 admin BCrypt hash 曾与 `admin123` 不匹配

### Request

```http
POST /api/v1/auth/login HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{"username":"admin","password":"admin123"}
```

### Response

```http
HTTP/1.1 200 
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
X-Content-Type-Options: nosniff
X-XSS-Protection: 1; mode=block
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: SAMEORIGIN
Content-Type: application/json
Transfer-Encoding: chunked
Date: Mon, 15 Jun 2026 07:41:34 GMT

{
  "token": "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJ1c2VyLWFkbWluIiwidXNlcm5hbWUiOiJhZG1pbiIsInJvbGUiOiJQTEFURk9STV9BRE1JTiIsIm9yZ2FuaXphdGlvbklkIjoiIiwicmVzb3VyY2VQb29sSWRzIjpbXSwiaWF0IjoxNzgxNTA5Mjk0LCJleHAiOjE3ODE1OTU2OTR9.6eqvnDheJx2THSzdVQpRAdKycgYfvNkT473wdDbV0Y-ctNDCec-2ZwQjTWbHzvj1",
  "username": "admin",
  "role": "PLATFORM_ADMIN",
  "expiresInMs": 86400000
}
```

> JWT payload（base64 解码）：`{"sub":"user-admin","username":"admin","role":"PLATFORM_ADMIN","organizationId":"","resourcePoolIds":[],"iat":1781509294,"exp":1781595694}`

---

## Step 2 — 列出 7 条预置规格

> **断言**：返回 200 + 7 条 `compute_spec`（3 EXCLUSIVE + 3 SHARED + 1 OVERSELL）  
> **代码**：`ComputeSpecController.list()` → `ComputeSpecService.list()` → `ComputeSpecMapper.findAll()`  
> **已修复 bug**：schema-h2.sql 的 `MERGE INTO compute_spec` 漏 `default_gpumem_mb` / `default_gpucores` 两列，现已对齐 schema 15 列；spec name 含 `/`（如 `shared-hami-a100-1/4`），`resource_quota_key` 用 `-` 替代（K8s resource name 合法）

### Request

```http
GET /api/v1/specs HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:41:42 GMT

[
  {"id":"spec-oversell-a100","name":"oversell-a100-mig-1/2","displayName":"A100 MIG 1/2 (超分占位)","gpuBrand":"NVIDIA","specType":"OVERSELL","poolType":"OVERSELL","defaultGpuCount":1,"defaultGpumemMb":0,"defaultGpucores":0,"defaultCpuCores":4,"defaultMemoryGib":16,"nodeSelector":"{}","tolerations":"[{\"key\":\"nvidia.com/gpu\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]","resourceQuotaKey":"platform.io/oversell-a100-mig-1-2","memoryGb":40},
  {"id":"spec-exclusive-a100","name":"exclusive-nvidia-a100-80g","displayName":"NVIDIA A100 80GB (独占整卡)","gpuBrand":"NVIDIA","specType":"PHYSICAL","poolType":"EXCLUSIVE","defaultGpuCount":1,"defaultGpumemMb":0,"defaultGpucores":0,"defaultCpuCores":8,"defaultMemoryGib":32,...,"resourceQuotaKey":"platform.io/exclusive-nvidia-a100-80g","memoryGb":80},
  {"id":"spec-exclusive-h100","name":"exclusive-nvidia-h100-80g",...,"defaultGpuCount":1,...,"resourceQuotaKey":"platform.io/exclusive-nvidia-h100-80g","memoryGb":80},
  {"id":"spec-exclusive-dcu","name":"exclusive-hygon-dcu",...,"gpuBrand":"HYGON",...,"resourceQuotaKey":"platform.io/exclusive-hygon-dcu","memoryGb":32},
  {"id":"spec-shared-a100-12","name":"shared-hami-a100-1/2",...,"defaultGpuCount":1,"defaultGpumemMb":40960,"defaultGpucores":50,"defaultCpuCores":4,"defaultMemoryGib":16,...,"resourceQuotaKey":"platform.io/shared-hami-a100-1-2","memoryGb":40},
  {"id":"spec-shared-a100-14","name":"shared-hami-a100-1/4",...,"defaultGpuCount":1,"defaultGpumemMb":20480,"defaultGpucores":25,"defaultCpuCores":2,"defaultMemoryGib":8,...,"resourceQuotaKey":"platform.io/shared-hami-a100-1-4","memoryGb":20},
  {"id":"spec-shared-a100-18","name":"shared-hami-a100-1/8",...,"defaultGpuCount":1,"defaultGpumemMb":10240,"defaultGpucores":12,"defaultCpuCores":1,"defaultMemoryGib":4,...,"resourceQuotaKey":"platform.io/shared-hami-a100-1-8","memoryGb":10}
]
```

> 关键字段确认（VIRTUAL/SHARED 池 1/4 卡 spec）：
> - `defaultGpuCount=1, defaultGpumemMb=20480, defaultGpucores=25, defaultCpuCores=2, defaultMemoryGib=8`

---

## Step 3 — 注册物理集群

> **断言**：返回 200 + 集群 id；acmp AES-256 加密 kubeconfig 落库；`KubernetesClientManager` 缓存 K8s 客户端  
> **代码**：`PhysicalClusterController.create()` → `PhysicalClusterService.register()` → `KubernetesClientManager.getOrCreate()`  
> **请求体**：`kubeconfigBase64` 字段实际是 **JSON 字符串**（脚本里 `kubeconfigBase64` 名字略不准确，acmp 接收 base64 时也接受 raw JSON；verify.sh 用 `kubectl config view --raw -o json` 转 string 后 JSON 转义）

### Request

```http
POST /api/v1/clusters HTTP/1.1
Host: localhost:8080
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...

{
  "name": "kind-acmp-test",
  "kubeconfigBase64": "{\"kind\":\"Config\",\"apiVersion\":\"v1\",...\"server\":\"https://127.0.0.1:6443\",...}",
  "gpuTypes": "NVIDIA",
  "location": "kind"
}
```

> ⚠️ `kubeconfigBase64` 字段实际是 base64 编码的 raw JSON，**为安全起见本文档不展示完整证书私钥**。  
> 真实结构：`{"kind":"Config","apiVersion":"v1","clusters":[{"name":"kind-acmp-test","cluster":{"server":"https://127.0.0.1:6443","certificate-authority-data":"<base64>"}],...}`

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:41:58 GMT

{
  "id": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40",
  "name": "kind-acmp-test",
  "description": null,
  "status": "active",
  "gpuTypes": "NVIDIA",
  "location": "kind",
  "hamiSplits": null,
  "maxCpuCores": null,
  "maxMemoryGib": null,
  "createdAt": "2026-06-15T07:41:58.273816Z",
  "updatedAt": "2026-06-15T07:41:58.273816Z"
}
```

---

## Step 4 — 扫描集群（回写 gpuTypes / hamiSplits / maxCpuCores / maxMemoryGib）

> **断言**：返回 200 + `gpuTypes` 含 `NVIDIA-A100-SXM4-80GB`，`splitCount=5`  
> **代码**：`GpuInventoryService.scanAndPersist()` — 调用 K8s `client.nodes().list()` 读 labels/annotations/allocatable；DB 更新 `physical_cluster.gpu_types` / `hami_splits` / `max_cpu_cores` / `max_memory_gib`  
> **已修复 bug**：seed 脚本同时把 `nvidia.com/gpu.product` 写到 **label**（GpuInventoryService 期望的来源），不是 annotation

### Request

```http
POST /api/v1/clusters/f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40/scan HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:02 GMT

{
  "scannedAt": "2026-06-15T07:42:02.908455Z",
  "nodeCount": 1,
  "gpuModelCount": 1,
  "splitCount": 5,
  "maxCpuCores": 4,
  "maxMemoryGib": 16,
  "gpuTypes": ["NVIDIA-A100-SXM4-80GB"],
  "splits": [
    {"poolLabel":"nvidia-14b","memMb":12000,"coresPct":33,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-28b","memMb":24000,"coresPct":50,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-40b","memMb":48000,"coresPct":66,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-7b","memMb":6000,"coresPct":16,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-80b","memMb":81920,"coresPct":100,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]}
  ]
}
```

> GPU 型号来自节点 label `nvidia.com/gpu.product=NVIDIA-A100-SXM4-80GB`  
> 切分规格来自节点 annotation `nvidia.com/virtualization-group-{7b,14b,28b,40b,80b}`

---

## Step 5 — 查询显卡

### Request

```http
GET /api/v1/clusters/f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40/gpus HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:07 GMT

{
  "total": [
    {"model":"NVIDIA-A100-SXM4-80GB","memoryMb":81920,"nodeCount":1,"totalCards":0,"nodeNames":["acmp-test-control-plane"]}
  ],
  "summary": {"gpuModelCount": 1},
  "clusterId": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40"
}
```

> `totalCards=0`：扫描时点 `nvidia.com/gpu` allocatable 暂时被 kubelet 上报覆盖（acmp 不写 status 不影响）；后端 watcher 每 8s 重新 patch。

---

## Step 6 — 查询 vGPU 切分

### Request

```http
GET /api/v1/clusters/f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40/gpu-splits HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:10 GMT

{
  "clusterId": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40",
  "splits": [
    {"poolLabel":"nvidia-14b","memMb":12000,"coresPct":33,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-28b","memMb":24000,"coresPct":50,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-40b","memMb":48000,"coresPct":66,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-7b","memMb":6000,"coresPct":16,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]},
    {"poolLabel":"nvidia-80b","memMb":81920,"coresPct":100,"nodeCount":1,"nodeNames":["acmp-test-control-plane"]}
  ]
}
```

---

## Step 7 — 创建工作空间（自动建 3 类池 + K8s NS/SA/Role/RB/Queue）

> **断言**：返回 201 + WS id；`pools[3]` 数组（EXCLUSIVE/SHARED/OVERSELL）；`namespace=ws-ai-rd-xxxxxx`  
> **代码**：`WorkspaceService.create()` — 1) 写 `workspace`；2) 写 3 个 `resource_pool`；3) K8s `createNamespace/createServiceAccount/createRole/createRoleBinding`；4) K8s `applyClusterScopedYaml`（Volcano Queue）  
> **已修复 bug**：Volcano Queue CRD 不存在时 `applyClusterScopedYaml` 会抛 "Could not find a registered handler" — 改为 `log.warn` 继续，不阻断 WS 创建

### Request

```http
POST /api/v1/workspaces HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...

{
  "name": "ai-rd",
  "description": "AI 算法部",
  "clusterId": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40",
  "maxPods": 50
}
```

### Response

```http
HTTP/1.1 201 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:18 GMT

{
  "id": "30b7d3c7-ac13-4eff-9872-d19ed6f5a85a",
  "name": "ai-rd",
  "description": "AI 算法部",
  "primaryClusterId": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40",
  "primaryClusterName": "kind-acmp-test",
  "namespace": "ws-ai-rd-45d76594",
  "volcanoQueueName": "queue-ws-ai-rd-45d76594",
  "serviceAccountName": "sa-ws-ai-rd-45d76594",
  "maxPods": 50,
  "createdBy": "user-admin",
  "status": "active",
  "pools": [
    {"id":"d735ff4d-3b29-4893-93c3-4099b0123030","poolType":"EXCLUSIVE","name":"ai-rd-exclusive","description":"ai-rd 的 EXCLUSIVE 池","totalNodes":0,"allocatedNodes":0,"availableNodes":0,"specCount":0},
    {"id":"0dd6e969-0a8c-4049-ae46-487668785b6b","poolType":"OVERSELL","name":"ai-rd-oversell","description":"ai-rd 的 OVERSELL 池","totalNodes":0,"allocatedNodes":0,"availableNodes":0,"specCount":0},
    {"id":"56b96886-e5f8-4026-b8d7-5e88b2c02eea","poolType":"SHARED","name":"ai-rd-shared","description":"ai-rd 的 SHARED 池","totalNodes":0,"allocatedNodes":0,"availableNodes":0,"specCount":0}
  ],
  "memberIds": [],
  "createdAt": null,
  "updatedAt": null
}
```

### K8s 真实落地校验

```bash
$ kubectl get ns ws-ai-rd-45d76594 -o jsonpath='{.metadata.name}{" "}{.status.phase}'
ws-ai-rd-45d76594 Active

$ kubectl get sa -n ws-ai-rd-45d76594 -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
default
sa-ws-ai-rd-45d76594

$ kubectl get role -n ws-ai-rd-45d76594 -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
role-ws-ai-rd-45d76594

$ kubectl get rolebinding -n ws-ai-rd-45d76594 -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
rb-ws-ai-rd-45d76594

$ kubectl get queue -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
queue-ws-ai-rd-45d76594
```

---

## Step 8 — 查工作空间详情（取 3 个池 id）

### Request

```http
GET /api/v1/workspaces/30b7d3c7-ac13-4eff-9872-d19ed6f5a85a HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response（节选）

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:34 GMT

{
  "id": "30b7d3c7-ac13-4eff-9872-d19ed6f5a85a",
  "name": "ai-rd",
  "namespace": "ws-ai-rd-45d76594",
  "pools": [
    {"id":"d735ff4d-3b29-4893-93c3-4099b0123030","poolType":"EXCLUSIVE",...},
    {"id":"0dd6e969-0a8c-4049-ae46-487668785b6b","poolType":"OVERSELL",...},
    {"id":"56b96886-e5f8-4026-b8d7-5e88b2c02eea","poolType":"SHARED",...}
  ],
  ...
}
```

| poolType | poolId |
|---|---|
| EXCLUSIVE | `d735ff4d-3b29-4893-93c3-4099b0123030` |
| SHARED | `56b96886-e5f8-4026-b8d7-5e88b2c02eea` |
| OVERSELL | `0dd6e969-0a8c-4049-ae46-487668785b6b` |

---

## Step 8b — 查 SHARED 规格（取 `spec-shared-a100-14`）

```http
GET /api/v1/specs?poolType=SHARED HTTP/1.1
```

Response：

```json
[
  {"id":"spec-shared-a100-12","name":"shared-hami-a100-1/2","defaultGpuCount":1,"defaultGpumemMb":40960,"defaultGpucores":50,...},
  {"id":"spec-shared-a100-14","name":"shared-hami-a100-1/4","defaultGpuCount":1,"defaultGpumemMb":20480,"defaultGpucores":25,"defaultCpuCores":2,"defaultMemoryGib":8,"resourceQuotaKey":"platform.io/shared-hami-a100-1-4",...},
  {"id":"spec-shared-a100-18","name":"shared-hami-a100-1/8","defaultGpuCount":1,"defaultGpumemMb":10240,"defaultGpucores":12,...}
]
```

---

## Step 9 — PATCH SHARED 池容量 + 关联规格（同步 K8s ResourceQuota）

> **断言**：返回 200 + `totalNodes=10`；K8s 上 ResourceQuota 数恰好 = 1（**V1 修复 #2**）  
> **代码**：`ResourcePoolService.update()` — 1) 写 `resource_pool.total_nodes`；2) 覆盖写 `resource_pool_spec`；3) K8s `client.load(YAML).createOrReplace()` — **不要用 serverSideApply**（v1 修复前会创建多个 quota）  
> **已修复 bug**：原 `KubernetesClientManager.createResourceQuotaBySpec` 用 `serverSideApply` 会因 PATCH 多次产生多个 quota 对象；改为 `createOrReplace`

### Request

```http
PATCH /api/v1/pools/56b96886-e5f8-4026-b8d7-5e88b2c02eea HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...

{
  "totalNodes": 10,
  "specs": ["spec-shared-a100-14"]
}
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:48 GMT

{
  "id": "56b96886-e5f8-4026-b8d7-5e88b2c02eea",
  "workspaceId": "30b7d3c7-ac13-4eff-9872-d19ed6f5a85a",
  "poolType": "SHARED",
  "name": "ai-rd-shared",
  "totalNodes": 10,
  "allocatedNodes": 0,
  "availableNodes": 10,
  "status": "active",
  "specs": [{"id":"spec-shared-a100-14","name":"shared-hami-a100-1/4","specType":"VIRTUAL","poolType":"SHARED"}],
  "createdAt": "2026-06-15T07:42:17.779201Z",
  "updatedAt": "2026-06-15T07:42:48.291232Z"
}
```

### K8s 真实落地校验

```bash
$ kubectl get resourcequota -n ws-ai-rd-45d76594 --no-headers
quota-shared-56b96886   10/10   ...   0s

$ kubectl get resourcequota -n ws-ai-rd-45d76594 -o jsonpath='{range .items[*]}{.metadata.name}  hard={.spec.hard}{"\n"}{end}'
quota-shared-56b96886  hard={"platform.io/shared-hami-a100-1-4":"10","pods":"100"}
```

> 关键：`spec.hard["platform.io/shared-hami-a100-1-4"]=10` 与 DB `total_nodes=10` 同步；quota 数 = 1（不重复）。

---

## Step 10 — 创建项目

> **断言**：返回 201 + projectId；`memberIds` 必含当前用户，否则后续部署报 `ForbiddenException: 无权限访问该项目`（`ModelDeploymentService.ensureCanAccessProject` 校验）  
> **已修复 bug**：`/api/v1/workspaces/{id}/projects` 接受 `memberIds` 可选项；verify.sh 默认不传，本文档手工测时显式传 `["user-admin"]`

### Request

```http
POST /api/v1/workspaces/30b7d3c7-ac13-4eff-9872-d19ed6f5a85a/projects HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...

{
  "name": "llm-team",
  "description": "LLM 算法组",
  "memberIds": ["user-admin"]
}
```

### Response

```http
HTTP/1.1 201 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:42:56 GMT

{
  "id": "a98d6b3e-1810-40a7-b185-d415a1c4a361",
  "workspaceId": "30b7d3c7-ac13-4eff-9872-d19ed6f5a85a",
  "name": "llm-team",
  "description": "LLM 算法组",
  "createdBy": "user-admin",
  "status": "active",
  "memberIds": ["user-admin"],
  "quotaByPoolType": {},
  "createdAt": null,
  "updatedAt": null
}
```

---

## Step 11 — 分配项目配额（poolId × specId × totalNodes）

> **断言**：返回 201 + quota id；DB `project_resource_quota.used_nodes=0`  
> **代码**：`ProjectQuotaService.allocate()` — 1) 校验 spec 已在池上关联；2) upsert `project_resource_quota`；3) `pool.allocated_nodes += totalNodes`

### Request

```http
POST /api/v1/projects/a98d6b3e-1810-40a7-b185-d415a1c4a361/quotas HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...

{
  "poolId": "56b96886-e5f8-4026-b8d7-5e88b2c02eea",
  "specId": "spec-shared-a100-14",
  "totalNodes": 5
}
```

### Response

```http
HTTP/1.1 201 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:01 GMT

{
  "id": "86c9dd76-bad2-459e-8ed0-ab76327f9e04",
  "projectId": "a98d6b3e-1810-40a7-b185-d415a1c4a361",
  "poolId": "56b96886-e5f8-4026-b8d7-5e88b2c02eea",
  "specId": "spec-shared-a100-14",
  "totalNodes": 5,
  "usedNodes": 0,
  "availableNodes": 5,
  "createdAt": null,
  "updatedAt": null
}
```

---

## Step 12 — 部署推理服务（SHARED 规格 → K8s Deployment/Service）

> **断言**：返回 201 + `status="running"`；K8s 上出现 `Deployment/vllm-qwen3-svc` 和 `Service/vllm-qwen3-svc-svc`；Pod limits 含 `nvidia.com/gpumem=20480` + `platform.io/shared-hami-a100-1-4=1`  
> **代码**：`ModelDeploymentService.deploy()` — 1) `ensureCanAccessProject`；2) 校验 `replicas==1`；3) 加载 spec；4) `prq.used + 1 ≤ prq.total` 校验；5) 预扣 `prq.used += 1`；6) K8s 提交 `Deployment + Service`（fabric8 builder）  
> **已修复 bug**：
> - `K8sResourceBuilder` 加 `sanitizeLabel`，Deployment metadata/pod template 不再含 `/`
> - 部署后 `updateActualClusterId` 显式持久化（V1 修复 #4）
> - `poolMapper.updateAllocated` 删了无效写（V1 修复 #1）

### Request

```http
POST /api/v1/projects/a98d6b3e-1810-40a7-b185-d415a1c4a361/deployments HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...

{
  "name": "qwen3-svc",
  "specName": "shared-hami-a100-1/4",
  "replicas": 1,
  "image": "vllm/vllm-openai:latest",
  "envVars": { "MODEL_NAME": "Qwen3-14B" },
  "command": "python",
  "args": "-m vllm.entrypoints.openai.api_server --model /models/Qwen3",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models",
  "modelName": "Qwen3-14B"
}
```

### Response

```http
HTTP/1.1 201 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:07 GMT

{
  "id": "d172c63a-f9be-4f26-aef5-21f7b42c38d4",
  "projectId": "a98d6b3e-1810-40a7-b185-d415a1c4a361",
  "workspaceId": "30b7d3c7-ac13-4eff-9872-d19ed6f5a85a",
  "resourcePoolId": "56b96886-e5f8-4026-b8d7-5e88b2c02eea",
  "specId": "spec-shared-a100-14",
  "poolType": "SHARED",
  "name": "qwen3-svc",
  "modelName": "Qwen3-14B",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models",
  "vllmImage": "vllm/vllm-openai:latest",
  "gpuPerReplica": 1,
  "replicas": 1,
  "k8sDeploymentName": "vllm-qwen3-svc",
  "k8sServiceName": "vllm-qwen3-svc-svc",
  "status": "running",
  "serviceUrl": "http://vllm-qwen3-svc-svc.ws-ai-rd-45d76594.svc.cluster.local:8000",
  "readyReplicas": null,
  "actualClusterId": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40",
  "createdBy": "user-admin"
}
```

### K8s 真实落地校验

```bash
$ kubectl get deploy -n ws-ai-rd-45d76594 --no-headers
vllm-qwen3-svc   0/1   1     0     6s

$ kubectl get svc -n ws-ai-rd-45d76594 --no-headers
vllm-qwen3-svc-svc   ClusterIP   10.96.64.123   <none>   8000/TCP   6s

# Pod resource limits（关键）
$ kubectl get deploy vllm-qwen3-svc -n ws-ai-rd-45d76594 \
    -o jsonpath='{.spec.template.spec.containers[0].resources.limits}'
{"cpu":"2","memory":"8Gi","nvidia.com/gpu":"1","nvidia.com/gpucores":"25","nvidia.com/gpumem":"20480","platform.io/shared-hami-a100-1-4":"1"}

# nodeSelector 为空（schema 改用 `{}`）+ tolerations 注入
$ kubectl get deploy vllm-qwen3-svc -n ws-ai-rd-45d76594 \
    -o jsonpath='nodeSelector={.spec.template.spec.nodeSelector}  tolerations={.spec.template.spec.tolerations}'
nodeSelector=  tolerations=[{"effect":"NoSchedule","key":"nvidia.com/gpu","operator":"Exists"}]
```

> 关键校验点：
> - `nvidia.com/gpumem=20480`：HAMi 切分内存（A100-80GB 的 1/4 = 20480 MiB）✅
> - `nvidia.com/gpucores=25`：HAMi 切分算力百分比 ✅
> - `platform.io/shared-hami-a100-1-4=1`：每副本 1 单位，触发 K8s ResourceQuota 累计（pod 起来后 `quota-shared-56b96886.used["platform.io/shared-hami-a100-1-4"]=1`）

---

## Step 13 — 校验项目已用配额（Step 12 部署后）

> **代码**：`ProjectService.getById()` → `toResponse()` → `quotaByPoolType` group  
> **已修复 bug**：
> - H2 默认返回**大写列名**，`row.get("resource_pool_id")` 永远 null → 改用大写 key
> - `cs.pool_type` 与 `rp.pool_type` 同名导致 alias 错位 → 显式 `rp_pool_type`
> - 即便修上面，NULL 仍可能成 Map key → Jackson 序列化时 500 → 兜底 `"UNKNOWN"`

### Request

```http
GET /api/v1/projects/a98d6b3e-1810-40a7-b185-d415a1c4a361 HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:19 GMT

{
  "id": "a98d6b3e-1810-40a7-b185-d415a1c4a361",
  "workspaceId": "30b7d3c7-ac13-4eff-9872-d19ed6f5a85a",
  "name": "llm-team",
  "memberIds": ["user-admin"],
  "quotaByPoolType": {
    "SHARED": [{
      "quotaId": "86c9dd76-bad2-459e-8ed0-ab76327f9e04",
      "poolId": "56b96886-e5f8-4026-b8d7-5e88b2c02eea",
      "poolName": "ai-rd-shared",
      "specId": "spec-shared-a100-14",
      "specName": "shared-hami-a100-1/4",
      "specType": "VIRTUAL",
      "totalNodes": 5,
      "usedNodes": 1,
      "availableNodes": 4
    }]
  }
}
```

> **断言通过**：`usedNodes=1, availableNodes=4`（5 - 1 = 4）✅

---

## Step 14 — 部署 OVERSELL（仅记账不调 K8s）

> **设计**：1.0 超分池**不提交 K8s**，仅写 `model_deployment` 行 + 扣 `prq.used` + `status="running"`  
> **代码**：`ModelDeploymentService.deploy()` line 164 — `if ("OVERSELL".equals(spec.getPoolType()))` 分支  
> **已修复 bug**：原代码 `updateStatus("running")` 后没 reload，`toResponse(record, null)` 用旧的 pending record → 改为 `findById(id).get()` 重新查

### Step 14a — PATCH OVERSELL 池

```http
PATCH /api/v1/pools/0dd6e969-0a8c-4049-ae46-487668785b6b
{"totalNodes": 5, "specs": ["spec-oversell-a100"]}
```

```
HTTP/1.1 200
{"id":"0dd6e969-0a8c-4049-ae46-487668785b6b","totalNodes":5,"allocatedNodes":0,"availableNodes":5,
 "specs":[{"id":"spec-oversell-a100","name":"oversell-a100-mig-1/2",...}]}
```

### Step 14b — 分配 OVERSELL 配额 3 节点

```http
POST /api/v1/projects/a98d6b3e-1810-40a7-b185-d415a1c4a361/quotas
{"poolId":"0dd6e969-0a8c-4049-ae46-487668785b6b","specId":"spec-oversell-a100","totalNodes":3}
```

```
HTTP/1.1 201
{"id":"...", "totalNodes":3, "usedNodes":0, "availableNodes":3}
```

### Step 14c — 部署 OVERSELL

```http
POST /api/v1/projects/a98d6b3e-1810-40a7-b185-d415a1c4a361/deployments
{
  "name": "oversell-test",
  "specName": "oversell-a100-mig-1/2",
  "replicas": 1,
  "image": "vllm/vllm-openai:latest",
  "modelSource": "without_weights",
  "modelIdOrPath": "/models"
}
```

Response：

```http
HTTP/1.1 201 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:32 GMT

{
  "id": "adb22880-87d8-4649-bf9b-1a7752c97245",
  "resourcePoolId": "0dd6e969-0a8c-4049-ae46-487668785b6b",
  "specId": "spec-oversell-a100",
  "poolType": "OVERSELL",
  "name": "oversell-test",
  "replicas": 1,
  "k8sDeploymentName": "vllm-oversell-test",
  "k8sServiceName": "vllm-oversell-test-svc",
  "status": "running",
  "serviceUrl": null,
  "actualClusterId": "f3f1a7e0-5663-4cd9-9c4a-2da9aa684d40"
}
```

### K8s 真实落地校验（OVERSELL 不应调 K8s）

```bash
$ kubectl get deploy -n ws-ai-rd-45d76594 --no-headers | grep vllm-oversell
（空 — K8s 上无 vllm-oversell-test）✅
```

> 1.0 设计：OVERSELL 仅记账（`prq.used += 1`），**不真实提交 K8s**。

---

## Step 15 — 删除 SHARED 部署

> **代码**：`ModelDeploymentService.delete()` — 1) K8s `deleteDeployment` + `deleteService`；2) DB `prq.used -= 1`；3) 删除 `model_deployment` 行  
> **已修复 bug**：原 `getStatus/delete` 用 `ws.getPrimaryClusterId()` 查 K8s，应优先用 `record.actualClusterId`（V1 修复 #3）

### Request

```http
DELETE /api/v1/projects/a98d6b3e-1810-40a7-b185-d415a1c4a361/deployments/d172c63a-f9be-4f26-aef5-21f7b42c38d4 HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:39 GMT

{"message":"已删除部署"}
```

### K8s 真实落地校验 + DB 状态

```bash
$ kubectl get deploy -n ws-ai-rd-45d76594 --no-headers
No resources found in ws-ai-rd-45d76594 namespace.   # SHARED Deployment 已删 ✅
```

DB 状态（`GET /api/v1/projects/{id}` 摘要）：

| poolType | specName | used / total |
|---|---|---|
| SHARED | shared-hami-a100-1/4 | 0 / 5 ✅（删了） |
| OVERSELL | oversell-a100-mig-1/2 | 1 / 3 ✅（保留） |

---

## Step 16 — 删除工作空间（级联：NS + 3 池 + WS 成员 + Volcano Queue）

> **代码**：`WorkspaceService.delete()` — 1) K8s `deleteNamespace`（同时清 SA/Role/RoleBinding/Deployment/Service/ResourceQuota/Pod）；2) K8s `deleteQueue`（Volcano Queue 集群级，独立删）；3) DB 级联删 prq/pool/project_member/workspace  
> **异步性**：K8s NS 删除是异步的，acmp 返回 200 后 K8s 还在 Terminating，verify.sh 等待 15s

### Request

```http
DELETE /api/v1/workspaces/30b7d3c7-ac13-4eff-9872-d19ed6f5a85a HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:46 GMT

{"message":"已删除"}
```

### K8s 真实落地校验

```bash
$ for i in $(seq 1 15); do
    if ! kubectl get ns ws-ai-rd-45d76594 >/dev/null 2>&1; then
        echo "  ✓ NS 在 ${i}s 后已删"
        break
    fi
    sleep 1
  done
  ✓ NS 在 6s 后已删

$ kubectl get ns ws-ai-rd-45d76594
Error from server (NotFound): namespaces "ws-ai-rd-45d76594" not found   ✅
```

> 注：Volcano Queue `queue-ws-ai-rd-45d76594` 是**集群级资源**，Namespace 删除不会带它，**1.0 已知行为**（需手动 `kubectl delete queue` 或依赖外部 Volcano operator GC）。

---

## Step 17 — 审计对账

> **代码**：`AuditService.report()` — 遍历 `model_deployment`（status=running 且非 OVERSELL 且有 `actualClusterId`），调 K8s 查 deployment，统计 orphan / quota mismatch  
> **已修复 bug**：V1 修复 #4 保证 K8s 提交后 `actual_cluster_id` 持久化

### Request

```http
GET /api/v1/admin/audit/deployments HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### Response

```http
HTTP/1.1 200 
Content-Type: application/json
Date: Mon, 15 Jun 2026 07:43:56 GMT

{
  "generatedAt": "2026-06-15T07:43:56.675531Z",
  "totalDeployments": 1,
  "orphanCount": 0,
  "quotaMismatchCount": 0,
  "orphanDeployments": [],
  "quotaMismatches": []
}
```

> **断言通过**：
> - `totalDeployments=1`（OVERSELL 占位；SHARED 已被删所以不在 running 列表）
> - `orphanCount=0`（OVERSELL 不被对账器检查）
> - `quotaMismatchCount=0`

---

## 关键交叉验证（Verify 脚本交叉引用）

| 验证脚本断言 | 对应 Step | 实际结果 |
|---|---|---|
| H1 7 条预置规格 | Step 2 | ✅ 7 条 |
| H3 集群注册 + 客户端缓存 | Step 3 | ✅ id 返回 |
| H4 扫描 nodeCount > 0 | Step 4 | ✅ nodeCount=1 |
| H5 显卡含 A100 | Step 5 | ✅ model=NVIDIA-A100-SXM4-80GB |
| H6 切分含 nvidia-7b | Step 6 | ✅ |
| H7 WS 自动建 3 类池 | Step 7 | ✅ pools[3] |
| H8 K8s NS 真实存在 | Step 7 | ✅ ws-ai-rd-45d76594 Active |
| H9 PATCH 池后 K8s ResourceQuota 数 = 1（V1 修复 #2） | Step 9 | ✅ 数=1, hard 同步 |
| H10 创建项目 200 | Step 10 | ✅ 201 |
| H11 配额 total=5 | Step 11 | ✅ |
| H12 部署 SHARED → K8s Deployment/Service | Step 12 | ✅ Deployment + Service + limits 正确 |
| H13 Pod limits 含 gpumem + platform.io | Step 12 | ✅ gpumem=20480, platform.io/shared-hami-a100-1-4=1 |
| H14 项目 used=1 | Step 13 | ✅ usedNodes=1 |
| H15 OVERSELL 部署 status=running + K8s 无 deploy | Step 14c | ✅ |
| H16 删部署 K8s 删 + used-1 | Step 15 | ✅ |
| H17 删 WS K8s NS 删 | Step 16 | ✅ 6s 内 |

---

## 已修复的 Bug 总览（本轮录制前已修复并验证）

| # | 文件 | 修复 |
|---|---|---|
| 1 | `data-h2.sql` | admin BCrypt hash → 正确 `admin123` 值 |
| 2 | `schema-h2.sql` | MERGE compute_spec 补 `default_gpumem_mb` + `default_gpucores` 列 |
| 3 | `schema-h2.sql` | spec name `shared-hami-a100-1/4` 的 `nodeSelector` 和 `resource_quota_key` 中 `/` → `-` |
| 4 | `K8sResourceBuilder.java` | 加 `sanitizeLabel`，Deployment label 不再含 `/` |
| 5 | `WorkspaceService.java` | Volcano Queue 失败 `log.warn` 继续（不阻断 WS 创建） |
| 6 | `ProjectService.java` | `findByProjectId` 用大写列名；`poolType` NULL 兜底 `"UNKNOWN"` |
| 7 | `ModelDeploymentService.java` | 超分池 `updateStatus` 后 `findById` reload |
| 8 | `GlobalExceptionHandler.java` | 加 `BadRequestException` → 400 handler |
| 9 | `SecurityConfig.java` | 加 `authenticationEntryPoint` 返回 401 |
| 10 | `scripts/install-nvidia-plugin.sh` | 后台 watcher 每 8s 重 patch nvidia.com/gpu |
| 11 | `scripts/kind-cluster.yaml` | 改 `kindest/node:v1.27.3`（v1.28 不接受 `IPv6DualStack` feature gate） |

---

## 原始报文

每一步的完整 curl -i 输出保存在 `/tmp/opencode/acmp-verify/trace/`：

```
01-login.txt              08b-specs-shared.txt   14b-quota-over.txt
02-specs.txt              09-pool-patch.txt       14c-deploy-over.txt
03-register.json           10-project.txt          15-delete-deploy.txt
04-scan.txt               11-quota.txt            16-delete-ws.txt
05-gpus.txt               12-deploy-shared.txt    17-audit.txt
06-splits.txt             13-project-check.txt    cluster_id.txt, ws_id.txt, ...
07-workspace.txt          14a-pool-over-patch.txt token.txt
08-ws-detail.txt          08-pool-ids.txt
```
