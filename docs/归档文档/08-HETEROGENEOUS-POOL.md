# 异构算力资源池设计（v1.0）

> 本文档是 1.0 同构资源池的扩展：在"池里只能放同一品牌卡"基础上，支持"池里多张不同品牌卡共池"。

## 1. 核心模型

### 1.1 抽象：池 = 可调度节点集合

```
1 张物理卡 → 加池时选 1 个 spec → 变 N 个"可调度节点"
- EXCLUSIVE 池 + PHYSICAL spec → 1 节点（整卡独占）
- SHARED 池 + VIRTUAL spec（1/4 切分）→ 4 节点
- SHARED 池 + VIRTUAL spec（1/8 切分）→ 8 节点
```

公式：
```
slots = cardMemMb / spec.defaultGpumemMb     # VIRTUAL
slots = 1                                     # PHYSICAL / OVERSELL
```

### 1.2 异构 = 池里多 spec

一个池里**可以同时存在**多张不同品牌卡，但**每张卡独立选 spec**。

例：一个 EXCLUSIVE 池有：
- 2 张 A100（spec: `exclusive-nvidia-a100-80g`，slots=1 各 → 共 2 节点）
- 1 张 DCU（spec: `exclusive-hygon-dcu`，slots=1 → 共 1 节点）

池 capacity = 2 + 1 = 3 节点。

部署时通过 `specName` 选品牌——Pod 仍申请单一品牌卡（vLLM 单 Pod 用 1 卡，1.0 不做多品牌 Pod）。

### 1.3 关键不变量（DB ↔ K8s 严格对应）

```
池容量层
  pool.totalNodes      = SUM(pool_card.slots WHERE pool_id AND status='active')
  pool.allocatedNodes  = SUM(prq.totalNodes WHERE pool_id)

项目配额层
  prq.totalNodes       = 管理员分配（项目从此池可分到的节点数）
  prq.usedNodes        = 此项目在此 (pool, spec) 上 running 的 Pod 数

K8s ResourceQuota.hard
  hard["platform.io/{spec}"] = SUM(pool_card.slots WHERE pool_id AND spec_id)
  hard["pods"]               = max(50, pool.totalNodes * 10)

约束：
  任意 prq：prq.usedNodes ≤ prq.totalNodes
  任意 pool：SUM(prq.total) ≤ pool.allocatedNodes ≤ pool.totalNodes
  K8s 实际 Pod 数（status=running, 非 OVERSELL）= SUM(prq.usedNodes WHERE spec)
```

## 2. 数据模型

### 2.1 新增表 `pool_card`

```sql
CREATE TABLE pool_card (
    id           VARCHAR(64) PRIMARY KEY,
    pool_id      VARCHAR(64) NOT NULL,
    gpu_brand    VARCHAR(32) NOT NULL,        -- NVIDIA / HYGON / HUAWEI_ASCEND
    gpu_model    VARCHAR(64) NOT NULL,
    node_name    VARCHAR(128),                -- K8s 节点名（来自 GpuInventoryService 扫描）
    serial_no    VARCHAR(128),                -- 可选
    spec_id      VARCHAR(64) NOT NULL,        -- 这张卡在池里应用哪个规格
    slots        INT NOT NULL,                -- 1 卡 + 1 spec = N 节点
    status       VARCHAR(20) DEFAULT 'active', -- active / removed
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (pool_id, node_name, serial_no, spec_id)
);
```

**设计要点**：
- `UNIQUE(pool, node, serial, spec)` 强制 1 张卡 1 个 spec 不重复
- `slots` 在加卡时由 `computeSlots()` 写入，之后不变
- `node_name` 用于部署时生成 `nodeAffinity`（让 Pod 落到有卡的节点）

### 2.2 改动表

```sql
-- resource_pool 加 capacity_strategy 字段（预留，1.0 不用）
ALTER TABLE resource_pool ADD COLUMN capacity_strategy VARCHAR(32) DEFAULT 'SUM_SLOTS';

-- model_deployment 加 2 列
ALTER TABLE model_deployment ADD COLUMN pool_card_id VARCHAR(64);
ALTER TABLE model_deployment ADD COLUMN resource_key VARCHAR(128);
```

`project_resource_quota` 表**不动**——`(project, pool, spec, total, used)` 已经够用。

## 3. API

### 3.1 `POST /api/v1/pools/{poolId}/cards`

加卡到池。

