# 完整流程示例：从规格到部署

## 场景

1 个 NVIDIA 节点 + 1 个 DCU 节点，异构算力环境下：
- 创建 `nvidia-a100-80g` 规格（NVIDIA GPU）
- 创建 `hygon-dcu-32g` 规格（海光 DCU）
- 创建逻辑资源池，关联上述两个物理集群和两个规格
- 创建工作空间，申请两个规格的配额
- 分别用两个规格部署 vLLM 推理服务

---

## 第 1 步：注册物理集群（需 PLATFORM_ADMIN）

### 1.1 注册 NVIDIA 集群

```bash
curl -s -X POST http://localhost:8080/api/v1/physical-clusters \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "nvidia-cluster",
    "kubeconfigBase64": "<base64 kubeconfig>",
    "gpuTypes": "NVIDIA",
    "location": "beijing",
    "nodeLabels": "{\"pool\":\"nvidia-gpu\"}",
    "taints": "[{\"key\":\"nvidia.com/gpu\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]"
  }'
```

**响应：**
```json
{"id": "cluster-nvidia-xxx", "name": "nvidia-cluster", "status": "active", ...}
```

### 1.2 注册 DCU 集群

```bash
curl -s -X POST http://localhost:8080/api/v1/physical-clusters \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "hygon-cluster",
    "kubeconfigBase64": "<base64 kubeconfig>",
    "gpuTypes": "HYGON",
    "location": "beijing",
    "nodeLabels": "{\"pool\":\"hygon-dcu\"}",
    "taints": "[{\"key\":\"amd.com/dcu\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]"
  }'
```

**响应：**
```json
{"id": "cluster-hygon-xxx", "name": "hygon-cluster", "status": "active", ...}
```

---

## 第 2 步：创建算力规格（需 PLATFORM_ADMIN）

### 2.1 创建 NVIDIA A100 规格

```bash
curl -s -X POST http://localhost:8080/api/v1/specs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "nvidia-a100-80g",
    "displayName": "NVIDIA A100 80GB SXM",
    "gpuBrand": "NVIDIA",
    "memoryGb": 80,
    "description": "NVIDIA A100 80GB 全卡规格"
  }'
```

> 平台会自动补全：
> - `defaultGpuCount = 1`（每副本1块GPU）
> - `nodeSelector = {"pool":"nvidia-gpu"}`（调度到 NVIDIA 节点）
> - `resourceQuotaKey = "platform.io/nvidia-a100-80g"`（平台计量用）

### 2.2 创建 Hygon DCU 规格

```bash
curl -s -X POST http://localhost:8080/api/v1/specs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "hygon-dcu-32g",
    "displayName": "Hygon DCU 32GB",
    "gpuBrand": "HYGON",
    "memoryGb": 32,
    "description": "Hygon DCU 32GB 全卡规格"
  }'
```

---

## 第 3 步：创建逻辑资源池（需 PLATFORM_ADMIN / ORG_ADMIN）

```bash
curl -s -X POST http://localhost:8080/api/v1/resource-pools \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "heterogeneous-pool",
    "description": "NVIDIA + Hygon 异构算力池",
    "departmentCode": "dept-ai",
    "departmentName": "AI 研发部",
    "physicalClusterIds": ["cluster-nvidia-xxx", "cluster-hygon-xxx"],
    "specQuotas": [
      {"specName": "nvidia-a100-80g", "totalQuota": 4},
      {"specName": "hygon-dcu-32g", "totalQuota": 2}
    ]
  }'
```

**含义：**
- `totalQuota: 4` → 该逻辑池最多同时运行 4 个 A100 规格的副本
- `totalQuota: 2` → 该逻辑池最多同时运行 2 个 DCU 规格的副本

**响应：**
```json
{
  "id": "pool-xxx",
  "name": "heterogeneous-pool",
  "specQuotas": [
    {"specName": "nvidia-a100-80g", "totalQuota": 4, "allocatedQuota": 0, "availableQuota": 4},
    {"specName": "hygon-dcu-32g", "totalQuota": 2, "allocatedQuota": 0, "availableQuota": 2}
  ]
}
```

---

## 第 4 步：创建工作空间（需 PLATFORM_ADMIN / ORG_ADMIN）

```bash
curl -s -X POST http://localhost:8080/api/v1/workspaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ai-workspace",
    "description": "AI 研发工作空间",
    "resourcePoolId": "pool-xxx",
    "specQuotas": [
      {"specName": "nvidia-a100-80g", "maxQuota": 2},
      {"specName": "hygon-dcu-32g", "maxQuota": 1}
    ],
    "maxPods": 50
  }'
```

**含义：**
- `maxQuota: 2` → 该工作空间最多运行 2 个 A100 规格副本
- `maxQuota: 1` → 该工作空间最多运行 1 个 DCU 规格副本

