# Gpu 入池、算力规格与租户配额 0.1 设计

> 更新说明：逐张 GPU 入池接口已被 Node 整体入池方案替代。当前实现以
> `docs/24-NODE-POOL-JOIN-AND-SCHEDULING-MVP.md` 为准。

## 1. 目标

0.1 版本将“Gpu 入池”和“创建算力规格”合并为一个管理员操作，形成以下核心流程：

```text
发现 Gpu
  → Gpu 加入独享池或共享池并创建算力规格
  → 算力规格形成可分配节点
  → 管理员为租户分配规格节点配额
  → 项目继承租户配额
  → 推理服务按规格节点数创建 Pod 副本
```

本版本优先保证功能可用，不增加移出资源池、重新切分、复杂并发控制和自动对账。

## 2. 概念边界

### 2.1 Kubernetes Node

Kubernetes 集群中的真实服务器。Node 不进入资源池，只用于表示 Gpu 所在位置以及提供
CPU、内存等集群资源。

### 2.2 GpuDevice

Kubernetes Node 上的一张物理 Gpu，是资源池的入池对象。一张 Gpu 最多属于一个资源池。
同一个 Kubernetes Node 上的不同 Gpu 可以分别进入独享池或共享池。

### 2.3 算力规格节点

平台分配和计量概念，不是 Kubernetes Node。

一个算力规格节点固定包含：

- 1 张独享 Gpu，或者一张 Gpu 的一个共享份额；
- 一组 CPU Core；
- 一组内存。

一个算力规格节点对应推理 Deployment 的一个 Pod 副本。

## 3. 固定资源池

平台只保留两个资源池：

| 资源池 | ID | 含义 |
| --- | --- | --- |
| 独享池 | `pool-exclusive` | Gpu 以整卡方式使用 |
| 共享池 | `pool-shared` | Gpu 通过 HAMi 按固定比例共享 |

资源池只管理 GpuDevice，不管理 Kubernetes Node。

## 4. Gpu 加入独享池

管理员在未入池 Gpu 上点击“加入独享池”，弹窗自动展示：

- Kubernetes Node；
- Gpu 编号；
- Gpu UUID；
- Gpu 型号；
- 目标资源池；
- Gpu 数量固定为 1。

管理员填写：

- 规格唯一名称；
- 展示名称；
- CPU Core；
- 内存 GiB；
- 描述，可选。

确认后在一个数据库事务中：

1. 校验 Gpu 存在且尚未入池；
2. 校验规格名称唯一；
3. 校验 CPU、内存大于 0；
4. 创建 `EXCLUSIVE` 算力规格；
5. 将 Gpu 关联到独享池和新规格；
6. 返回 Gpu、资源池和规格。

独享规格固定：

```text
gpuCount = 1
gpuShare = null
capacityNodes = 1
```

## 5. Gpu 加入共享池

管理员在未入池 Gpu 上点击“加入共享池”，除独享规格字段外还必须选择：

```text
1/8
1/4
1/2
```

确认后创建 `SHARED` 算力规格，并将 Gpu 关联到共享池和新规格。

共享规格节点容量：

| 切分比例 | 可提供规格节点 |
| --- | ---: |
| 1/8 | 8 |
| 1/4 | 4 |
| 1/2 | 2 |

共享规格固定：

```text
gpuCount = 1
gpuShare = 1/8、1/4 或 1/2
```

## 6. 数据关系

```text
GpuDevice
  ├─ resourcePoolId
  └─ computeSpecId

ComputeSpec
  ├─ resourcePoolId
  ├─ gpuModel
  ├─ gpuCount = 1
  ├─ gpuShare
  ├─ cpuCores
  └─ memoryGib
```

0.1 版本一张入池 Gpu 只对应一个算力规格。规格创建后不提供修改、删除和重新切分入口。

## 7. 入池接口

```http
POST /api/v1/resource-pools/{poolId}/gpus/{gpuId}/join
```

独享池请求：