```json
POST /api/v1/pools/{poolId}/cards
{
  "gpuBrand": "NVIDIA",
  "gpuModel": "NVIDIA-A100-SXM4-80GB",
  "nodeName": "gpu-node-01",
  "serialNo": "GPU-1234",
  "specId": "spec-shared-a100-14"
}
```

**后端逻辑**：
1. 校验池存在 + spec 存在
2. 校验 `spec.poolType == pool.poolType`（EXCLUSIVE spec 只能进 EXCLUSIVE 池）
3. 校验 `spec.gpuBrand == card.gpuBrand`（A100 卡只能加 A100 spec）
4. 校验 `UNIQUE(pool, node, serial, spec)` 不重复
5. 算 `slots = cardMem / spec.defaultGpumemMb`
6. INSERT `pool_card`
7. **重算池 capacity + 同步 K8s ResourceQuota**（失败 log warn 继续）

### 3.2 `DELETE /api/v1/pools/{poolId}/cards/{cardId}?force=false`

从池移除卡。

**校验逻辑**：
- 删卡后该 spec 的剩余 slots ≥ 该 spec 下 prq.used 之和 → 普通删
- 否则返 400，提示 `?force=true` 强制
- `force=true` 模式：截断 prq.used 到剩余值

**后端逻辑**：
1. 校验卡存在
2. 算 `poolSpecSlotsAfter = SUM(pool_card.slots) - thisCard.slots`
3. 算 `maxUsedForSpec = SUM(prq.usedNodes WHERE pool, spec)`
4. `maxUsedForSpec > poolSpecSlotsAfter` → 普通返 400；`force=true` 截断
5. DELETE `pool_card`
6. 若 force 截断：UPDATE `prq.usedNodes = poolSpecSlotsAfter`
7. **重算池 capacity + 同步 K8s**（失败 warn 继续）

### 3.3 `GET /api/v1/pools/{poolId}/cards`

列池里所有卡 + 按 spec 汇总。

```json
{
  "poolId": "pool-xxx",
  "cards": [
    { "id":"pcard-1", "gpuBrand":"NVIDIA", "gpuModel":"A100-80GB", "nodeName":"gpu-node-01", "specId":"spec-shared-a100-14", "slots":4 },
    { "id":"pcard-2", "gpuBrand":"NVIDIA", "gpuModel":"A100-80GB", "nodeName":"gpu-node-01", "specId":"spec-shared-a100-12", "slots":2 },
    { "id":"pcard-3", "gpuBrand":"HYGON",  "gpuModel":"DCU",      "nodeName":"gpu-node-02", "specId":"spec-exclusive-hygon-dcu", "slots":1 }
  ],
  "totalNodes": 7,
  "bySpec": {
    "spec-shared-a100-14": { "cards": 1, "slots": 4 },
    "spec-shared-a100-12": { "cards": 1, "slots": 2 },
    "spec-exclusive-hygon-dcu": { "cards": 1, "slots": 1 }
  }
}
```

## 4. K8s 落地的关键变化

### 4.1 部署时生成 `nodeAffinity`

读 `pool_card.node_name` 聚合所有卡所在节点，作为 Pod 的 `nodeAffinity`，让 Pod 落到有卡的节点。

```yaml
spec:
  template:
    spec:
      affinity:
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
            - matchExpressions:
              - key: kubernetes.io/hostname
                operator: In
                values: ["gpu-node-01"]
```

### 4.2 部署时 K8s 失败 → 回滚 prq.used

保证 DB 与 K8s 一致：

```java
try {
    clientManager.createVllmDeploymentAndService(...);
    deploymentMapper.updateStatus(id, "running", serviceUrl);
} catch (Exception err) {
    // 回滚 prq.used
    projectQuotaMapper.updateUsedNodes(quota.getId(), used);  // 减回去
    deploymentMapper.updateStatus(id, "failed", null);
    throw new RuntimeException(...);
}
```

### 4.3 加卡/删卡时同步 K8s ResourceQuota

`platform.io/{spec}` = `SUM(pool_card.slots WHERE pool, spec)`：

```yaml
apiVersion: v1
kind: ResourceQuota
metadata: { name: quota-shared-xxx, namespace: ws-foo }
spec:
  hard:
    platform.io/spec-shared-a100-1-4: "16"     # 4 张卡 × 4 节点
    platform.io/spec-shared-a100-1-2: "4"      # 2 张卡 × 2 节点
    pods: "200"
```

K8s ResourceQuota 多键天然支持（K8s scheduler 累计所有 key 校验）。

