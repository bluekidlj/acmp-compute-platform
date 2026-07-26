# Node 整体入池与规格调度 MVP

## 实施状态

Node 数据字段、整机入池接口、Kubernetes Node 标签、Deployment `nodeSelector`
和前端 Node 选择流程已经实现。当前 Windows 演示集群保留原数据，等待管理员后续手动
清理并验证真实入池流程。

## 1. 改造目标

平台以 Kubernetes Node 作为资源池归属单位。一台 Node 上的全部 GPU 必须一次性加入
独享池或共享池，禁止同一 Node 同时存在独享、共享和未入池 GPU。

本方案只面向单管理员、单主体演示，不实现并发控制、分布式事务、自动补偿和复杂重试。

## 2. 核心约束

1. 一个 Node 只能属于一个资源池。
2. Node 入池时处理该 Node 当前发现的全部 GPU。
3. 同一 Node 的 GPU 使用同一种共享比例或统一使用独享模式。
4. 相同硬件和资源参数继续复用已有算力规格。
5. Node 入池时由 ACMP 自动调用 Kubernetes API 写入调度标签。
6. 推理 Deployment 根据算力规格自动生成 `nodeSelector`。
7. 管理员不再逐张选择和维护 GPU。

## 3. 最小数据改造

`cluster_node` 增加两个字段：

```sql
resource_pool_id VARCHAR(64)
compute_spec_id  VARCHAR(64)
```

- `resource_pool_id`：Node 属于独享池还是共享池。
- `compute_spec_id`：Node 当前统一使用的算力规格。

`gpu_device.resource_pool_id` 和 `gpu_device.compute_spec_id` 继续保留，作为设备明细和容量
统计依据，但其值由 Node 入池流程批量维护，不再允许前端逐卡修改。

MVP 假设一台 GPU Node 上的 GPU 品牌和型号一致。集群同步发现 Node 内存在多个型号时，
入池接口直接返回明确错误，暂不拆分多个规格。

## 4. Node 入池接口

废弃前端逐卡调用：

```http
POST /api/v1/resource-pools/{poolId}/gpus/{gpuId}/join
```

新增：

```http
POST /api/v1/resource-pools/{poolId}/nodes/{nodeId}/join
```

请求字段沿用当前规格输入：

```json
{
  "name": "nvidia-v100-exclusive",
  "displayName": "Tesla V100 独享",
  "gpuShare": null,
  "cpuCores": 8,
  "memoryGib": 32,
  "description": "V100 独享推理规格"
}
```

共享池示例：

```json
{
  "name": "nvidia-a100-shared-quarter",
  "displayName": "A100 共享 1/4",
  "gpuShare": "1/4",
  "cpuCores": 4,
  "memoryGib": 16
}
```

## 5. Node 入池主流程

按以下顺序执行，不实现事务补偿：

1. 查询 `cluster_node`，校验 Node 存在且状态为 Ready。
2. 查询 Node 当前发现的全部在线 GPU。
3. 校验 GPU 数量大于 0，且品牌、型号一致。
4. 校验 Node 和 GPU 尚未加入任何资源池。
5. 根据目标池校验独享或共享参数。
6. 按资源字段查找已有算力规格，存在则复用，否则创建。
7. 调 Kubernetes API 给真实 Node 写入标签。
8. 更新 `cluster_node` 的资源池和规格。
9. 批量更新该 Node 全部 GPU 的资源池和规格。
10. 返回 Node、GPU 数量和规格信息。

任何一步失败立即停止并返回错误。日志必须记录当前步骤、clusterId、nodeName、poolId、
specId 和 Kubernetes API 响应。

## 6. Kubernetes Node 标签

独享 Node：

```yaml
metadata:
  labels:
    acmp.ai/pool-type: exclusive
    acmp.ai/compute-spec: nvidia-v100-exclusive
    acmp.ai/gpu-brand: nvidia
    acmp.ai/gpu-model: tesla-v100
```

