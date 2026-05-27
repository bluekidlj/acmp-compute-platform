# 资源池设计文档（v2.0）

## 一、核心维护原则

**"物理属性归物理池，标准定义归规格，逻辑池只存关联关系"**

| 数据 | 存储位置 | 说明 |
|------|---------|------|
| 节点标签、污点 | **物理集群唯一存储** | 物理固有属性，逻辑池不存 |
| 规格（ResourceRequirements） | **全局规格库唯一存储** | 所有池引用同一份 |
| 逻辑池 | **只存名称+部门+关联** | 聚合容器，不存调度规则 |

---

## 二、数据模型

### physical_cluster

```
id, name, kubeconfig, gpu_types, location
node_labels:  {"pool":"nvidia-gpu-pool"}              ← 调度器筛选节点
taints:       [{"key":"hami.io/gpu","value":"present","effect":"NoSchedule"}]
```

### resource_pool（纯聚合容器）

```
id, name, description, department_code, department_name, status
  ├── resource_pool_physical_cluster   (M2M: 关联物理池)
  ├── resource_pool_spec_quota         (按规格总配额)
  ├── workspace_resource_pool          (工作空间绑定)
  └── workspace_pool_spec_quota        (工作空间按规格配额)
```

### resource_pool_spec_quota

| 字段 | 说明 |
|------|------|
| resource_pool_id + spec_id (PK) | |
| total_nodes | 池内该规格总节点数 |
| allocated_nodes | 已分配给工作空间 |

### workspace_resource_pool

```
workspace_id + resource_pool_id (PK)  — 绑定关系
```

### workspace_pool_spec_quota（双层配额核心）

```
workspace_id + resource_pool_id + spec_id (PK)
  max_nodes:  上限
  used_nodes: 已使用
```

---

## 三、部署全流程（10 步）

```
用户 POST /api/v1/resource-pools/pool-ai/model-deployments
  { "specName":"nvidia-4090-24g", "replicas":1 }

① 绑定校验 → workspace_resource_pool 确认 ws-llm ↔ pool-ai
② 加载调度约束 → 查物理池 nodeLabels+taints
③ 加载规格资源 → compute_spec { gpu:1, cpu:8, mem:32Gi }
④ 双层配额硬校验:
   第一层 pool: total=2, allocated=1, 剩余=1 ≥ 1 ✅
   第二层 ws:   max=1, used=0, 剩余=1 ≥ 1 ✅
⑤ 预扣配额
⑥ 生成 Deployment YAML（自动注入 nodeSelector+tolerations+resources）
⑦ 提交 K8s（workspace 的 SA）
⑧ K8s 调度 → nodeSelector+taint 匹配节点
⑨ Pod Running → 配额正式扣减 → 返回 serviceUrl
⑩ 失败 → 5min 超时 → delete deployment → 回滚配额
```

### 本质

> **指定资源池部署 = 平台自动把该池关联的所有调度规则、配额限制、隔离策略注入 Deployment，提交到工作空间 Namespace。**

---

## 四、代码清单

| 文件 | 改动 |
|------|------|
| `schema-h2.sql` | physical_cluster +nodeLabels/taints；resource_pool 精简；新增 workspace_resource_pool + workspace_pool_spec_quota |
| `PhysicalCluster.java` | +nodeLabels, +taints |
| `PhysicalClusterRegisterRequest.java` | +nodeLabels, +taints |
| `ResourcePool.java` | 精简为 name+department+status |
| `docs/RESOURCE-POOL-DESIGN.md` | 本文档 |
