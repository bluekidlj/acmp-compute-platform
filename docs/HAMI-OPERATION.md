# HAMi vGPU 切分配置操作指南

## 1. 概述

本文档说明如何在 K8s 集群侧配置 HAMi vGPU 切分，以及 ACMP 平台如何使用这些配置。

**边界说明**：
- HAMi 配置（节点 Annotations）由**集群管理员**在 K8s 集群侧操作
- ACMP 平台通过**节点标签（Labels）**识别 vGPU 规格

---

## 2. HAMi 切分原理

### 2.1 HAMi 是什么

HAMi（Heterogeneous-Aware Container Middleware for GPU sharing）是 K8s 的 GPU 共享设备插件，实现将一块物理 GPU 虚拟化为多个 vGPU 单元。

```
物理 GPU A100 80GB（1 块）
    │
    └── HAMi device plugin（在节点上运行）
            ├── 按配置切成 N 个 vGPU 单元
            ├── 每个 vGPU 有独立显存（gpumem）和算力（gpucores）
            └── Pod 申请时自动分配对应大小的 vGPU
```

### 2.2 HAMi 切分参数

| 参数 | 含义 | 示例值 |
|------|------|--------|
| `nvidia.com/gpu-family` | GPU 型号 | `A100-80GB-SXM` |
| `nvidia.com/gpu-memory` | 物理 GPU 总显存 | `81920Mi`（80GB） |
| `nvidia.com/virtualization-group-count` | 切分数 | `6`（切成 6 份） |
| `nvidia.com/vgpu-group-N` | 每个 vGPU 单元规格 | `0,13670,25`（索引,显存MB,算力%） |

---

## 3. 前置要求

### 3.1 环境要求

