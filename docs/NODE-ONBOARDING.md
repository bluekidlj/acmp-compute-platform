# 节点纳管与资源池创建操作手册

## 概述

本文档说明如何将 K8s 集群中的新节点纳入平台管理，并根据节点规格创建逻辑资源池。

**两种模式：**

| 模式 | 适用场景 | 节点标签 | 每节点可调度数 |
|------|----------|----------|---------------|
| 物理规格 | 物理机 / 无 HAMi 环境 | 无 pool 标签 | nodeCount |
| HAMi 虚拟切分 | 启用了 HAMi vGPU 切分 | pool=xxx | 由切分规格决定 |

---

## 流程总览

```
┌──────────────────────────────────────────────────────────────┐
│ Step 1: 注册物理集群                                          │
│   → POST /api/v1/physical-clusters                           │
│   → 返回 clusterId                                           │
├──────────────────────────────────────────────────────────────┤
│ Step 2: 扫描节点（查看节点算力信息 + poolLabel 枚举）          │
│   → GET /api/v1/physical-clusters/{clusterId}/nodes          │
│   → 判断使用物理规格还是 HAMi 切分规格                          │
├──────────────────────────────────────────────────────────────┤
│ Step 3: 创建算力规格（ComputeSpec）                            │
│   → 物理规格：POST /api/v1/compute-specs（手动）               │
│   → HAMi 切分：POST /api/v1/resource-pools（自动生成规格）     │
├──────────────────────────────────────────────────────────────┤
│ Step 4: 创建逻辑资源池                                         │
│   → POST /api/v1/resource-pools                              │
└──────────────────────────────────────────────────────────────┘
```

---

## Step 1: 注册物理集群

### 接口

```
POST /api/v1/physical-clusters
Content-Type: application/json
Authorization: Bearer $TOKEN
```

### 请求体

```json
{
  "name": "北京NVIDIA集群",
  "kubeconfigBase64": "<base64编码的kubeconfig>",
  "gpuTypes": "NVIDIA",
  "location": "beijing",
  "hamiEnabled": true
}
```

### 说明

| 字段 | 必填 | 说明 |
|------|------|------|
| name | 是 | 集群名称 |
| kubeconfigBase64 | 是 | kubeconfig Base64 编码（或明文） |
| gpuTypes | 否 | 支持的 GPU 类型，默认 NVIDIA |
| location | 否 | 地域 |
| hamiEnabled | 否 | 是否启用 HAMi vGPU，默认 false |

### 响应

```json
{
  "id": "cls-xxxxx",
  "name": "北京NVIDIA集群",
  "status": "active",
  "gpuTypes": "NVIDIA",
  "hamiEnabled": true
}
```

**保存返回的 `id`，后续步骤需用到。**

---

## Step 2: 扫描节点

### 接口

```
GET /api/v1/physical-clusters/{clusterId}/nodes
Authorization: Bearer $TOKEN
```

### 响应结构

```json
{
  "nodes": [
    {
      "name": "gpu-node-1",
      "status": "Ready",
      "gpuType": "A100-80GB-SXM",
      "nodeCount": 6,
      "nodeMemMb": 81920,
      "nodeCores": 100,
      "cpuCores": 64,
      "memoryGiB": 256,
      "poolLabels": ["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"],
      "labelsJson": "{\"pool\":\"nvidia-a100-80g-1/4,nvidia-a100-80g-1/8\",\"nvidia.com/gpu-family\":\"A100-80GB-SXM\"}"
    }
  ],
  "poolLabels": ["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"]
}
```

### 响应字段说明

| 字段 | 说明 |
|------|------|
| nodes | 集群节点列表 |
| nodes[].name | 节点名称 |
| nodes[].status | 节点状态（Ready/NotReady） |
| nodes[].gpuType | GPU 型号（从 nvidia.com/gpu-family 或 amd.com/dcu-family 获取） |
| nodes[].nodeCount | 可用节点数（vGPU 实例数，HAMi 切分后） |
| nodes[].nodeMemMb | 每节点显存 MB（HAMi 切分后） |
| nodes[].nodeCores | 每节点算力百分比（HAMi 切分后） |
| nodes[].poolLabels | 节点支持的切分规格标签集（逗号分隔，1/2/1/4/1/8 可同时存在） |
| poolLabels | **集群中所有不重复的 pool 标签枚举**（用于资源池创建时选择切分规格） |