**平台自动执行：**
1. 在 NVIDIA 和 DCU 两个集群上分别创建同名 Namespace
2. 在每个集群的 Namespace 上创建 ResourceQuota（限制副本数）
3. 创建 SA + Role + RoleBinding
4. 在每个集群上创建 Volcano Queue
5. 扣减 L1 配额（`allocatedQuota` 增加）

---

## 第 5 步：添加工作空间成员

```bash
curl -s -X POST http://localhost:8080/api/v1/workspaces/<ws-id>/members \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userId": "<user-id>"}'
```

---

## 第 6 步：部署 vLLM 推理服务

### 6.1 用 NVIDIA A100 规格部署

```bash
curl -s -X POST "http://localhost:8080/api/v1/workspaces/<ws-id>/model-deployments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen3-nvidia",
    "specName": "nvidia-a100-80g",
    "modelName": "Qwen3",
    "modelSource": "with_weights",
    "modelIdOrPath": "/models/qwen3",
    "vllmImage": "vllm/vllm-openai:latest",
    "replicas": 2
  }'
```

**平台执行流程：**
1. L1 配额校验：`allocatedQuota + 2 ≤ 4` ✓
2. L2 配额校验：`maxQuota - usedQuota ≥ 2` ✓
3. 扣减配额：`allocatedQuota = 2`，`usedQuota = 2`
4. `PoolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)` → 匹配到 `cluster-nvidia-xxx`
5. 构建 K8s Deployment，nodeSelector 注入 `{"pool":"nvidia-gpu"}`
6. 提交到 NVIDIA 集群

**结果：**
- Deployment 下的 Pod 会调度到 NVIDIA 节点
- 每个 Pod 申请 `limits["nvidia.com/gpu"] = 1`

### 6.2 用 Hygon DCU 规格部署

```bash
curl -s -X POST "http://localhost:8080/api/v1/workspaces/<ws-id>/model-deployments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen3-hygon",
    "specName": "hygon-dcu-32g",
    "modelName": "Qwen3",
    "modelSource": "with_weights",
    "modelIdOrPath": "/models/qwen3",
    "vllmImage": "vllm/vllm-openai:latest",
    "replicas": 1
  }'
```

**平台执行流程：**
1. L1 配额校验：`allocatedQuota + 1 ≤ 2` ✓
2. L2 配额校验：`maxQuota - usedQuota ≥ 1` ✓
3. 扣减配额
4. `PoolMetadataService.pickClusterForSpec` → 匹配到 `cluster-hygon-xxx`
5. 提交到 DCU 集群

**结果：**
- Deployment 下的 Pod 会调度到 Hygon DCU 节点

---

## 第 7 步：查询部署状态

```bash
# 查 NVIDIA 部署
curl -s "http://localhost:8080/api/v1/workspaces/<ws-id>/model-deployments" \
  -H "Authorization: Bearer $TOKEN"

# 查 DCU 部署
curl -s "http://localhost:8080/api/v1/workspaces/<ws-id>/model-deployments" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 关键概念回顾

| 概念 | 含义 | 示例值 |
|------|------|--------|
| `ComputeSpec.defaultGpuCount` | Pod 向 K8s 申请的 GPU 数量 | `1` |
| `ComputeSpec.resourceQuotaKey` | 平台计量键，ResourceQuota 用 | `"platform.io/nvidia-a100-80g"` |
| `resourcePool.specQuotas[].totalQuota` | 逻辑池该规格的总副本限额 | `4`（该池最多4个A100 Pod） |
| `workspace.specQuotas[].maxQuota` | 工作空间该规格的副本上限 | `2`（该空间最多2个A100 Pod） |
| `ComputeSpec.nodeSelector` | Pod 调度约束，匹配节点标签 | `{"pool":"nvidia-gpu"}` |

---

## resourceQuotaKey 最终 YAML 展开

`resourceQuotaKey` 在 K8s YAML 中出现在三个位置：

### 1. Pod containers[0].resources.limits

```yaml
# 由 K8sResourceBuilder.buildVllmDeploymentAndService 生成
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm-qwen3-nvidia
  namespace: ws-ai-workspace-xxx
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: vllm
          resources:
            limits:
              nvidia.com/gpu: "1"                    # ← defaultGpuCount
              cpu: "4"                               # ← defaultCpuCores
              memory: "16Gi"                        # ← defaultMemoryGib
              nvidia.com/gpumem: "32000Mi"          # ← defaultGpumemMb (HAMi vGPU 显存)
              nvidia.com/gpucores: "100"            # ← defaultGpucores (HAMi vGPU 算力)
              platform.io/nvidia-a100-80g: "1"       # ← resourceQuotaKey，每副本计1
            requests:                                 # requests 同 limits
              nvidia.com/gpu: "1"
              cpu: "4"
              memory: "16Gi"
              platform.io/nvidia-a100-80g: "1"
      nodeSelector:
        pool: nvidia-gpu                             # ← spec.nodeSelector，调度到 NVIDIA 节点
      tolerations:                                    # ← spec.tolerations，容忍污点
        - key: nvidia.com/gpu
          operator: Exists
          effect: NoSchedule