- K8s 集群版本 ≥ 1.20
- 已安装 [HAMi device plugin](https://github.com/HAMi/HAMi)
- 节点有 GPU（NVIDIA/AMD/Hygon 等）
- 有节点操作权限（能执行 `kubectl label` 和 `kubectl annotate`）

### 3.2 验证 HAMi 已安装

```bash
# 检查 HAMi device plugin 是否运行
kubectl get pods -n kube-system | grep -i hami

# 检查节点 GPU 资源是否暴露
kubectl get nodes -o custom-columns=NAME:.metadata.name,GPU:.status.allocatable.nvidia\\.com/gpu
```

---

## 4. 节点配置步骤

### 4.1 为节点打标签（ACMP 平台识别用）

节点标签是 ACMP 平台识别 vGPU 规格的关键。

```bash
# 为 GPU 节点打标签（pool 值对应 ACMP 平台预置的切分规格名）
# 支持逗号分隔多规格：同时支持多种切分
kubectl label node <node-name> pool=nvidia-a100-80g-1/4 --overwrite

# 多规格同时标记（1/2、1/4、1/8 共存）
kubectl label node <node-name> --overwrite pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4,nvidia-a100-80g-1/8

# 查看标签
kubectl get nodes --show-labels | grep pool
```

**pool 值格式**：`nvidia-a100-80g-1/4`、`hygon-dcu-32g-1/8` 等，支持逗号分隔多值。

ACMP 平台通过 `ComputeSpec.nodeSelector` 匹配 `PhysicalCluster.nodeLabels` 中的 `pool` 键。

### 4.2 配置 HAMi Annotations

在节点上设置 HAMi 切分参数：

```bash
NODE_NAME="gpu-node-1"

# A100-80GB 切成 6 个 vGPU 单元
kubectl annotate node ${NODE_NAME} --overwrite \
  nvidia.com/gpu-family="A100-80GB-SXM" \
  nvidia.com/gpu-memory="81920Mi" \
  nvidia.com/virtualization-group-count="6" \
  nvidia.com/default-vgpu-mem="13670" \
  nvidia.com/default-vgpu-cores="16"
```

### 4.3 配置每个 vGPU 单元的 Annotations

```bash
NODE_NAME="gpu-node-1"

# 逐个设置 vGPU 单元规格（索引, 显存MB, 算力%）
for i in 0 1 2 3 4 5; do
  kubectl annotate node ${NODE_NAME} --overwrite \
    "nvidia.com/vgpu-group-${i}=${i},13670,16"
done

# 验证
kubectl describe node ${NODE_NAME} | grep -A 20 Annotations
```

### 4.4 重启 kubelet 使配置生效

HAMi 配置需要 kubelet 重启后才能生效：

```bash
# 在节点上执行
ssh <node-host>
sudo systemctl restart kubelet

# 验证 allocatable 变化
kubectl get node <node-name> -o jsonpath='{.status.allocatable}'
```

预期输出：
```json
{
  "nvidia.com/gpu": "6",
  "nvidia.com/gpumem": "81920Mi",
  "nvidia.com/gpucores": "100"
}
```

---

## 5. 预置切分规格参考

### 5.1 NVIDIA A100 80GB（切 6 份）

```bash
# 显存：81920MiB / 6 ≈ 13670MiB， 算力：100% / 6 ≈ 16%
kubectl annotate node ${NODE_NAME} --overwrite \
  nvidia.com/gpu-family="A100-80GB-SXM" \
  nvidia.com/gpu-memory="81920Mi" \
  nvidia.com/virtualization-group-count="6" \
  nvidia.com/default-vgpu-mem="13670" \
  nvidia.com/default-vgpu-cores="16"

for i in 0 1 2 3 4 5; do
  kubectl annotate node ${NODE_NAME} --overwrite "nvidia.com/vgpu-group-${i}=${i},13670,16"
done

# 打标签（支持逗号分隔多规格，同时支持 1/2、1/4、1/8 切分）
kubectl label node ${NODE_NAME} --overwrite pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4,nvidia-a100-80g-1/8
```

### 5.2 NVIDIA H100 80GB SXM（切 6 份）

```bash
# 显存：81920MiB / 6 ≈ 13670MiB， 算力：100% / 6 ≈ 16%
kubectl annotate node ${NODE_NAME} --overwrite \
  nvidia.com/gpu-family="H100-SXM-80GB" \
  nvidia.com/gpu-memory="81920Mi" \
  nvidia.com/virtualization-group-count="6" \
  nvidia.com/default-vgpu-mem="13670" \
  nvidia.com/default-vgpu-cores="16"

for i in 0 1 2 3 4 5; do
  kubectl annotate node ${NODE_NAME} --overwrite "nvidia.com/vgpu-group-${i}=${i},13670,16"
done

# 打标签（支持逗号分隔多规格，同时支持 1/2、1/4、1/8 切分）
kubectl label node ${NODE_NAME} --overwrite pool=nvidia-h100-80g-1/2,nvidia-h100-80g-1/4,nvidia-h100-80g-1/8
```

### 5.3 Hygon DCU 32GB（切 4 份）

```bash
# 显存：32768MiB / 4 ≈ 8192MiB， 算力：100% / 4 ≈ 25%
kubectl annotate node ${NODE_NAME} --overwrite \
  nvidia.com/gpu-family="Hygon-DCU-32GB" \
  nvidia.com/gpu-memory="32768Mi" \
  nvidia.com/virtualization-group-count="4" \
  nvidia.com/default-vgpu-mem="8192" \
  nvidia.com/default-vgpu-cores="25"

for i in 0 1 2 3; do
  kubectl annotate node ${NODE_NAME} --overwrite "nvidia.com/vgpu-group-${i}=${i},8192,25"
done

# 打标签（支持逗号分隔多规格）
kubectl label node ${NODE_NAME} --overwrite pool=hygon-dcu-32g-1/2,hygon-dcu-32g-1/4,hygon-dcu-32g-1/8
```

---

## 6. ACMP 平台对接流程

### 6.1 节点配置完成后的状态

```
K8s 节点 annotations:
  nvidia.com/gpu-family: "A100-80GB-SXM"
  nvidia.com/gpu-memory: "81920Mi"
  nvidia.com/virtualization-group-count: "6"
  nvidia.com/vgpu-group-0: "0,13670,16"
  nvidia.com/vgpu-group-1: "1,13670,16"
  ...

K8s 节点 labels:
  pool: nvidia-a100-80g-1/4   ← ACMP 平台通过这个标签识别
```

### 6.2 在 ACMP 平台注册物理集群

```bash
curl -X POST http://localhost:8080/api/v1/physical-clusters \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "nvidia-cluster",
    "kubeconfigBase64": "<base64-kubeconfig>",
    "hamiEnabled": true,
    "nodeLabels": "{\"pool\":\"nvidia-a100-80g-1/4\"}"
  }'
```

### 6.3 在 ACMP 平台创建资源池（启用切分，支持多规格）

```bash
# 单规格创建
curl -X POST http://localhost:8080/api/v1/resource-pools \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "nvidia-vgpu-pool",
    "departmentCode": "AI",
    "departmentName": "AI部门",
    "physicalClusterIds": ["<cluster-id>"],
    "poolLabel": "nvidia-a100-80g-1/4",
    "specQuotas": [{"specName": "nvidia-a100-80g-1/4", "totalQuota": 20}]
  }'
```

**平台自动生成 ComputeSpec**：
- `name`: nvidia-a100-80g-1/4
- `nodeSelector`: `{"pool":"nvidia-a100-80g-1/4"}`
- `defaultGpumemMb`: 20480
- `defaultGpucores`: 25

### 6.4 使用切分规格部署

### 6.4 使用切分规格部署

```bash
# 使用切分规格部署推理服务
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
HAMi 调度器绑定 vGPU 单元
```

---

## 7. 常见问题

### Q1: HAMi 配置后 allocatable 没有变化

- 确保 kubelet 已重启
- 确保 HAMi device plugin 已正确安装并运行
- 检查节点是否有 `nvidia.com/gpu` in allocatable

### Q2: 节点标签与 ACMP 平台规格不匹配

- 确保节点标签 `pool=xxx` 与 ACMP 预置的切分规格名一致
- 查看 `GpuSplitSpec` 枚举获取所有预置规格名

---

## 8. 相关文档

- [HAMi vGPU 切分管理](../docs/HAMI-PARTITION.md)
- [异构算力调度设计](../docs/HETEROGENEOUS-COMPUTE.md)