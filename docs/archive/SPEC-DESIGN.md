# 算力规格（ComputeSpec）与 K8s 资源映射设计文档

## 1. 核心对应关系

算力平台的每个概念，在 K8s 中都有精确的对应对象：

| 算力平台概念 | K8s 对象 | 操作方式 |
|-------------|---------|---------|
| 物理服务器上架 | Node 对象 | 加入集群，自动上报 capacity |
| 虚拟物理池 | Node 标签 + 污点 | `label node X pool=nvidia-gpu` / `taint node X nvidia.com/gpu=present:NoSchedule` |
| 逻辑资源池 | **DB 记录** | 平台层记录：总配额 `nvidia-rtx4090-24g=1, hygon-dcu-32g=1` |
| 工作空间 | **Namespace + ResourceQuota** | 创建 NS + RQ 限制 `platform.io/nvidia-rtx4090-24g=1` |
| 规格 | **预设 ResourceRequirements** | name→{gpu,cpu,mem,nodeSelector,tolerations} |
| 任务 | Pod | resources + nodeSelector + tolerations |
| 调度 | K8s Scheduler | 按 nodeSelector + 污点容忍匹配节点 |
| 运行 | Kubelet + Cgroup | 容器资源隔离 |
| 结束 | Pod 删除 | ResourceQuota 计数自动恢复 |
| 统计 | Metrics Server | 资源使用数据 → 账单 |

---

## 2. 资源量流转全过程

```
算力平台操作                          K8s 实际操作
──────────                          ──────────
1. 物理服务器上架                     → Node 自动上报 capacity
                                     NAME          CPU   MEMORY   NVIDIA.COM/GPU
                                     node-nvidia   64    256Gi    8
                                     node-dcu      64    256Gi    4

2. 创建虚拟物理池                     → 给 Node 打标签和污点
                                     node-nvidia: labels={pool:nvidia-gpu}
                                                  taints={nvidia.com/gpu=present:NoSchedule}
                                     node-dcu:    labels={pool:hygon-dcu}
                                                  taints={amd.com/dcu=present:NoSchedule}

3. 创建逻辑资源池                     → DB INSERT resource_pool + resource_pool_spec_quota
                                     算法部逻辑池: nvidia-rtx4090-24g=1, hygon-dcu-32g=1

4. 部门创建工作空间                   → K8s: Namespace + ResourceQuota
                                     kind: ResourceQuota
                                     metadata: { namespace: llm-training }
                                     spec:
                                       hard:
                                         platform.io/nvidia-rtx4090-24g: "1"
                                         platform.io/hygon-dcu-32g: "1"

5. 用户提交任务（指定规格）            → 平台校验配额 → 翻译为 Pod
                                     spec: nvidia-rtx4090-24g
                                     → Pod:
                                       resources.limits: nvidia.com/gpu=1, cpu=8, memory=32Gi
                                       nodeSelector: pool=nvidia-gpu
                                       tolerations: nvidia.com/gpu:NoSchedule

6. K8s Scheduler 调度                → 匹配 node-nvidia（tolerates taint + matches label）

7. Kubelet 创建容器                   → Cgroup 限制 CPU/memory

8. Pod 删除                          → ResourceQuota used 自动 -1

9. 平台统计                          → Metrics Server API → 用量报表
```

---

## 3. 数据模型

### 3.1 `compute_spec`（规格 = 预设 ResourceRequirements）

| 字段 | 说明 | 示例 |
|------|------|------|
| name | 唯一规格名 | `nvidia-rtx4090-24g` |
| default_gpu_count | 每副本 GPU 数 | `1` |
| default_cpu_cores | 每副本 CPU 核数 | `8` |
| default_memory_gib | 每副本内存 (GiB) | `32` |
| **node_selector** | JSON: 目标节点标签 | `{"pool":"nvidia-gpu"}` |
| **tolerations** | JSON: 污点容忍 | `[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]` |
| **resource_quota_key** | ResourceQuota 中的资源键 | `platform.io/nvidia-rtx4090-24g` |

