# 算力切分机制

## 1. 核心问题：虚拟节点是谁切的？

**答案：不在平台代码里切，由 K8s 集群侧（HAMi）完成。**

ACMP 平台不"创建"虚拟节点，只"描述" Pod 需要多少资源并路由到合适的节点。

```
真实物理节点（K8s 节点）
    │
    └── HAMi 设备插件（集群侧安装）
            │
            ├── 将物理 GPU 切分为多个 vGPU 规格
            ├── 每个 vGPU 规格暴露为不同的 allocatable 资源
            └── 节点打上多组标签，标识支持的 vGPU 规格

ACMP 平台（业务侧）
    │
    ├── ComputeSpec：定义"我需要多少 vGPU 资源"
    ├── nodeSelector：定义"我要调度到哪类 vGPU 规格的节点"
    └── ResourceQuota：限制"这个 namespace 最多能跑几个该规格的 Pod"
```

**所以切分发生在 K8s 集群的 HAMi 层，不是平台代码。**

---

## 2. HAMi vGPU 切分原理（集群侧）

以一块 NVIDIA A100 80GB 为例，HAMi 可以切成：

| vGPU 规格 | GPU 卡数 | 显存 | 算力 | 节点标签 | allocatable 键 |
|-----------|----------|------|------|---------|----------------|
| v100-7B | 1/6 卡 | ~10GiB | ~16% | `pool:v100-7b` | `nvidia.com/gpu=1`, `nvidia.com/gpumem=~10000` |
| v100-14B | 1/3 卡 | ~20GiB | ~33% | `pool:v100-14b` | `nvidia.com/gpu=1`, `nvidia.com/gpumem=~20000` |
| v100-28B | 1/2 卡 | ~40GiB | ~50% | `pool:v100-28b` | `nvidia.com/gpu=1`, `nvidia.com/gpumem=~40000` |
| v100-40B | 2/3 卡 | ~60GiB | ~66% | `pool:v100-40b` | `nvidia.com/gpu=2`, `nvidia.com/gpumem=~60000` |
| v100-80B | 整卡 | 80GiB | 100% | `pool:v100-80b` | `nvidia.com/gpu=1`, `nvidia.com/gpumem=~80000` |

**HAMi 配置示例**（节点 Annotations，集群管理员配置）：
```yaml
annotations:
 vidia.com/gpu-family: "a100"
  nvidia.com/gpu-memory: "80000Mi"         # 物理显存总量
  # 切分为多个 vGPU 规格
  nvidia.com/virtualization-group-7b:     "6000,16"        # mem, cores
  nvidia.com/virtualization-group-14b:    "12000,33"
  nvidia.com/virtualization-group-28b:    "24000,50"
  nvidia.com/virtualization-group-40b:    "48000,66"
```

**节点打标签**（集群管理员配置）：
```bash
kubectl label node <node> pool=v100-7b pool=v100-14b pool=v100-28b pool=v100-40b pool=v100-80b
```

**K8s 节点 allocatable 变化后**：
```json
{
  "allocatable": {
    "nvidia.com/gpu": "6",                    # 可切出 6 个 1/6 vGPU
    "nvidia.com/gpumem": "80000Mi",           # 物理显存
    "huawei.com Ascend910": "1"
  }
}
```

HAMi 会在 Pod 调度时，根据 `limits["nvidia.com/gpumem"]` 动态分配对应大小的 vGPU 设备。

---

## 3. ComputeSpec 在算力切分中的角色

ComputeSpec **不是用来切算力的**，而是**用来描述切分后的算力单元**。

```
HAMi 切好后，平台定义规格模板：
ComputeSpec.name = "v100-14b"
ComputeSpec.defaultGpumemMb = 14000        → Pod 要申请 14000Mi 显存
ComputeSpec.nodeSelector = {"pool":"v100-14b"}  → 调度到打了 v100-14b 标签的节点
ComputeSpec.resourceQuotaKey = "platform.io/v100-14b"
```

**ComputeSpec 的作用是匹配 HAMi 切好的 vGPU 规格：**

