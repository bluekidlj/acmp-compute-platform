# 架构设计

## 一、核心设计原则

**"物理属性归物理池，标准定义归规格，逻辑池只存关联关系"**

| 数据 | 存储位置 | 说明 |
|------|---------|------|
| 节点标签、污点 | **物理集群唯一存储** | 物理固有属性，逻辑池不存 |
| 规格（ResourceRequirements） | **全局规格库唯一存储** | 所有池引用同一份 |
| 逻辑池 | **只存名称+部门+关联** | 聚合容器，不存调度规则 |

---

## 二、数据模型

### 物理集群（physical_cluster）

```
id, name, kubeconfig(加密), gpu_types, location
node_labels:  {"pool":"nvidia-gpu-pool"}              ← 调度器筛选节点
taints:       [{"key":"hami.io/gpu","value":"present","effect":"NoSchedule"}]
```

### 逻辑资源池（resource_pool）

纯聚合容器：
- `resource_pool_physical_cluster` — 关联物理集群（M2M）
- `resource_pool_spec_quota` — 按规格总配额（L1）
- `workspace_resource_pool` — 工作空间绑定
- `workspace_pool_spec_quota` — 工作空间按规格配额（L2）

### 算力规格（compute_spec）

全局规格库：
- `defaultGpuCount/defaultGpumemMb/defaultGpucores` — K8s 资源请求
- `nodeSelector/tolerations` — 调度约束
- `resourceQuotaKey` — 平台计量键（`platform.io/{spec}`）
- `specType` — PHYSICAL/VIRTUAL（物理整卡 or HAMi 切分）

---

## 三、异构算力调度

同一逻辑池可关联多个物理集群（NVIDIA GPU / Hygon DCU / Huawei Ascend），部署时根据规格动态路由到对应节点。

### 调度链路

```
用户指定 specName=xxx
       ↓
PoolMetadataService.pickClusterForSpec(poolId, spec, workspaceId)
       ↓
匹配 spec.nodeSelector 与 cluster.nodeLabels
       ↓
K8sResourceBuilder 生成 Pod:
  nodeSelector: {pool: xxx}        ← 路由到对应节点
  limits:
    nvidia.com/gpu: "1"
    nvidia.com/gpumem: "20480Mi"  ← HAMi 分配 vGPU
    platform.io/xxx: "1"           ← ResourceQuota 计量
       ↓
HAMi 在节点上找到满足条件的 vGPU 单元
```

### 关键设计

1. **无需 primaryClusterId** — workspace 通过 `workspace_pool_cluster` 关联所有物理集群
2. **规格驱动路由** — `spec.nodeSelector` 决定 Pod 落到哪类节点
3. **HAMi 切分发生在集群侧** — 平台只做规格匹配，不参与 GPU 切分

---

## 四、双层配额

```
L1（逻辑池层）:
  resource_pool_spec_quota.total_quota - allocated = 可用

L2（工作空间层）:
  workspace_pool_spec_quota.max_quota - used = 可用
```

部署时两层配额同时校验，都通过才允许调度。

---

## 五、关键代码路径

| 功能 | 关键类 |
|------|--------|
| 集群注册 | `PhysicalClusterService.register()` |
| 节点扫描 | `PhysicalClusterService.scanNodes()` |
| 规格匹配 | `PoolMetadataService.pickClusterForSpec()` |
| 部署 vLLM | `ModelDeploymentService.deploy()` |
| 资源构建 | `K8sResourceBuilder.buildVllmDeploymentAndService()` |
| 双层配额 | `QuotaService.validateBothLevelQuotas()` |

---

## 六、文档索引

- [API 参考](./API-REFERENCE.md)
- [异构算力示例](./HETEROGENEOUS-EXAMPLE.md)
- [HAMi vGPU 切分](./HAMI-PARTITION.md)
- [节点纳管](./NODE-ONBOARDING.md)
- [权限设计](./PERMISSION-DESIGN.md)