> 命名规范：`{brand}-{model}-{memory}`，如 `nvidia-a100-80g`、`hygon-dcu-32g`。
> `resource_quota_key` 默认 = `platform.io/{name}`。

### 3.2 物理集群的节点标签识别

虚拟物理池是同一 K8s 集群中按标签/污点划分的节点组。标签从 Node 对象读取，与 `compute_spec.node_selector` 匹配。

### 3.3 `resource_pool_spec_quota`（逻辑池按规格配额）

| 字段 | 说明 |
|------|------|
| resource_pool_id | 逻辑池 |
| spec_id | 规格 |
| total_quota | 该规格在逻辑池的总配额 |

### 3.4 `workspace_spec_quota`（工作空间按规格配额）

| 字段 | 说明 |
|------|------|
| workspace_id | 工作空间 |
| spec_id | 规格 |
| max_quota | 上限 |
| used_quota | 已使用（平台扣减） |

> K8s ResourceQuota 使用 `platform.io/{specName}` 作为资源键自动追踪 used。平台层 `workspace_spec_quota` 做备份。

---

## 4. Spec → Pod 转换

```
用户提交：规格="nvidia-rtx4090-24g", 工作空间="llm-training"

1. 查 ComputeSpec:
   { gpuCount:1, cpuCores:8, memoryGib:32,
     nodeSelector: {"pool":"nvidia-gpu"},
     tolerations: [{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}],
     resourceQuotaKey: "platform.io/nvidia-rtx4090-24g" }

2. 校验 workspace_spec_quota: used + 1 ≤ max

3. 构建 Pod:
   resources: { nvidia.com/gpu:1, cpu:8, memory:32Gi }
   nodeSelector: { pool: nvidia-gpu }
   tolerations: [{ key: nvidia.com/gpu, operator: Exists, effect: NoSchedule }]

4. 提交到 K8s → Scheduler 匹配 node-nvidia
```

---

## 5. 示例：两个物理池的完整映射

```
┌─────────────────────────────────────────────────────────┐
│  K8s 集群                                                │
│  ┌─────────────────────┐  ┌─────────────────────┐        │
│  │ node-nvidia          │  │ node-dcu             │        │
│  │ labels:              │  │ labels:              │        │
│  │   pool=nvidia-gpu    │  │   pool=hygon-dcu     │        │
│  │ taints:              │  │ taints:              │        │
│  │   nvidia.com/gpu     │  │   amd.com/dcu        │        │
│  │   =present:NoSchedule│  │   =present:NoSchedule│        │
│  │ capacity:            │  │ capacity:            │        │
│  │   nvidia.com/gpu: 8  │  │   amd.com/dcu: 4     │        │
│  └──────────┬──────────┘  └──────────┬──────────┘        │
│             │                        │                    │
│  ┌──────────▼────────────────────────▼──────────┐        │
│  │           Namespace: llm-training              │        │
│  │  ResourceQuota:                               │        │
│  │    platform.io/nvidia-rtx4090-24g: "1"        │        │
│  │    platform.io/hygon-dcu-32g: "1"             │        │
│  │  ┌──────────────┐  ┌──────────────┐          │        │
│  │  │ Pod train-nv │  │ Pod train-dcu│          │        │
│  │  │ nodeSelector │  │ nodeSelector │          │        │
│  │  │ pool=nvidia  │  │ pool=hygon   │          │        │
│  │  │ nvidia/gpu:1 │  │ amd/dcu:1    │          │        │
│  │  └──────────────┘  └──────────────┘          │        │
│  └──────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────┘
```

---

## 6. 代码清单

| 文件 | 说明 |
|------|------|
| `entity/ComputeSpec.java` | +nodeSelector, +tolerations, +resourceQuotaKey |
| `schema-h2.sql` | compute_spec 加 3 列；恢复 workspace_spec_quota |
| `ComputeSpecMapper.java/xml` | 加新列映射 |
| `WorkspaceService.create()` | ResourceQuota 用 platform.io/{spec} 键 |
| `ComputeSpecService` | toPodNodeSelector(), toPodTolerations(), toPodResources() |
| `docs/SPEC-DESIGN.md` | 本文档 |