| 阶段 | 谁做 | 做什么 |
|------|------|--------|
| **切分** | HAMi（集群侧） | 将物理 GPU 切成不同大小的 vGPU 单元，打标签暴露 |
| **定义规格模板** | 平台管理员 | 创建 ComputeSpec，声明需要哪种 vGPU 规格 |
| **调度路由** | 平台 K8sResourceBuilder | Pod 注入 `nodeSelector` + `defaultGpumemMb`，HAMi 自动分配对应 vGPU |
| **配额限制** | 平台 WorkspaceService | 通过 ResourceQuota 限制 namespace 内该规格副本数 |

---

## 4. 流程全貌

```
Step 1: 集群管理员部署 HAMi + 配置 vGPU 切分规格 + 给节点打标签
        （此工作在 K8s 集群侧完成，ACMP 不涉及）

Step 2: 平台管理员注册物理集群
        → 集群连接信息（kubeconfig）
        → 节点标签已知（pool:v100-14b 等）

Step 3: 平台管理员创建 ComputeSpec
        → name="v100-14b"
        → defaultGpumemMb=14000        （申请 14GB 显存）
        → nodeSelector={"pool":"v100-14b"}  （路由到对应节点）
        → gpuBrand=NVIDIA

Step 4: 平台管理员创建逻辑资源池
        → 关联物理集群
        → 绑定 ComputeSpec，设置 totalQuota（该规格最多几个 Pod）

Step 5: 创建工作空间
        → 申请该规格的 maxQuota（该工作空间最多几个 Pod）
        → WorkspaceService 在物理集群上创建 Namespace + ResourceQuota

Step 6: 用户部署 vLLM，指定 specName="v100-14b"，replicas=1
        → K8sResourceBuilder 生成 Pod：
            limits: {
              nvidia.com/gpumem: "14000Mi"    ← HAMi 读取此值分配对应大小 vGPU
              nvidia.com/gpu: "1"
              platform.io/v100-14b: "1"         ← ResourceQuota 计量
            }
            nodeSelector: {pool: "v100-14b"}    ← HAMi 调度到此标签节点
        → HAMi 在节点上找到满足 gpumem≥14000Mi 的空闲 vGPU 单元，绑定给容器
        → ResourceQuota 自动累加 used.platform.io/v100-14b
```

---

## 5. 算力切分与平台配额的关系

```
逻辑池 totalQuota = 10（允许 10 个 v100-14b 规格的 Pod）
    ↓
ResourceQuota hard.platform.io/v100-14b = 10
    ↓
K8s API Server 校验 used ≤ hard
    ↓
即使 HAMi 切出 6 个 v100-14b vGPU（每节点），
    平台通过 totalQuota=10 限制不超过 10 个 Pod 运行
```

**平台配额 ≠ HAMi vGPU 数量**

| 概念 | 含义 |
|------|------|
| HAMi vGPU 总数 | 物理 GPU * 切分比例（如 A100 切成 6 个 1/6 vGPU）|
| 逻辑池 totalQuota | 平台允许该池运行的该规格 Pod 总数（由平台管理员配置）|
| 关系 | totalQuota 应 ≤ HAMi vGPU 总数（否则超售）|

---

## 6. 关键结论

1. **算力切分在集群侧（HAMI）完成**，平台代码不参与切分
2. **ComputeSpec 是匹配工具**，不是切分工具。平台通过 spec.nodeSelector 将 Pod 路由到对应 vGPU 规格节点
3. **平台配额（totalQuota / maxQuota）是业务层限制**，控制"允许跑多少个 Pod"，与 HAMi 的 vGPU 单元是两个独立维度
4. **超售风险**：如果 totalQuota > HAMi vGPU 总数，用户部署成功但 Pod 会在节点侧 pending（HAMi 无法分配对应 vGPU）。建议 totalQuota ≤ HAMi vGPU 实际可用数
5. **存储未纳入切分体系**：存储切分由 StorageClass + PVC 控制，不在本平台配额体系内

---

## 7. 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `docs/HETEROGENEOUS-COMPUTE.md` | 补充限制说明第 4 条（存储未纳入） |
| `docs/LOG.md` | 记录本次更新 |