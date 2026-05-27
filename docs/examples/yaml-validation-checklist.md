# YAML 正确性核验清单

## 一、标签匹配（nodeSelector）

### 检查点：Pod nodeSelector ↔ 节点标签 必须完全一致

```
问题场景：
  节点标签：pool=nvidia-a100-80g-1/4
  ComputeSpec.nodeSelector = {"pool": "nvidia-a100-80g-1/4"}  ✅ 匹配
  ComputeSpec.nodeSelector = {"pool": "nvidia-a100-80g-1/2"}  ❌ 不匹配 → Pod Pending
  ComputeSpec.nodeSelector = {"pool": "a100-1/4"}              ❌ 不匹配 → Pod Pending
```

**验证方法：**
```bash
# 查看节点标签
kubectl get nodes --show-labels | grep pool

# 检查 Pod 是否 Pending
kubectl get pods -n <namespace> -o wide
kubectl describe pod <pod-name> -n <namespace> | grep -A5 "Events:"
```

---

## 二、资源键对应关系

### 2.1 GPU 资源键（按品牌）

| GPU 品牌 | 设备键 | HAMi 显存键 | HAMi 算力键 |
|---------|--------|-----------|------------|
| NVIDIA | `nvidia.com/gpu` | `nvidia.com/gpumem` | `nvidia.com/gpucores` |
| HYGON | `amd.com/dcu` | — | — |
| HUAWEI_ASCEND | `huawei.com/ascend910` | — | — |

### 2.2 检查点：limits 中的键必须与 spec 配置一致

**正确示例（A100 HAMi 1/4）：**
```yaml
resources:
  limits:
    nvidia.com/gpu: "1"                    # spec.defaultGpuCount
    nvidia.com/gpumem: 20480Mi             # spec.defaultGpumemMb
    nvidia.com/gpucores: 25                # spec.defaultGpucores
    cpu: "4"                               # spec.defaultCpuCores
    memory: 16Gi                            # spec.defaultMemoryGib
    platform.io/nvidia-a100-80g-1/4: "1"   # spec.resourceQuotaKey
```

**常见错误：**
- `gpumem` 写成了 `nvidia.com/gpu_mem` 或 `nvidia.com/gpu-memory`
- `gpucores` 写成了 `nvidia.com/gpu_cores` 或 `nvidia.com/cores`
- `platform.io/xxx` 与 `spec.resourceQuotaKey` 不一致

---

## 三、ResourceQuota 计量

### 3.1 检查点：platform.io/{spec} 键必须双向一致

```
ComputeSpec.resourceQuotaKey = "platform.io/nvidia-a100-80g-1/4"
                                    ↓
Pod limits 必须包含：platform.io/nvidia-a100-80g-1/4: "1"
                                    ↓
ResourceQuota hard 必须包含：platform.io/nvidia-a100-80g-1/4: "<maxQuota>"
```

**错误 1：键名不一致**
```yaml
# spec.resourceQuotaKey = "platform.io/nvidia-a100-80g-1/4"
# 但 Pod limits 写成了：
platform.io/a100-80g-1/4: "1"   # ❌ 缺少 nvidia- 前缀
```

**错误 2：Pod 根本没有 platform.io 键**
```yaml
# Pod limits 完全没有 platform.io/nvidia-a100-80g-1/4
# → ResourceQuota used 永远是 0，配额限制失效
```

**错误 3：ResourceQuota 配了但 Pod 没写**
```yaml
# ResourceQuota hard: platform.io/xxx: "20"
# 但 Pod limits 没有 platform.io/xxx: "1"
# → used 不会累加，配额失效
```

### 3.2 验证方法

```bash
# 查看 ResourceQuota
kubectl get resourcequota -n <namespace> -o yaml

# 查看实际使用量（used）
kubectl describe resourcequota -n <namespace>

# 验证 Pod 中是否包含 platform.io 键
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.spec.containers[0].resources.limits}'
```

---

## 四、HAMi vGPU 调度

### 4.1 检查点：节点必须有对应的 pool 标签

```bash
# 查看节点 pool 标签
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}: pool={.metadata.labels.pool}{"\n"}{end}'
```

