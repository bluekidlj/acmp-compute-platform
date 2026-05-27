# 部署推理服务完整流程文档

## 1. ComputeSpec 是什么

ComputeSpec 是平台预设的"K8s Pod 资源规格模板"，存储在 DB 里。

它告诉 K8s：每个 Pod 要申请多少 GPU/CPU/内存，用什么调度规则（nodeSelector/tolerations）。

### 字段说明

| 字段 | 含义 | 示例 |
|------|------|------|
| name | 规格唯一标识 | `auto-nvidia-a100-80g-1/4-1g-4c-16g` |
| displayName | 显示名 | `1/4` |
| gpuBrand | GPU 品牌 | NVIDIA / HYGON |
| defaultGpuCount | 每副本 GPU 卡数 | 1 |
| defaultGpumemMb | 每副本 GPU 显存 MB | 20480 |
| defaultGpucores | 每副本 GPU 算力 % | 25 |
| defaultCpuCores | 每副本 CPU 核数 | 4 |
| defaultMemoryGib | 每副本内存 GiB | 16 |
| nodeSelector | Pod 调度到哪类节点 | `{"pool":"nvidia-a100-80g-1/4"}` |
| tolerations | 容忍节点污点 | `[{"key":"nvidia.com/gpu",...}]` |
| resourceQuotaKey | 平台计量键 | `platform.io/auto-nvidia-a100-80g-1/4-1g-4c-16g` |
| memoryGb | 总内存参考值 | 20 |

### ComputeSpec 生成的 K8s 资源

```
GPU:       limits["nvidia.com/gpu"] = 1
GPU显存:   limits["nvidia.com/gpumem"] = 20480 (HAMi vGPU)
GPU算力:   limits["nvidia.com/gpucores"] = 25
CPU:       limits["cpu"] = 4
内存:      limits["memory"] = 16Gi
平台计量:  limits["platform.io/auto-..."] = 1（每副本计1，用于 ResourceQuota 计数）
调度:     Pod.spec.nodeSelector = {"pool":"nvidia-a100-80g-1/4"}
容忍:     Pod.spec.tolerations = [{"key":"nvidia.com/gpu",...}]
```

---

## 2. specName 是怎么生成的

用户填：`gpuType="nvidia-a100-80g-1/4"`, `gpuCount=1`, `cpuCores=4`, `memoryGib=16`

平台生成 specName：
```
name = "auto-" + gpuType + "-" + gpuCount + "g" + "-" + cpuCores + "c" + "-" + memoryGib + "g"
      = "auto-nvidia-a100-80g-1/4-1g-4c-16g"
```

这个 name 是 ComputeSpec 在 DB 里的唯一标识，用于查找/复用。

---

## 3. 完整部署流程

```
用户请求（POST）
    ↓
① validateDeployment(poolId, request)     ← 部署预检验
    ├─ 查资源池关联的唯一物理集群
    ├─ 查集群的 nodeLabels = {"pool": "nvidia-a100-80g-1/4"}
    └─ 比较 gpuType == nodeLabels["pool"]
       不等 → 抛异常（提前拒绝，不扣配额）
    ↓
② ensureComputeSpec(request)             ← 自动匹配/创建 ComputeSpec
    ├─ specName = "auto-nvidia-a100-80g-1/4-1g-4c-16g"
    ├─ 查 DB，有则复用
    └─ 无则创建：
         - nodeSelector = {"pool": "nvidia-a100-80g-1/4"}
         - gpumemMb / gpucores 从 GpuSplitSpec 映射（20480 / 25）
    ↓
③ quotaService.validateBothLevelQuotas()  ← 双层配额校验
    ↓
④ quotaService.deductBothLevelQuotas()    ← 配额预扣
    ↓
⑤ pickCluster(poolId, spec)              ← 选物理集群
    └─ HomogeneousScheduler 直接返回唯一物理集群
    ↓
⑥ K8s 提交
    - Deployment YAML（含资源限制、nodeSelector、tolerations）
    - Service YAML
```

---

## 4. validateDeployment 为什么重要

**场景**：池 A 只接入了 `hygon-dcu` 节点，用户填了 `nvidia-a100` 类型的部署请求。

**旧逻辑**（无预检验）：
1. 创建 ComputeSpec → 成功
2. 扣配额 → 成功
3. pickCluster 找不到集群 → 抛异常
4. 回滚配额（但浪费 DB 写入和配额操作）

**新逻辑**（有预检验）：
1. validateDeployment 提前发现 gpuType 不匹配
2. 直接抛异常，不扣配额，不创建 ComputeSpec
3. 用户立刻知道错误

---

## 5. 资源池的两种模式

| 模式 | 说明 | 调度逻辑 |
|------|------|----------|
| HOMOGENEOUS | 单一物理集群 | pickCluster 直接返回唯一集群 |
| HETEROGENEOUS | 多物理集群 | 按 nodeSelector 匹配最佳集群 |

同构池的 nodeSelector 匹配在 validateDeployment 时已完成，pickCluster 只是返回结果。

---

## 6. 相关文件

| 文件 | 作用 |
|------|------|
| `PoolScheduler.java` | 调度器接口 |
| `HomogeneousScheduler.java` | 同构调度器：validateDeployment + pickCluster |
| `HeterogeneousScheduler.java` | 异构调度器 |
| `PoolMetadataService.java` | 调度器工厂，根据 poolMode 分发 |
| `ModelDeploymentService.java` | ensureComputeSpec 自动生成/查找 ComputeSpec |
| `ComputeSpec.java` | 实体定义 |

---

## 7. 验证方式

1. `mvn compile` 通过
2. 同构池部署：gpuType 匹配 → 成功
3. 同构池部署：gpuType 不匹配 → 提前报错（不扣配额）
4. 异构池保持现有行为