```

### 2. ResourceQuota hard

```yaml
# 由 KubernetesClientManager.createResourceQuotaBySpec 生成
apiVersion: v1
kind: ResourceQuota
metadata:
  name: quota-ws-ai-workspace-xxx-nvidia-cluster
  namespace: ws-ai-workspace-xxx   # 注意：同名 NS 在不同集群是独立的
spec:
  hard:
    pods: "200"
    platform.io/nvidia-a100-80g: "2"    # ← workspace.specQuotas[].maxQuota
    platform.io/hygon-dcu-32g: "1"      # ← 同上
```

### 3. VolcanoQueue capability

```yaml
# 由 K8sResourceBuilder.buildVolcanoQueue 生成
apiVersion: scheduling.volcano.sh/v1beta1
kind: Queue
metadata:
  name: queue-ws-ai-workspace-xxx-nvidia-cluster
spec:
  capability:
    platform.io/nvidia-a100-80g: "2"    # ← 同 ResourceQuota
  weight: 1
  reclaimable: true
```

### resourceQuotaKey 工作原理

```
Pod 创建
    ↓
Pod.limits.platform.io/nvidia-a100-80g = 1
    ↓
K8s API Server 自动更新 namespace 的 ResourceQuota.status.used.platform.io/nvidia-a100-80g += 1
    ↓
API Server 校验 used ≤ hard，不满足则拒绝 Pod 创建
```

**两方维护，各自在不同时间：**

| 方 | 维护内容 | 时机 |
|----|---------|------|
| **平台代码** | 写入 ResourceQuota `hard`（副本数上限） | 工作空间创建时 |
| **K8s API Server** | 自动累加 ResourceQuota `used`（实际副本数） | 每个 Pod 创建/删除时 |

**本质**：`platform.io/{spec}` 不是真实 K8s 资源，而是**自定义计量键**。K8s ResourceQuota 允许使用任意字符串键，只要 Pod 在 `limits` 里声明，API Server 自动统计。平台利用这个机制实现副本数配额控制，与 K8s 节点真实 GPU 调度解耦。

### resourceQuotaKey 最终 YAML 展开

`resourceQuotaKey` 在 K8s YAML 中出现在三个位置：

### 1. Pod containers[0].resources.limits

```yaml
# 由 K8sResourceBuilder.buildVllmDeploymentAndService 生成
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm-qwen3-nvidia
  namespace: ws-ai-workspace-xxx
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: vllm
          resources:
            limits:
              nvidia.com/gpu: "1"                    # ← defaultGpuCount
              cpu: "4"                               # ← defaultCpuCores
              memory: "16Gi"                        # ← defaultMemoryGib
              nvidia.com/gpumem: "32000Mi"          # ← defaultGpumemMb (HAMi vGPU 显存)
              nvidia.com/gpucores: "100"            # ← defaultGpucores (HAMi vGPU 算力)
              platform.io/nvidia-a100-80g: "1"       # ← resourceQuotaKey，每副本计1
            requests:                                 # requests 同 limits
              nvidia.com/gpu: "1"
              cpu: "4"
              memory: "16Gi"
              platform.io/nvidia-a100-80g: "1"
      nodeSelector:
        pool: nvidia-gpu                             # ← spec.nodeSelector，调度到 NVIDIA 节点
      tolerations:                                    # ← spec.tolerations，容忍污点
        - key: nvidia.com/gpu
          operator: Exists
          effect: NoSchedule
```

### 2. ResourceQuota hard

```yaml
# 由 KubernetesClientManager.createResourceQuotaBySpec 生成
apiVersion: v1
kind: ResourceQuota
metadata:
  name: quota-ws-ai-workspace-xxx-nvidia-cluster
  namespace: ws-ai-workspace-xxx   # 注意：同名 NS 在不同集群是独立的
spec:
  hard:
    pods: "200"
    platform.io/nvidia-a100-80g: "2"    # ← workspace.specQuotas[].maxQuota
    platform.io/hygon-dcu-32g: "1"      # ← 同上
```

### 3. VolcanoQueue capability

```yaml
# 由 K8sResourceBuilder.buildVolcanoQueue 生成
apiVersion: scheduling.volcano.sh/v1beta1
kind: Queue
metadata:
  name: queue-ws-ai-workspace-xxx-nvidia-cluster
spec:
  capability:
    platform.io/nvidia-a100-80g: "2"    # ← 同 ResourceQuota
  weight: 1
  reclaimable: true
```