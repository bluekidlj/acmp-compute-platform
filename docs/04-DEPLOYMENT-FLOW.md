# 1.0 部署推理服务全流程

## 1. 端到端流程

```
管理员 (PLATFORM_ADMIN)                     用户 (INFERENCE_USER)
  │                                           │
  │ 1. 注册物理集群                            │
  │ POST /api/v1/clusters                     │
  │   → 写 physical_cluster                   │
  │   → K8s 客户端缓存                        │
  │                                           │
  │ 2. (可选) 扫描集群                         │
  │ POST /api/v1/clusters/{id}/scan           │
  │   → 写 gpu_types / hami_splits / maxCpu   │
  │                                           │
  │ 3. 创建工作空间                            │
  │ POST /api/v1/workspaces                   │
  │   → 写 workspace                          │
  │   → 自动建 3 个 ResourcePool (三类)        │
  │   → K8s: NS + SA + Role + RB + Queue      │
  │                                           │
  │ 4. 修改池容量 + 关联规格                    │
  │ PATCH /api/v1/pools/{id}                  │
  │   { totalNodes: 5, specs: ["..."] }        │
  │   → 写 resource_pool.total_nodes          │
  │   → 写 resource_pool_spec 关联            │
  │   → K8s: ResourceQuota.hard[...]          │
  │                                           │
  │ 5. 创建项目                                │
  │ POST /api/v1/workspaces/{ws}/projects     │
  │   → 写 project                            │
  │   → 写 project_member                     │
  │                                           │
  │ 6. 分配项目配额                            │
  │ POST /api/v1/projects/{id}/quotas         │
  │   { poolId, specId, totalNodes: 3 }        │
  │   → 写 project_resource_quota             │
  │   → pool.allocated += 3                   │
  │                                           │
  │                                           │ 7. 部署推理服务
  │                                           │ POST /api/v1/projects/{id}/deployments
  │                                           │   { specName, replicas, image, ... }
  │                                           ↓
  │                                  校验项目成员
  │                                  加载 spec → spec.poolType
  │                                  找 WS 下同 poolType 池（已关联 spec）
  │                                  校验 project.used + 1 ≤ project.total
  │                                  预扣 project.used += 1
  │                                  生成 Deployment + Service YAML
  │                                  K8s: 提交到 ws.primaryClusterId / ws.namespace
  │                                  失败 → 回滚 project.used
  │                                           ↓
  │                                  返回 serviceUrl
```

## 2. 关键校验点

### 2.1 spec → pool 路由

```
spec.poolType      →  找 resource_pool WHERE workspace_id = ws.id AND pool_type = spec.poolType
                          校验该池在 resource_pool_spec 中已关联 spec.id
```

### 2.2 三层配额

```
L1  池容量:        pool.total_nodes        (管理员设定)
L1' 池已分配:      pool.allocated_nodes    (所有 project quota total 之和)
L2  项目配额:      prq.total_nodes         (管理员分配给该项目的)
L2' 项目已用:      prq.used_nodes          (部署时累加)

校验:  prq.used + 1 ≤ prq.total
       ∑ prq.total ≤ pool.allocated ≤ pool.total
```

### 2.3 副本数

`replicas` 默认为 1，每个副本占用一个租户规格节点。前端最大值取当前租户该规格的
剩余节点数，后端再次校验剩余配额，避免提交超过配额的 Deployment。

### 2.4 超分池（OVERSELL）

1.0 实现：
- 走 `project_resource_quota` 正常扣减
- **不调 K8s 提交**
- `status` 直接置为 `running`（不实际运行）
- 文档化"超分池 1.0 仅记账"

## 3. K8s 资源生成

`K8sResourceBuilder.buildVllmDeploymentAndService(...)` 输出 YAML：

```yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm-qwen3-svc
  namespace: ws-ai-a1b2c3d4
  labels:
    app: vllm
    spec: shared-hami-a100-1/4
spec:
  replicas: 1
  selector:
    matchLabels: { app: vllm, deployment: vllm-qwen3-svc }
  template:
    metadata:
      labels: { app: vllm, deployment: vllm-qwen3-svc, spec: shared-hami-a100-1/4 }
    spec:
      nodeSelector: { pool: shared-hami-a100-1/4 }
      tolerations: [{ key: nvidia.com/gpu, operator: Exists, effect: NoSchedule }]
      containers:
      - name: vllm
        image: vllm/vllm-openai:latest
        env: [{ name: VLLM_MODEL, value: /models }]
        resources:
          limits:
            nvidia.com/gpu: 1
            nvidia.com/gpumem: 20480
            nvidia.com/gpucores: 25
            cpu: 2
            memory: 8Gi
            platform.io/shared-hami-a100-1/4: 1     ← 关键
        readinessProbe: { httpGet: { path: /health, port: 8000 }, initialDelaySeconds: 60 }
---
apiVersion: v1
kind: Service
metadata: { name: vllm-qwen3-svc-svc, namespace: ws-ai-a1b2c3d4 }
spec:
  selector: { app: vllm, deployment: vllm-qwen3-svc }
  ports: [{ port: 8000, targetPort: 8000 }]
  type: ClusterIP
```

## 4. 失败回滚

| 阶段 | 失败处理 |
|---|---|
| K8s 提交 | `project_resource_quota.used -= 1`；`status="failed"` |
| 配额校验 | 抛 400，不扣减 |
| 超分池 | 不调 K8s，直接 `status="running"` |

## 5. 关键代码路径

| 步骤 | 类:方法 |
|---|---|
| 校验成员 | `ModelDeploymentService.ensureCanAccessProject` |
| 加载 spec | `ComputeSpecMapper.findByName` |
| 找匹配池 | `ModelDeploymentService.findMatchingPool` |
| 配额预扣 | `ProjectResourceQuotaMapper.updateUsedNodes` |
| 生成 YAML | `K8sResourceBuilder.buildVllmDeploymentAndService` |
| 提交 K8s | `KubernetesClientManager.createVllmDeploymentAndService` |
| 失败回滚 | `ProjectResourceQuotaMapper.updateUsedNodes` (减回去) |
