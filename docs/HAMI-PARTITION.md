# HAMi vGPU 切分管理

## 1. 设计原则

**平台预设切分规格，用户选择切分类型，平台自动生成 ComputeSpec。**

```
K8s 集群侧（HAMi 配置）
    ├── HAMi ConfigMap: customSpecs 定义切分规格（1/2, 1/4, 1/8）
    └── 节点 Labels: pool=xxx ← ACMP 平台通过这个识别

ACMP 平台侧
    ├── GpuSplitSpec 枚举：预置所有切分规格
    ├── 资源池创建时选择 splitType → 自动生成 ComputeSpec
    └── 部署时通过 nodeSelector 路由到正确节点
```

---

## 2. 预置切分规格（GpuSplitSpec 枚举）

| GPU 型号 | 1/2 切分 | 1/4 切分 | 1/8 切分 |
|----------|---------|---------|---------|
| NVIDIA A100 80GB | nvidia-a100-80g-1/2 (40GB, 50%) | nvidia-a100-80g-1/4 (20GB, 25%) | nvidia-a100-80g-1/8 (10GB, 12%) |
| NVIDIA RTX 4090 24GB | nvidia-rtx4090-24g-1/2 (12GB, 50%) | nvidia-rtx4090-24g-1/4 (6GB, 25%) | nvidia-rtx4090-24g-1/8 (3GB, 12%) |
| Hygon DCU 32GB | hygon-dcu-32g-1/2 (16GB, 50%) | hygon-dcu-32g-1/4 (8GB, 25%) | hygon-dcu-32g-1/8 (4GB, 12%) |

---

## 3. 用户操作流程

### 3.1 注册物理集群（启用 HAMi）

```bash
curl -X POST http://localhost:8080/api/v1/physical-clusters \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "nvidia-cluster",
    "kubeconfigBase64": "<base64-kubeconfig>",
    "hamiEnabled": true
  }'
```

### 3.2 创建资源池（启用切分）

```bash
# 创建 1/4 卡切分的资源池
curl -X POST http://localhost:8080/api/v1/resource-pools \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "nvidia-vgpu-pool",
    "departmentCode": "AI",
    "departmentName": "AI部门",
    "physicalClusterIds": ["<cluster-id>"],
    "splitType": "1/4",
    "gpuType": "A100-80GB-SXM",
    "specQuotas": [{"specName": "nvidia-a100-80g-1/4", "totalQuota": 20}]
  }'
```

**平台自动执行**：
1. 查找 `GpuSplitSpec.NVIDIA_A100_80GB_1_4` → `specName=nvidia-a100-80g-1/4`
2. 创建 ComputeSpec：
   - `name`: nvidia-a100-80g-1/4
   - `nodeSelector`: `{"pool":"nvidia-a100-80g-1/4"}`
   - `defaultGpumemMb`: 20480
   - `defaultGpucores`: 25
3. 插入 `resource_pool_spec_quota`

### 3.3 部署推理服务

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
spec.nodeSelector = {pool: nvidia-a100-80g-1/4}
    ↓
匹配集群 nodeLabels 中的 pool=nvidia-a100-80g-1/4
    ↓
K8sResourceBuilder.buildVllmDeploymentAndService
    ↓
Pod spec:
  nodeSelector: {pool: nvidia-a100-80g-1/4}
  limits:
    nvidia.com/gpu: "1"
    nvidia.com/gpumem: "20480Mi"    ← 自动从 ComputeSpec 填充
    nvidia.com/gpucores: "25"       ← 自动从 ComputeSpec 填充
    platform.io/nvidia-a100-80g-1/4: "1"
    ↓
HAMi 调度器在节点上找到满足 gpumem>=20480Mi 的 vGPU 单元，绑定给容器
```

---

## 4. 关键代码

### 4.1 GpuSplitSpec 枚举

```java
public enum GpuSplitSpec {
    NVIDIA_A100_80GB_1_2("nvidia-a100-80g-1/2", "NVIDIA", "A100-80GB-SXM", 40960, 50),
    NVIDIA_A100_80GB_1_4("nvidia-a100-80g-1/4", "NVIDIA", "A100-80GB-SXM", 20480, 25),
    NVIDIA_A100_80GB_1_8("nvidia-a100-80g-1/8", "NVIDIA", "A100-80GB-SXM", 10240, 12),
    // ...
}
```

### 4.2 资源池创建（切分模式）

```java
// ResourcePoolService.create() 中
if (request.getSplitType() != null) {
    GpuSplitSpec splitSpec = GpuSplitSpec.fromGpuTypeAndRatio(gpuType, splitType);
    // 创建 ComputeSpec，自动设置 nodeSelector = {pool: specName}
}
```

---

## 5. 相关文档

- [HAMi vGPU K8s 配置操作指南](./HAMI-OPERATION.md)
- [异构算力调度设计](./HETEROGENEOUS-COMPUTE.md)