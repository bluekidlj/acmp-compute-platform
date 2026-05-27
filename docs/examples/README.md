# K8s YAML 示例

本目录存放平台生成的 K8s 资源 YAML 示例，用于上线前核验和调试参考。

## 文件说明

| 文件 | 说明 |
|------|------|
| `nvidia-a100-deployment.yaml` | NVIDIA A100 物理规格（整卡，无 HAMi） |
| `nvidia-a100-hami-1-4-deployment.yaml` | NVIDIA A100 HAMi 1/4 卡切分 |
| `nvidia-h100-hami-1-4-deployment.yaml` | NVIDIA H100 HAMi 1/4 卡切分 |
| `hygon-dcu-hami-1-4-deployment.yaml` | Hygon DCU HAMi 1/4 卡切分 |
| `volcano-queue.yaml` | Volcano Queue（集群级资源） |
| `resourcequota.yaml` | ResourceQuota + 常见错误说明 |
| `yaml-validation-checklist.md` | 上线前核验清单 |

## 关键映射关系

```
ComputeSpec                    → Pod limits
─────────────────────────────────────────────
gpuBrand.NVIDIA               → nvidia.com/gpu
gpuBrand.HYGON                → amd.com/dcu
gpuBrand.HUAWEI_ASCEND        → huawei.com/ascend910

defaultGpumemMb (HAMi)        → nvidia.com/gpumem (仅 NVIDIA)
defaultGpucores (HAMi)        → nvidia.com/gpucores (仅 NVIDIA)

resourceQuotaKey              → limits[platform.io/{spec}] = "1"
                                 (Pod → ResourceQuota.used 自动累加)

nodeSelector                  → Pod.spec.nodeSelector
tolerations                   → Pod.spec.tolerations
```

## 调度链路

```
节点标签: pool=nvidia-a100-80g-1/4
                ↓
ComputeSpec.nodeSelector = {"pool": "nvidia-a100-80g-1/4"}
                ↓
Pod.spec.nodeSelector 匹配节点标签
                ↓
HAMi 根据 nvidia.com/gpumem 分配对应大小 vGPU
                ↓
limits["platform.io/nvidia-a100-80g-1/4"] = "1"
                ↓
ResourceQuota.used.platform.io/... 自动累加
```

## 常见问题速查

详见 `yaml-validation-checklist.md`

1. **Pod Pending（节点选择失败）** → `nodeSelector.pool` 与节点标签不匹配
2. **Pod Pending（无可用 GPU）** → `nvidia.com/gpumem` 超过节点可切分单元
3. **ResourceQuota 失效** → `platform.io/{spec}` 键名不一致
4. **HAMi 分配错误 vGPU** → Pod 缺少 `nvidia.com/gpumem` 声明