### 4.2 常见调度失败原因

| 错误现象 | 原因 | 解决 |
|---------|------|------|
| Pod Pending（无可用 GPU） | `nvidia.com/gpumem` 超过节点可切分单元 | 减小 gpumemMb 或改用更小的切分规格 |
| Pod Pending（节点选择失败） | `nodeSelector.pool` 与节点标签不匹配 | 检查标签是否完全一致 |
| Pod Running 但 ResourceQuota 超限 | Pod 没有写 `platform.io/{spec}=1` | 检查 Pod limits |
| HAMi 分配了错误大小的 vGPU | Pod limits 缺少 `nvidia.com/gpumem` | 必须显式声明 gpumem |

---

## 五、Tolerations

### 5.1 检查点：污点容忍必须与节点污点匹配

**常见 tolerations：**
```yaml
tolerations:
  - key: nvidia.com/gpu
    operator: Exists
    effect: NoSchedule    # 匹配节点的 NoSchedule 污点
```

**如果节点没有污点，不写 tolerations 也可以。**

---

## 六、Deployment selector 匹配

### 6.1 检查点：Deployment selector ↔ Pod labels

```yaml
spec:
  selector:
    matchLabels:
      app: vllm
      deployment: vllm-qwen3      # ← 必须与 template.metadata.labels 一致
  template:
    metadata:
      labels:
        app: vllm
        deployment: vllm-qwen3  # ← 必须与 selector 一致
```

**错误：selector 与 template labels 不匹配 → Deployment 创建成功但 Pod 不会被管理**

---

## 七、Service selector

### 7.1 检查点：Service selector ↔ Pod labels

```yaml
spec:
  selector:
    app: vllm
    deployment: vllm-qwen3      # ← 必须与 Pod labels 完全一致
```

**错误：selector 多加了或漏了某个 label → Service 无法找到 Pod**

---

## 八、异构算力（多集群）检查

### 8.1 检查点：部署时 clusterId 是否动态选定

**验证：调用 ModelDeploymentService.deploy() 时**
- 应该调用 `poolMetadataService.pickClusterForSpec(poolId, spec)` 动态获取 clusterId
- 不应该使用 workspace.primaryClusterId（已废弃）

**验证方法：**
```bash
# 查看 Pod 实际调度到了哪个节点
kubectl get pods -n <namespace> -o wide
# 应该看到节点名
```

---

## 九、验证命令速查

```bash
# 1. 检查节点标签
kubectl get nodes --show-labels | grep -E "pool|nvidia|amd"

# 2. 检查节点可分配资源
kubectl describe node <node-name> | grep -A10 "Allocated resources"

# 3. 检查 Pod 资源配置
kubectl get pod <pod-name> -n <namespace> -o yaml | grep -A20 "resources:"
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.spec.nodeSelector}'

# 4. 检查 ResourceQuota
kubectl get resourcequota -n <namespace>
kubectl describe resourcequota <quota-name> -n <namespace>

# 5. 检查 Deployment 状态
kubectl get deployment -n <namespace>
kubectl rollout status deployment/<name> -n <namespace>

# 6. 检查 Service 是否匹配 Pod
kubectl get endpoints -n <namespace>
```

---

## 十、上线前 Checklist

```
[ ] 节点 pool 标签已正确配置（如 pool=nvidia-a100-80g-1/4）
[ ] HAMi 已正确配置（节点 annotations 中有 nvidia.com/virtualization-group-*）
[ ] ComputeSpec.nodeSelector 与节点 pool 标签完全一致
[ ] ComputeSpec.gpuBrand 与节点 GPU 类型一致
[ ] Pod limits 包含 nvidia.com/gpumem（HAMi 环境必须）
[ ] Pod limits 包含 platform.io/{spec}=1
[ ] ResourceQuota hard 包含 platform.io/{spec}（与 Pod limits 键名完全一致）
[ ] Deployment selector 与 Pod labels 完全一致
[ ] Service selector 与 Pod labels 完全一致
[ ] Tolerations 与节点污点匹配（或节点无污点）
[ ] 多集群场景：验证 pickClusterForSpec 动态返回了正确的 clusterId
```