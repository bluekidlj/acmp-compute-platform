# 模型部署操作手册

## 1. 功能说明

平台提供模型部署功能，用户通过页面配置算力资源、服务镜像、环境变量等，平台自动完成配额校验、K8s 资源创建、Pod 调度。

---

## 2. 用户页面字段

### 2.1 基本信息

| 字段 | 必填 | 说明 |
|------|------|------|
| name | 是 | 部署名称 |
| description | 否 | 描述 |

### 2.2 算力资源

| 字段 | 必填 | 说明 |
|------|------|------|
| replicas | 是 | 实例数目 |
| gpuCount | 是 | 每副本 GPU 数（如 1） |
| cpuCores | 是 | 每副本 CPU 核数（如 4） |
| memoryGib | 是 | 每副本内存 GiB（如 16） |
| gpuType | 是 | GPU 类型（如 nvidia-a100-80g-1/4），用于匹配资源池规格 |

**gpuType 枚举（与 poolLabel 对应）：**
| gpuType | 说明 |
|---------|------|
| nvidia-a100-80g-1/2 | NVIDIA A100 80GB 1/2 卡 |
| nvidia-a100-80g-1/4 | NVIDIA A100 80GB 1/4 卡 |
| nvidia-a100-80g-1/8 | NVIDIA A100 80GB 1/8 卡 |
| nvidia-h100-80g-1/2 | NVIDIA H100 80GB 1/2 卡 |
| nvidia-h100-80g-1/4 | NVIDIA H100 80GB 1/4 卡 |
| nvidia-h100-80g-1/8 | NVIDIA H100 80GB 1/8 卡 |
| hygon-dcu-32g-1/2 | Hygon DCU 32GB 1/2 卡 |
| hygon-dcu-32g-1/4 | Hygon DCU 32GB 1/4 卡 |
| hygon-dcu-32g-1/8 | Hygon DCU 32GB 1/8 卡 |

### 2.3 服务配置

| 字段 | 必填 | 说明 |
|------|------|------|
| image | 是 | 镜像地址（如 vllm/vllm-openai:latest） |
| envVars | 否 | 环境变量（如 {"MODEL_NAME": "qwen3"}） |
| command | 否 | 启动命令（如 ["python", "serve.py"]） |
| args | 否 | 启动参数 |

### 2.4 模型配置

| 字段 | 必填 | 说明 |
|------|------|------|
| modelSource | 是 | with_weights / without_weights |
| modelIdOrPath | 否 | 容器内模型路径（默认 /models） |
| modelName | 否 | 模型名称（显示用） |

---

## 3. API

### 3.1 创建部署

```
POST /api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments
Content-Type: application/json
Authorization: Bearer $TOKEN
```

**请求体：**
```json
{
  "name": "qwen3-deployment",
  "replicas": 2,
  "gpuCount": 1,
  "cpuCores": 4,
  "memoryGib": 16,
  "gpuType": "nvidia-a100-80g-1/4",
  "image": "vllm/vllm-openai:latest",
  "envVars": {
    "MODEL_NAME": "Qwen3-14B"
  },
  "command": ["python", "-m", "vllm.entrypoints.openai.api_server"],
  "args": "--model /models/Qwen3-14B --host 0.0.0.0 --port 8000",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models/Qwen3-14B",
  "modelName": "Qwen3-14B"
}
```

**平台自动执行：**
1. 根据 gpuType + 资源参数查找或创建 ComputeSpec
2. 双层配额校验（L1: 池配额, L2: 工作空间配额）
3. 根据 ComputeSpec.nodeSelector 动态选定目标物理集群（异构算力路由）
4. 预扣配额
5. 生成 K8s Deployment + Service
6. 提交到选定集群

### 3.2 查询部署

```
GET /api/v1/workspaces/{workspaceId}/model-deployments
```

### 3.3 删除部署

```
DELETE /api/v1/workspaces/{workspaceId}/model-deployments/{deploymentId}
```

---

## 4. 部署流程

```
用户页面填写
    ↓
POST /api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments
    ↓
┌─────────────────────────────────────────────────────┐
│ ① 校验 workspace 成员权限                            │
│ ② 自动匹配/创建 ComputeSpec                           │
│    gpuType="nvidia-a100-80g-1/4"                     │
│    → nodeSelector = {"pool": "nvidia-a100-80g-1/4"} │
│    → defaultGpumemMb = 20480                         │
│    → defaultGpucores = 25                            │
│ ③ 双层配额校验                                       │
│ ④ PoolMetadataService.pickClusterForSpec            │
│    → 根据 nodeSelector 匹配集群节点                  │
│ ⑤ 预扣配额                                          │
│ ⑥ K8sResourceBuilder.buildVllmDeploymentAndService  │
│    → Deployment + Service YAML                      │
│ ⑦ 提交到目标集群                                     │
└─────────────────────────────────────────────────────┘
    ↓
返回部署信息 (id, k8sDeploymentName, serviceUrl)
```

---

## 5. 异构算力路由

**场景**：同一资源池关联多个物理集群（NVIDIA GPU + Hygon DCU）

**路由规则**：
- gpuType 以 `nvidia-` 开头 → 路由到 NVIDIA 集群
- gpuType 以 `hygon-` 开头 → 路由到 DCU 集群

```
gpuType="nvidia-a100-80g-1/4"
    ↓
ComputeSpec.nodeSelector = {"pool": "nvidia-a100-80g-1/4"}
    ↓
PoolMetadataService.pickClusterForSpec(poolId, spec)
    ↓
匹配 nodeLabels 包含 pool=nvidia-a100-80g-1/4 的物理集群
    ↓
提交到 NVIDIA 集群
```

---

## 6. 配额说明

**双层配额：**
- L1（池级）：total_nodes - allocated_nodes = 可用节点数
- L2（工作空间级）：max_nodes - used_nodes = 可用节点数

部署时两层配额同时校验，都通过才允许调度。

---

## 7. 相关文档

- [节点纳管与资源池创建](./NODE-ONBOARDING.md)
- [HAMi vGPU 切分管理](./HAMI-PARTITION.md)
- [异构算力调度设计](./HETEROGENEOUS-COMPUTE.md)