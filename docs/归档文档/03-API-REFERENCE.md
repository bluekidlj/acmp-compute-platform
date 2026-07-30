# 1.0 API 参考

> 全部接口需在 Header 携带 `Authorization: Bearer <jwt>`，登录接口除外。
> 角色权限：`PLATFORM_ADMIN` > `ORG_ADMIN` > `INFERENCE_USER`。

## 1. 认证

### POST /api/v1/auth/login
登录获取 JWT（公开）。

请求：`{ "username", "password" }`
响应：`{ "token", "username", "role", "expiresInMs" }`

## 2. 物理集群 + 显卡

### POST /api/v1/clusters
注册集群（`PLATFORM_ADMIN`）。
```json
{ "name", "kubeconfigBase64", "gpuTypes": "NVIDIA", "location": "default", "nodeLabels": "{}", "taints": [] }
```

### GET /api/v1/clusters
列出所有集群（`PLATFORM_ADMIN`）。

### GET /api/v1/clusters/{id}
集群详情。

### DELETE /api/v1/clusters/{id}
删除集群（`PLATFORM_ADMIN`，级联关闭 K8s 客户端）。

### GET /api/v1/clusters/{id}/capacity
实时容量：`{ gpuSlots, cpu, memory }`。

### GET /api/v1/clusters/{id}/nodes
节点列表（labels / taints / allocatable / splits）。

### GET /api/v1/clusters/{id}/gpus
按 GPU 型号聚合。
```json
{
  "clusterId": "...",
  "total": [
    { "model": "NVIDIA-A100-SXM4-80GB", "memoryMb": 81920, "nodeCount": 4, "totalCards": 32, "nodeNames": [...] }
  ],
  "summary": { "gpuModelCount": 1 }
}
```

### GET /api/v1/clusters/{id}/gpu-splits
HAMi vGPU 切分列表（从 `nvidia.com/virtualization-group-*` 注解解析）。
```json
{ "clusterId": "...", "splits": [ { "poolLabel": "nvidia-7b", "memMb": 6000, "coresPct": 16, "nodeCount": 4, "nodeNames": [...] } ] }
```

### POST /api/v1/clusters/{id}/scan
触发扫描，回写 `physical_cluster` 的 `gpu_types / hami_splits / max_cpu_cores / max_memory_gib`（`PLATFORM_ADMIN`）。

## 3. 算力规格

### POST /api/v1/specs
创建规格（`PLATFORM_ADMIN`）。
```json
{
  "name": "custom-spec",
  "specType": "PHYSICAL",          // PHYSICAL / VIRTUAL / OVERSELL
  "gpuBrand": "NVIDIA",
  "defaultGpuCount": 1,
  "defaultCpuCores": 4,
  "defaultMemoryGib": 16,
  "defaultGpumemMb": 0,
  "defaultGpucores": 0,
  "nodeSelector": "{\"pool\":\"...\"}",
  "tolerations": "[{...}]",
  "description": "..."
}
```

### GET /api/v1/specs?poolType=EXCLUSIVE
列出规格（可选按 `poolType` 过滤）。

### GET /api/v1/specs/{id}

### DELETE /api/v1/specs/{id}（`PLATFORM_ADMIN`）

## 4. 工作空间（租户）

### POST /api/v1/workspaces
创建 WS（`PLATFORM_ADMIN` / `ORG_ADMIN`），自动建 3 类池。
```json
{ "name", "description", "clusterId", "memberIds": [], "maxPods": 50 }
```

### PUT /api/v1/workspaces/{id}

### DELETE /api/v1/workspaces/{id}
级联：删 K8s Namespace、3 类池、WS 成员。

### GET /api/v1/workspaces
### GET /api/v1/workspaces/{id}

### POST /api/v1/workspaces/{id}/members  `{ "userId" }`
### DELETE /api/v1/workspaces/{id}/members/{userId}
### GET /api/v1/workspaces/{id}/members

## 5. 资源池

### GET /api/v1/workspaces/{workspaceId}/pools
列 WS 下三类池。

### GET /api/v1/pools/{id}

### PATCH /api/v1/pools/{id}
修改容量 + 关联规格（覆盖式），同步 K8s ResourceQuota。
```json
{ "totalNodes": 5, "specs": ["spec-exclusive-a100"] }
```

### DELETE /api/v1/pools/{id}
仅当 `allocated_nodes=0` 时允许删除。

## 6. 项目

### POST /api/v1/workspaces/{workspaceId}/projects
创建项目。
```json
{ "name", "description", "memberIds": [] }
```

### PUT /api/v1/projects/{id}
### DELETE /api/v1/projects/{id}
级联：删项目配额、成员。

### GET /api/v1/projects/{id}
### GET /api/v1/workspaces/{workspaceId}/projects

### POST /api/v1/projects/{id}/members  `{ "userId" }`
### DELETE /api/v1/projects/{id}/members/{userId}
### GET /api/v1/projects/{id}/members

## 7. 项目配额

### POST /api/v1/projects/{projectId}/quotas
分配配额（按 pool × spec 维度）。
```json
{ "poolId": "...", "specId": "...", "totalNodes": 3 }
```

### PATCH /api/v1/projects/{projectId}/quotas/{quotaId}
```json
{ "totalNodes": 5 }
```

### DELETE /api/v1/projects/{projectId}/quotas/{quotaId}
仅当 `used_nodes=0` 时允许删除。

## 8. 模型广场

### POST /api/v1/models（`PLATFORM_ADMIN`）
### GET /api/v1/models
### GET /api/v1/models/{id}
### PUT /api/v1/models/{id}（`PLATFORM_ADMIN`）
### DELETE /api/v1/models/{id}（`PLATFORM_ADMIN`）

## 9. 推理部署

### POST /api/v1/projects/{projectId}/deployments
部署 vLLM 推理服务。
```json
{
  "name": "qwen3-svc",
  "specName": "shared-hami-a100-1/4",
  "replicas": 1,                       // 1.0 严格限 1
  "image": "vllm/vllm-openai:latest",
  "envVars": { "MODEL_NAME": "Qwen3" },
  "command": "python -m vllm.entrypoints.openai.api_server",
  "args": "--model /models/Qwen3 --host 0.0.0.0 --port 8000",
  "modelId": "<modelSquareId 可选>",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models",
  "modelName": "Qwen3-14B"
}
```

### GET /api/v1/projects/{projectId}/deployments
### GET /api/v1/projects/{projectId}/deployments/{id}
### DELETE /api/v1/projects/{projectId}/deployments/{id}

## 10. 状态码

| 状态 | 场景 |
|---|---|
| 200 | OK |
| 201 | 创建成功 |
| 400 | 请求参数错误（含业务校验失败，如配额不足、replicas≠1） |
| 401 | 未认证 |
| 403 | 无权限（角色 / 成员校验失败） |
| 404 | 资源不存在 |
| 501 | 暂未实现（超分池部署） |