---

### 判断规格类型

**情况 A：poolLabels 为空 → 物理规格模式**

节点的 `poolLabels` 为空，该节点**未启用 HAMi 切分**，整卡调度。

**结论：**
- 每节点可调度 `nodeCount` 个 Pod
- 需要创建 **PHYSICAL 规格**

---

**情况 B：poolLabels 有值 → HAMi 虚拟切分模式**

节点的 `poolLabels` 非空，如 `["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"]`。

**结论：**
- 该节点已启用 HAMi，GPU 被切成多种规格
- 前端用 `poolLabels` 枚举供用户选择**其中一种**切分规格
- **平台根据用户选择的 poolLabel 自动生成 ComputeSpec**（见 Step 3B）
- **一个资源池 = 一种切分规格**，不同规格需分别创建不同的资源池

---

## Step 3A: 创建物理规格（poolLabel 为空时）

### 接口

```
POST /api/v1/compute-specs
Content-Type: application/json
Authorization: Bearer $TOKEN
```

### 请求体

```json
{
  "name": "a100-80g-physical",
  "displayName": "A100-80GB 物理规格",
  "gpuBrand": "NVIDIA",
  "defaultGpuCount": 1,
  "defaultCpuCores": 4,
  "defaultMemoryGib": 16,
  "specType": "PHYSICAL"
}
```

### 说明

| 字段 | 说明 |
|------|------|
| name | 规格名，部署时引用 |
| gpuBrand | NVIDIA / HYGON / HUAWEI_ASCEND |
| defaultGpuCount | 每副本使用 GPU 卡数（物理机填 1） |
| specType | PHYSICAL（物理整卡） |

### 响应

```json
{
  "id": "spec-xxxxx",
  "name": "a100-80g-physical",
  "displayName": "A100-80GB 物理规格",
  "gpuBrand": "NVIDIA",
  "specType": "PHYSICAL"
}
```

**保存 `name`，后续创建资源池时使用。**

---

## Step 3B: 创建 HAMi 虚拟规格（poolLabel 非空时）

**无需手动创建 ComputeSpec**，在 Step 4 创建资源池时平台自动根据 `poolLabel` 生成规格。

### 切分规格预设（参考）

平台预设以下切分规格，与 HAMi 节点标签对应：

| 规格名 | GPU 型号 | 显存 | 算力 |
|-----------|----------|------|------|
| nvidia-a100-80g-1/2 | NVIDIA A100 80GB | 40GB | 50% |
| nvidia-a100-80g-1/4 | NVIDIA A100 80GB | 20GB | 25% |
| nvidia-a100-80g-1/8 | NVIDIA A100 80GB | 10GB | 12% |
| nvidia-h100-80g-1/2 | NVIDIA H100 80GB | 40GB | 50% |
| nvidia-h100-80g-1/4 | NVIDIA H100 80GB | 20GB | 25% |
| nvidia-h100-80g-1/8 | NVIDIA H100 80GB | 10GB | 12% |
| hygon-dcu-32g-1/2 | Hygon DCU 32GB | 16GB | 50% |
| hygon-dcu-32g-1/4 | Hygon DCU 32GB | 8GB | 25% |
| hygon-dcu-32g-1/8 | Hygon DCU 32GB | 4GB | 12% |

**注意**：若集群的 `poolLabels` 返回了预设之外的自定义标签，平台会用默认值（16GB, 50%）创建 ComputeSpec，但仍能正确调度。

---

## Step 4: 创建逻辑资源池

### 物理规格模式

```json
{
  "name": "nvidia-physical-pool",
  "departmentCode": "ai",
  "departmentName": "AI部门",
  "physicalClusterIds": ["cls-xxxxx"],
  "specQuotas": [
    {
      "specName": "a100-80g-physical",
      "totalQuota": 6
    }
  ]
}
```

### HAMi 切分模式

