# HAMi vGPU 切分管理

## 1. 设计原则

**规格来源于平台预设 GpuSplitSpec 枚举，K8s 节点标签由管理员手动维护，扫描结果用于校验/展示。**

```
平台侧（GpuSplitSpec 枚举）
    ├── 预置所有切分规格（nvidia-a100-80g-1/4, hygon-dcu-32g-1/8 等）
    └── 创建资源池时，根据 poolLabel 查找枚举参数

K8s 集群侧（管理员手动配置）
    └── 节点 Labels: pool=nvidia-a100-80g-1/4,nvidia-a100-80g-1/8
                      ↑ 逗号分隔多规格

扫描返回
    ├── nodes[].poolLabels：节点支持的所有规格（展示给用户）
    └── poolLabels：集群所有规格枚举（用户选择）
```

**关键约束：**
- 平台规格为来源，K8s 标签为校验
- 创建资源池时，用户只选择**一种**规格
- **一个资源池 = 一种切分规格**

---

## 2. 规格定义（GpuSplitSpec 枚举）

| 规格名 | GPU 型号 | 显存 | 算力 |
|--------|----------|------|------|
| nvidia-a100-80g-1/2 | NVIDIA A100 80GB | 40GB | 50% |
| nvidia-a100-80g-1/4 | NVIDIA A100 80GB | 20GB | 25% |
| nvidia-a100-80g-1/8 | NVIDIA A100 80GB | 10GB | 12% |
| nvidia-h100-80g-1/2 | NVIDIA H100 80GB | 40GB | 50% |
| nvidia-h100-80g-1/4 | NVIDIA H100 80GB | 20GB | 25% |
| nvidia-h100-80g-1/8 | NVIDIA H100 80GB | 10GB | 12% |
| hygon-dcu-32g-1/2 | Hygon DCU 32GB | 16GB | 50% |
| hygon-dcu-32g-1/4 | Hygon DCU 32GB | 8GB | 25% |
| hygon-dcu-32g-1/8 | Hygon DCU 32GB | 4GB | 12% |

---

## 3. 用户操作流程

### 3.1 管理员在 K8s 节点配置标签

```bash
# 单规格
kubectl label node gpu-node-1 pool=nvidia-a100-80g-1/4 --overwrite

# 多规格（逗号分隔，同一节点支持多种切分）
kubectl label node gpu-node-1 pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4,nvidia-a100-80g-1/8 --overwrite
```

### 3.2 注册物理集群

```bash
curl -X POST http://localhost:8080/api/v1/physical-clusters \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "nvidia-cluster",
    "kubeconfigBase64": "<base64-kubeconfig>"
  }'
```

### 3.3 扫描节点（查看可用规格）

```bash
curl http://localhost:8080/api/v1/physical-clusters/{clusterId}/nodes \
  -H "Authorization: Bearer $TOKEN"
```

返回：
```json
{
  "nodes": [
    {
      "name": "gpu-node-1",
      "poolLabels": ["nvidia-a100-80g-1/2", "nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"],
      "gpuType": "A100-80GB-SXM"
    }
  ],
  "poolLabels": ["nvidia-a100-80g-1/2", "nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"]
}
```

**前端展示 poolLabels，用户选择其中一种（如 "nvidia-a100-80g-1/4"）**

### 3.4 创建资源池（启用切分）

```bash
curl -X POST http://localhost:8080/api/v1/resource-pools \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "A100-1/4卡池",
    "departmentCode": "ai",
    "departmentName": "AI部门",
    "physicalClusterIds": ["<cluster-id>"],
    "poolLabel": "nvidia-a100-80g-1/4",
    "specQuotas": [{"specName": "nvidia-a100-80g-1/4", "totalQuota": 20}]
  }'
```

**平台自动执行**：
1. 根据 `poolLabel` 查找 `GpuSplitSpec.fromSpecName("nvidia-a100-80g-1/4")`
2. 创建 ComputeSpec：
   - `name`: nvidia-a100-80g-1/4
   - `nodeSelector`: `{"pool":"nvidia-a100-80g-1/4"}`
   - `defaultGpumemMb`: 20480
   - `defaultGpucores`: 25
3. 插入 `resource_pool_spec_quota`

### 3.5 部署推理服务

```bash
curl -X POST http://localhost:8080/api/v1/workspaces/<wsId>/model-deployments \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "qwen3",
    "specName": "nvidia-a100-80g-1/4",
    "replicas": 2
  }'
```

**平台执行链路**：
```
PoolMetadataService.pickClusterForSpec
    ↓
ComputeSpec.nodeSelector = {pool: nvidia-a100-80g-1/4}
    ↓
匹配 PhysicalCluster.nodeLabels
    ↓
K8sResourceBuilder.buildVllmDeploymentAndService
    ↓
Pod spec:
  nodeSelector: {pool: nvidia-a100-80g-1/4}
  limits:
    nvidia.com/gpu: "1"
    nvidia.com/gpumem: "20480Mi"
    nvidia.com/gpucores: "25"
    platform.io/nvidia-a100-80g-1/4: "1"
    ↓
HAMi 调度器在节点上找到满足条件的 vGPU 单元绑定给容器
```

---

## 4. 多规格场景

同一节点支持多种切分规格（如 1/2、1/4、1/8），但**每种规格需要单独创建资源池**。

```
节点标签：pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4,nvidia-a100-80g-1/8
    ↓
扫描返回 poolLabels: [nvidia-a100-80g-1/2, nvidia-a100-80g-1/4, nvidia-a100-80g-1/8]
    ↓
用户选择 "nvidia-a100-80g-1/4" → 创建资源池 A（1/4 卡池）
用户选择 "nvidia-a100-80g-1/8" → 创建资源池 B（1/8 卡池）
    ↓
两个资源池，共用同一节点，通过 ResourceQuota 分别限制配额
```

---

## 5. 相关文档

- [HAMi vGPU K8s 配置操作指南](./HAMI-OPERATION.md)
- [异构算力调度设计](./HETEROGENEOUS-COMPUTE.md)
- [节点纳管与资源池创建](./NODE-ONBOARDING.md)