共享 Node：

```yaml
metadata:
  labels:
    acmp.ai/pool-type: shared
    acmp.ai/compute-spec: nvidia-a100-shared-quarter
    acmp.ai/gpu-brand: nvidia
    acmp.ai/gpu-model: nvidia-a100-sxm4-80gb
```

标签值必须转换为 Kubernetes 合法的小写值。`compute-spec` 使用规格名，不使用随机 UUID，
便于管理员通过 `kubectl get nodes --show-labels` 排查。

Node Patch 操作也要打印提交内容和 Kubernetes API 成功或失败日志，但不能打印 kubeconfig。

## 7. Deployment 调度规则

`K8sResourceBuilder` 根据所选规格增加：

```yaml
spec:
  template:
    spec:
      nodeSelector:
        acmp.ai/pool-type: exclusive
        acmp.ai/compute-spec: nvidia-v100-exclusive
```

共享规格使用：

```yaml
nodeSelector:
  acmp.ai/pool-type: shared
  acmp.ai/compute-spec: nvidia-a100-shared-quarter
```

GPU、CPU 和内存 requests/limits 继续由算力规格生成。多台 Node 使用相同规格标签时，
Kubernetes 在所有匹配 Node 中选择资源可用的主机。

## 8. 前端改造

资源池“加入”抽屉改为：

1. 选择集群。
2. 选择未入池 Node。
3. 展示 Node 名称、IP、状态、GPU 品牌、型号和总数。
4. 填写或确认统一规格参数。
5. 点击“整台 Node 加入资源池”。

删除 GPU 单选框和逐卡入池入口。

资源池列表按 Node 展示：

- Node 名称
- Internal IP
- GPU 品牌和型号
- GPU 数量
- 资源池类型
- 算力规格
- Kubernetes 标签状态
- Node 状态

## 9. 集群同步行为

同步 Kubernetes Node 时保留数据库中的 `resource_pool_id` 和 `compute_spec_id`，不能被发现
数据覆盖。同步读取 Kubernetes labels，用于页面展示和简单一致性提示。

如果已经入池的 Node 后续发现新增 GPU：

- MVP 不自动修改规格和容量。
- 页面显示“发现新增 GPU，请重新确认节点资源”。

## 10. 当前演示数据处理

当前 `desktop-worker` 是混合状态：

- GPU 0、1：共享池
- GPU 2、3：独享池
- GPU 4～7：未入池

实施前必须删除现有推理部署和租户规格配额，再清理 GPU/规格关联，最后将
`desktop-worker` 的 8 张模拟 A100 一次性加入用户选择的目标池。

## 11. 验收流程

1. 集群详情显示 `desktop-worker` 有 8 张 GPU。
2. 选择共享池或独享池，将整台 Node 一次性入池。
3. 数据库中 Node 和 8 张 GPU 的 pool/spec 关联一致。
4. `kubectl get node desktop-worker --show-labels` 能看到 ACMP 标签。
5. 部署 Demo 推理服务。
6. 后端日志打印包含 `nodeSelector` 的完整 Deployment YAML。
7. `kubectl get pod -o wide` 显示 Pod 调度到标签匹配的 Node。
8. 推理服务状态进入 Ready。

## 12. TODO（不进入本次 MVP）

- TODO：Node 从共享池迁移到独享池或反向迁移。
- TODO：Node 移出资源池以及自动删除 Kubernetes 标签。
- TODO：数据库失败后的 Kubernetes 标签自动补偿。
- TODO：Kubernetes Patch 的冲突重试和并发控制。
- TODO：一台 Node 内存在多个 GPU 型号时自动拆分规格。
- TODO：同步发现新增 GPU 后自动继承规格并扩容。
- TODO：多 GPU Node 的 Local PV、模型节点标签和模型预热。
- TODO：Node 标签与数据库状态的自动修复。
- TODO：生产环境细粒度 RBAC 和操作审计。