```json
{
  "name": "a100-exclusive",
  "displayName": "A100 独享单卡",
  "cpuCores": 8,
  "memoryGib": 32,
  "description": "A100 独享推理规格"
}
```

共享池请求：

```json
{
  "name": "a100-shared-quarter",
  "displayName": "A100 共享 1/4",
  "gpuShare": "1/4",
  "cpuCores": 4,
  "memoryGib": 16,
  "description": "A100 共享推理规格"
}
```

后端根据 Gpu 和目标池确定 Gpu 型号、规格类型、资源池和 `gpuCount=1`。

## 8. 算力规格页面

算力规格页面不再提供“新增规格”，改为：

```text
算力规格
  ├─ 独享规格
  └─ 共享规格
```

规格使用卡片展示：

- 名称；
- 资源池；
- Gpu 型号；
- 共享比例；
- CPU；
- 内存；
- 可提供节点；
- 已分配租户配额；
- 实际使用节点。

点击卡片打开详情抽屉，展示来源 Gpu、Kubernetes Node、Gpu UUID、创建时间等信息。
0.1 版本详情只读。

## 9. 租户规格配额

租户分配的是某个算力规格的节点数量。

```text
tenantId
specId
total
used
remaining
```

页面统一称为：

- 节点总量；
- 已使用节点；
- 剩余节点。

创建或修改配额时必须满足：

```text
该规格所有租户的 total 合计 <= 规格 capacityNodes
```

示例：

```text
A100 共享 1/4
可提供节点：4

租户 A：2
租户 B：1
尚可分配：1
```

项目不建立自己的配额，所有项目共同使用所属租户的规格配额。

## 10. 部署与配额

部署表单中的“算力节点数”对应 Kubernetes Deployment 的 `replicas`。

部署前检查：

1. 项目、模型和规格存在；
2. 规格固定使用一个 Gpu；
3. 租户已经分配该规格；
4. 租户剩余节点不少于申请数量；
5. 规格对应资源池至少存在候选 Gpu；
6. Kubernetes Deployment 和 Service 能够提交。

提交前按申请节点数增加 `used`。Kubernetes 创建失败时恢复，删除部署时归还。

0.1 版本只有管理员操作，不增加数据库锁、条件更新和并发配额设计。

## 11. Kubernetes 资源

独享规格：

```yaml
resources:
  requests:
    nvidia.com/gpu: "1"
    cpu: "8"
    memory: 32Gi
```

共享规格示例：

```yaml
resources:
  requests:
    nvidia.com/gpu: "1"
    nvidia.com/gpumem-percentage: "25"
    nvidia.com/gpucores: "25"
    cpu: "4"
    memory: 16Gi
```

ACMP 使用资源池中的 Gpu 选择候选集群，最终物理 Gpu 由 Kubernetes/HAMi 调度。
0.1 版本不修改 Kubernetes Node 标签，不增加污点和容忍。

## 12. 0.1 版本范围

实现：

- Gpu 单独入池；
- 入池时创建算力规格；
- 所有规格固定单 Gpu；
- 独享整卡和共享固定比例；
- 规格节点容量；
- 规格卡片和只读详情；
- 租户规格节点配额；
- 项目继承租户配额；
- 部署节点数对应 Pod 副本数。

不实现：

- Kubernetes Node 入池；
- 独立新增规格；
- 修改或删除规格；
- Gpu 移出池；
- Gpu 更换资源池；
- 重新切分；
- Node 标签；
- 污点和容忍；
- 复杂并发控制；
- 自动修复和后台对账。

## 13. 固定约束

```text
Kubernetes Node 不进入资源池
GpuDevice 是唯一入池对象
一张 Gpu 最多属于一个资源池和一个规格
每个规格 gpuCount 固定为 1
独享规格提供 1 个规格节点
共享规格按比例提供 2、4 或 8 个规格节点
租户配额单位是规格节点数
项目共享所属租户配额
部署节点数等于 Kubernetes Pod 副本数
```