## 5. 端到端示例

```
0. 集群扫描：1 节点 gpu-node-01（NVIDIA A100 x4）+ 1 节点 gpu-node-02（DCU x1）
1. 注册集群、扫描、写 gpuTypes
2. POST /workspaces → ws-id, namespace=ws-ai-rd-xxx（自动建 3 类池，totalNodes=0）
3. 加 4 张 A100 到 shared 池（应用 1/4 切分）：
   POST /pools/{shared}/cards × 4
   → pool.totalNodes = 4 × 4 = 16
   → K8s ResourceQuota.hard["platform.io/spec-shared-a100-1-4"] = 16
4. 加 1 张 DCU 到 exclusive 池：
   POST /pools/{exclusive}/cards
   → pool.totalNodes = 1
   → K8s ResourceQuota.hard["platform.io/spec-exclusive-hygon-dcu"] = 1
5. 配额：项目 A 申请 shared 池 1/4 切分 4
   → prq.usedNodes=0, prq.totalNodes=4
   → pool.allocatedNodes += 4
   → 校验 pool_card.slots(16) ≥ 现有+申请(4) ✓
6. 部署：
   POST /projects/{proj}/deployments
   { "specName":"spec-shared-a100-14", "replicas":1, "image":"vllm/vllm-openai:latest" }
   → 校验 prq.used(0)+1 ≤ prq.total(4) ✓
   → prq.used: 0 → 1
   → preferredNodes = [gpu-node-01]（从 pool_card 读）
   → K8s Deployment 提交：
     - limits: platform.io/spec-shared-a100-1-4: 1
     - affinity.nodeAffinity: hostname In [gpu-node-01]
   → status: pending → running
7. 删卡：DELETE /pools/{shared}/cards/{card-3}?force=true
   → 删后 poolSpecSlots = 12 < prq.used(1) → 截断 prq.used=12 (无变化)
   → 删 pool_card
   → pool.totalNodes: 16 → 12
   → K8s ResourceQuota.hard: 16 → 12
```

## 6. 一致性保证

| 场景 | 触发点 | 同步动作 |
|---|---|---|
| 加卡 | `PoolCardService.addCard` | 重算 `pool.totalNodes` + K8s ResourceQuota |
| 删卡 | `PoolCardService.removeCard` | 重算 `pool.totalNodes` + K8s ResourceQuota；force=true 截断 prq |
| 部署 | `ModelDeploymentService.deploy` | 校验 `prq.used+1 ≤ prq.total`；K8s 失败回滚 `prq.used` |
| 删部署 | `ModelDeploymentService.delete` | K8s 删后回滚 `prq.used` |
| 删 WS | `WorkspaceService.delete` | K8s NS 删 + pool_card 级联删 + K8s Queue 残留手工清 |

**没有对账 scheduler、没有分布式锁、没有补偿事务**——一致性靠：
1. 业务逻辑严格按公式 `slots = cardMem / gpumem`
2. 关键路径（部署）有 try/catch 回滚
3. 失败时 DB 与 K8s 都保留可恢复状态

## 7. 已知限制

- 1 张卡 1 池 1 spec（UNIQUE 约束）：不能同一卡既 EXCLUSIVE 又 SHARED
- 1.0 不做对账：DB 与 K8s 不一致时靠运维手工调整（删卡时强制同步）
- Pod 单品牌：1.0 简化决策，vLLM 单 Pod 1 卡
- `totalNodes` 字段 PATCH 已 deprecated，但保留作冗余缓存

## 8. 文件清单

### 新建

- `entity/PoolCard.java`
- `mapper/PoolCardMapper.java` + `PoolCardMapper.xml`
- `dto/PoolCardRequest.java` + `PoolCardResponse.java`
- `service/PoolCardService.java`
- `controller/PoolCardController.java`

### 修改

- `schema-h2.sql`（pool_card + 2 处 ALTER）
- `entity/ModelDeployment.java`（加 poolCardId + resourceKey）
- `service/ModelDeploymentService.java`（deploy 用 preferredNodes + K8s 失败回滚；delete 回滚 prq）
- `service/ProjectQuotaService.java`（allocate 用 pool_card.slots 校验）
- `service/ResourcePoolService.java`（update 不再支持 totalNodes）
- `k8s/K8sResourceBuilder.java`（buildVllmDeployment 加 preferredNodes 参数）
- `mapper/ModelDeploymentMapper.xml`（新列）