```json
{
  "name": "nvidia-vgpu-pool",
  "departmentCode": "ai",
  "departmentName": "AI部门",
  "physicalClusterIds": ["cls-xxxxx"],
  "poolLabel": "nvidia-a100-80g-1/4",
  "specQuotas": [
    {
      "specName": "nvidia-a100-80g-1/4",
      "totalQuota": 20
    }
  ]
}
```

**说明**：
- `poolLabel` 字段为用户从 Step 2 `poolLabels` 枚举中选择的**单一规格**
- 平台自动创建 ComputeSpec（若不存在），`nodeSelector = {"pool":"nvidia-a100-80g-1/4"}`
- 与节点标签精确匹配，确保 Pod 调度到正确的 vGPU 节点
- **一个资源池 = 一种切分规格**，如需多规格需分别创建资源池

---

## 调度链路（Step 5: 部署时）

```
用户指定 specName=xxx
       ↓
PoolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)
       ↓
匹配 spec.nodeSelector 与集群 nodeLabels
       ↓
K8sResourceBuilder 生成 Pod:
  nodeSelector: {pool: nvidia-a100-80g-1/4}  ← HAMi 路由到对应 vGPU 节点
  limits:
    nvidia.com/gpu: "1"
    nvidia.com/gpumem: "20480Mi"             ← HAMi 分配对应大小 vGPU
    platform.io/nvidia-a100-80g-1/4: "1"      ← ResourceQuota 计量
       ↓
HAMi 在节点上找到满足条件的 vGPU 单元绑定给容器
       ↓
ResourceQuota.used.platform.io/xxx 累加
```

---

## 完整示例

### 场景：纳管一个已启用 HAMi 的 NVIDIA A100 集群

**Step 1**: 注册集群
```bash
curl -X POST http://localhost:8080/api/v1/physical-clusters \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "北京A100集群",
    "kubeconfigBase64": "<base64>",
    "hamiEnabled": true
  }'
# 返回 {"id": "cls-001", ...}
```

**Step 2**: 扫描节点
```bash
curl http://localhost:8080/api/v1/physical-clusters/cls-001/nodes \
  -H "Authorization: Bearer $TOKEN"
```

返回：
```json
{
  "nodes": [
    {
      "name": "a100-node-1",
      "poolLabels": ["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"],
      "gpuType": "A100-80GB-SXM",
      "nodeCount": 6
    }
  ],
  "poolLabels": ["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"]
}
```

**前端展示 poolLabels 枚举，用户选择其中一种（如 "nvidia-a100-80g-1/4"）**

**Step 3**: 创建资源池（自动生成规格）
```bash
curl -X POST http://localhost:8080/api/v1/resource-pools \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "A100-1/4卡池",
    "departmentCode": "ai",
    "departmentName": "AI部门",
    "physicalClusterIds": ["cls-001"],
    "poolLabel": "nvidia-a100-80g-1/4",
    "specQuotas": [
      {
        "specName": "nvidia-a100-80g-1/4",
        "totalQuota": 24
      }
    ]
  }'
```

平台自动：
1. 创建 ComputeSpec `nvidia-a100-80g-1/4`（若不存在）
2. `nodeSelector = {"pool":"nvidia-a100-80g-1/4"}`
3. `defaultGpumemMb = 20480`, `defaultGpucores = 25`
4. 插入 `resource_pool_spec_quota`

---

## 快速检查清单

```
[ ] Step 1: 注册集群成功（返回 clusterId）
[ ] Step 2: 扫描节点 GET /nodes
[ ]     ├─ poolLabels 有值 → HAMi 切分模式
[ ]     └─ poolLabels 为空 → 物理规格模式
[ ] Step 3: 规格已创建（手动或自动）
[ ] Step 4: 资源池创建成功
[ ]     └─ specQuotas.availableQuota > 0
[ ] 验证: 部署一个 Pod，观察调度到的节点是否符合预期
```

---

## 相关文档

- [HAMi vGPU 切分管理](./HAMI-PARTITION.md)
- [算力切分机制](./COMPUTE-PARTITION.md)
- [异构算力调度设计](./HETEROGENEOUS-COMPUTE.md)
- [节点纳管功能](./NODE-MANAGEMENT.md)