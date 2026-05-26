# ACMP 异构算力管理平台 — 原理思维导图

> 演示场景：用一图讲清楚"这个平台到底是什么、靠什么工作、隔离链如何打通"。
> 所有图采用 Mermaid 语法，GitHub/VSCode/Typora/Obsidian 均可直接渲染。

---

## 1. 一句话定位

> **ACMP = K8s 原生对象（Namespace / ResourceQuota / Node 标签污点 / RBAC / VolcanoQueue）的"语义化包装层"。**
> 平台不发明新调度器，而是把"用户、部门、规格、配额"翻译成 K8s 已经能听懂的对象。

---

## 2. 主思维导图（系统全景）

```mermaid
mindmap
  root((ACMP<br/>异构算力管理平台))
    设计哲学
      物理属性归物理池
      标准定义归规格
      逻辑池只存关联关系
      平台层代理 K8s 层 SA
      隔离收敛到 Namespace + ResourceQuota
    三层资源模型
      物理集群<br/>K8s Cluster
        Node + label + taint
        kubeconfig AES 加密存储
        总容量实时从 Node.allocatable 上报
      逻辑资源池<br/>纯 DB 聚合容器
        M to N 关联物理集群
        按规格 total quota 分配
        不创建任何 K8s 资源
      工作空间<br/>K8s Namespace
        1 to 1 Namespace
        ResourceQuota 按 platform.io 限流
        SA + Role + RoleBinding
        Volcano Queue
    算力规格 ComputeSpec
      gpuBrand
        NVIDIA → nvidia.com/gpu
        HYGON → amd.com/dcu
        HUAWEI_ASCEND → huawei.com/ascend910
      nodeSelector
        翻译为 Pod.nodeSelector
        用来选物理集群
      tolerations
        翻译为 Pod.tolerations
        穿透物理池污点
      resourceQuotaKey
        platform.io/{specName}
        让 ResourceQuota 真生效
    双层配额体系
      L1 池级
        resource_pool_spec_quota
        total / allocated
        防止部门超额
      L2 工作空间级
        workspace_pool_spec_quota
        max / used
        防止项目超额
      K8s 层兜底
        ResourceQuota.hard
        按 platform.io/spec 限 used
    权限链
      JWT 平台层认证
        UserPrincipal
        id role poolIds
      workspace_member 平台层授权
        校验 user 属于 workspace
      K8s SA 层鉴权
        1 ws 1 SA
        平台代理调用
        不建 per-user SA
    异构硬件抽象
      NVIDIA GPU
        device plugin
        HAMi vGPU
      Hygon DCU
        amd.com/dcu
      华为昇腾
        huawei.com/ascend910
    回滚保护
      部署失败 双层配额回退
      删除部署 释放 L2 used
      删除工作空间 释放 L1 allocated 并删 ns
```

---

## 3. 三层资源模型对照表

```mermaid
flowchart TB
    subgraph PHY["① 物理资源池 (K8s Cluster)"]
        direction LR
        N1["node-nvidia<br/>labels: pool=nvidia-gpu<br/>taints: nvidia.com/gpu=present"]
        N2["node-dcu<br/>labels: pool=hygon-dcu<br/>taints: amd.com/dcu=present"]
    end

    subgraph SPEC["② 算力规格 (ComputeSpec)"]
        S1["nvidia-rtx4090-24g<br/>nodeSelector pool=nvidia-gpu<br/>quotaKey platform.io/nvidia-rtx4090-24g"]
        S2["hygon-dcu-32g<br/>nodeSelector pool=hygon-dcu<br/>quotaKey platform.io/hygon-dcu-32g"]
    end

    subgraph POOL["③ 逻辑资源池 (DB)"]
        P1["算法部资源池<br/>关联: 两个物理集群<br/>spec_quota<br/>nvidia-rtx4090-24g total 1<br/>hygon-dcu-32g     total 1"]
    end

    subgraph WS["④ 工作空间 (K8s Namespace)"]
        W1["llm-training<br/>ResourceQuota<br/>platform.io/nvidia-rtx4090-24g = 1"]
        W2["cv-training<br/>ResourceQuota<br/>platform.io/hygon-dcu-32g = 1"]
    end

    subgraph TASK["⑤ Pod (用户任务)"]
        T1["vllm-qwen3-svc<br/>limits<br/>nvidia.com/gpu 1<br/>platform.io/nvidia-rtx4090-24g 1<br/>nodeSelector pool=nvidia-gpu<br/>tolerations nvidia.com/gpu"]
    end

    PHY -.cluster 关联.-> POOL
    SPEC -.翻译模板.-> POOL
    POOL --切分配额--> WS
    SPEC --驱动构建--> TASK
    WS --提供 namespace--> TASK
    T1 --调度命中--> N1
```

---

## 4. 资源量流转链路（9 步）

```mermaid
flowchart LR
    A[1 物理机上架<br/>kubectl label/taint node] --> B[2 注册物理集群<br/>POST /admin/physical-clusters]
    B --> C[3 定义算力规格<br/>POST /specs]
    C --> D[4 创建逻辑池<br/>POST /admin/resource-pools<br/>+ spec_quota.total]
    D --> E[5 创建工作空间<br/>POST /workspaces<br/>L1 校验 → 选集群 → K8s ns + ResourceQuota]
    E --> F[6 提交任务<br/>POST /model-deployments<br/>L1+L2 校验 → 预扣 → 注入 spec → K8s Deployment]
    F --> G[7 K8s Scheduler<br/>nodeSelector + taint 匹配节点]
    G --> H[8 Kubelet + Cgroup<br/>容器隔离 GPU 卡限制<br/>ResourceQuota.used += 1]
    H --> I[9 删除/失败<br/>双层配额回滚<br/>ResourceQuota.used -= 1]

    style A fill:#e3f2fd
    style E fill:#fff3e0
    style F fill:#fff3e0
    style I fill:#ffebee
```

---

## 5. 隔离链的关键 — "为什么 ResourceQuota 能真生效"

```mermaid
flowchart TB
    SPEC["ComputeSpec<br/>name = nvidia-rtx4090-24g<br/>resourceQuotaKey = platform.io/nvidia-rtx4090-24g"]

    subgraph WSC["工作空间创建时"]
        RQ["ResourceQuota.hard<br/>platform.io/nvidia-rtx4090-24g = 1"]
    end

    subgraph POD["Pod 创建时"]
        POD_LIMITS["Container.resources.limits<br/>nvidia.com/gpu = 1<br/>platform.io/nvidia-rtx4090-24g = 1  ← 这条是关键"]
    end

    subgraph K8S["K8s 内置控制器"]
        QC["ResourceQuota Admission<br/>看到 Pod limits 含 platform.io/* 字段<br/>累加到 ResourceQuota.used"]
    end

    SPEC --写入 hard 上限--> RQ
    SPEC --写入 limits 计量--> POD_LIMITS
    POD_LIMITS --被 K8s 拦截--> QC
    QC --used 达到 hard--> BLOCK["第 N+1 个 Pod 被 K8s 拒绝"]

    style SPEC fill:#fff3e0
    style POD_LIMITS fill:#ffeb3b
    style BLOCK fill:#f44336,color:#fff
```

> **关键洞察**：旧实现只给 ResourceQuota 设了 `platform.io/{spec}=1`，但没给 Pod 加这个字段 → K8s 永远计 0 → 隔离失效。修复后 Pod limits 必须**同时**带 `platform.io/{spec}=1`，ResourceQuota 才会累加 used，超额时由 K8s **API Server 拒绝**新 Pod。

---

## 6. 双层配额工作时序（以部署为例）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户 (zhangsan)
    participant API as ACMP API
    participant DB as DB
    participant K as K8s

    U->>API: POST /resource-pools/{p}/workspaces/{ws}/model-deployments<br/>{specName, replicas=1}
    API->>DB: workspace_member 校验 zhangsan ∈ ws ?
    DB-->>API: ✅
    API->>DB: 读 compute_spec by name
    DB-->>API: spec(brand, nodeSelector, tolerations, quotaKey)

    rect rgb(255, 243, 224)
    Note over API,DB: ① L1 池级配额校验
    API->>DB: resource_pool_spec_quota.allocated + 1 ≤ total ?
    DB-->>API: ✅
    Note over API,DB: ② L2 工作空间级配额校验
    API->>DB: workspace_pool_spec_quota.used + 1 ≤ max ?
    DB-->>API: ✅
    end

    rect rgb(232, 245, 233)
    Note over API,DB: ③ 双层预扣
    API->>DB: L1.allocated += 1
    API->>DB: L2.used += 1
    end

    Note over API: 构建 YAML<br/>limits: nvidia.com/gpu=1, platform.io/{spec}=1<br/>nodeSelector: pool=nvidia-gpu<br/>tolerations: nvidia.com/gpu=present
    API->>K: client.apply(Deployment + Service)

    alt K8s 成功
        K-->>API: 201 Created
        API->>DB: deployment.status = running
        API-->>U: 201 + serviceUrl
    else K8s 失败
        K-->>API: 拒绝（如 ResourceQuota 超额）
        rect rgb(255, 235, 238)
        API->>DB: L1.allocated -= 1
        API->>DB: L2.used -= 1
        end
        API->>DB: deployment.status = failed
        API-->>U: 500 + 失败原因
    end
```

---

## 7. 数据模型（精简 ER）

```mermaid
erDiagram
    physical_cluster ||--o{ resource_pool_physical_cluster : "M to N"
    resource_pool    ||--o{ resource_pool_physical_cluster : "M to N"
    resource_pool    ||--o{ resource_pool_spec_quota       : "按规格"
    compute_spec     ||--o{ resource_pool_spec_quota       : "被引用"
    resource_pool    ||--o{ workspace                      : "1 to N"
    workspace        ||--|| workspace_resource_pool        : "绑定"
    workspace        ||--o{ workspace_pool_spec_quota      : "L2 配额"
    compute_spec     ||--o{ workspace_pool_spec_quota      : "被引用"
    workspace        ||--o{ workspace_member               : "成员"
    users            ||--o{ workspace_member               : "属于多 ws"
    workspace        ||--o{ model_deployment               : "部署"
    workspace        ||--o{ training_job_record            : "训练"
    compute_spec     ||--o{ model_deployment               : "记录使用规格"
    compute_spec     ||--o{ training_job_record            : "记录使用规格"

    physical_cluster {
        string id PK
        string name
        clob   kubeconfig_encrypted
        string node_labels "JSON 节点标签"
        string taints       "JSON 污点"
    }
    compute_spec {
        string id PK
        string name "nvidia-rtx4090-24g"
        string gpu_brand "NVIDIA/HYGON/HUAWEI_ASCEND"
        string node_selector
        string tolerations
        string resource_quota_key "platform.io/{name}"
    }
    resource_pool {
        string id PK
        string department_code
        string status
    }
    resource_pool_spec_quota {
        string resource_pool_id PK
        string spec_id PK
        int    total_quota
        int    allocated_quota "L1"
    }
    workspace {
        string id PK
        string resource_pool_id FK
        string namespace UK "K8s NS"
        string service_account_name
        string primary_cluster_id
    }
    workspace_pool_spec_quota {
        string workspace_id PK
        string resource_pool_id PK
        string spec_id PK
        int    max_quota
        int    used_quota "L2"
    }
```

---

## 8. 权限链（三道关卡）

```mermaid
flowchart TB
    REQ([用户 zhangsan 携 JWT 请求<br/>POST /workspaces/ws-llm/model-deployments])

    subgraph G1["关卡 ① 平台认证（JwtAuthenticationFilter）"]
        J["解 JWT → UserPrincipal<br/>id=zhangsan role=TRAINING_USER"]
    end

    subgraph G2["关卡 ② 平台授权（Service 内）"]
        M["workspace_member 表查询<br/>zhangsan ∈ ws-llm ?"]
    end

    subgraph G3["关卡 ③ K8s 鉴权（K8s API Server）"]
        SA["KubernetesClient 用平台 SA 发请求<br/>K8s 检查 SA 在 ns-llm 的 Role 权限"]
        RQ["ResourceQuota Admission<br/>platform.io/{spec} 计量是否超 hard ?"]
    end

    REQ --> J
    J --token 有效--> M
    M --是成员--> SA
    SA --RBAC 通过--> RQ
    RQ --used 未超--> OK([创建 Deployment])

    J -.token 无效.-> X1([401])
    M -.非成员.-> X2([403])
    SA -.RBAC 拒.-> X3([403 from K8s])
    RQ -.超额.-> X4([Forbidden by K8s ResourceQuota])

    style OK fill:#4caf50,color:#fff
    style X1 fill:#f44336,color:#fff
    style X2 fill:#f44336,color:#fff
    style X3 fill:#f44336,color:#fff
    style X4 fill:#f44336,color:#fff
```

---

## 9. 异构硬件抽象（一图看懂"为啥能跨厂商"）

```mermaid
flowchart LR
    USER[用户提交 specName=hygon-dcu-32g]

    subgraph TRANS["平台翻译层 (K8sResourceBuilder)"]
        BR{spec.gpuBrand?}
        K1["limits.nvidia.com/gpu = N"]
        K2["limits.amd.com/dcu = N"]
        K3["limits.huawei.com/ascend910 = N"]
        PKEY["+ limits.platform.io/hygon-dcu-32g = 1"]
        NS["+ nodeSelector pool=hygon-dcu"]
        TOL["+ tolerations amd.com/dcu=present"]
    end

    subgraph K["K8s 集群"]
        ND[node-dcu 上的 amd.com/dcu device plugin]
        SCH[Scheduler 仅匹配满足标签+容忍的节点]
    end

    USER --> BR
    BR --NVIDIA--> K1
    BR --HYGON--> K2
    BR --HUAWEI_ASCEND--> K3
    K2 --> PKEY --> NS --> TOL --> SCH
    SCH --命中--> ND

    style BR fill:#fff3e0
    style PKEY fill:#ffeb3b
```

---

## 10. 演示讲稿建议（3 分钟串词）

> 1. **痛点**（30 秒）：企业有 NVIDIA、海光、昇腾混合卡，多个部门混着用，怎么"既隔离又共享"？
> 2. **结构**（60 秒）：用三层资源模型 — 物理池（K8s Cluster）/逻辑池（DB 聚合）/工作空间（K8s NS）。物理池存"硬件事实"，逻辑池存"分配契约"，工作空间是"团队的隔离边界"。
> 3. **关键发明**（60 秒）：算力规格（ComputeSpec）作为唯一翻译层 —— **任何用户请求只指定 specName，平台自动翻译成 K8s 标准对象**（设备资源键、节点选择器、容忍、计量键）。一处定义、处处对齐。
> 4. **隔离链怎么真生效**（30 秒）：ResourceQuota 用 `platform.io/{spec}` 而不是 `nvidia.com/gpu`，Pod limits 同样带这个键 —— **K8s 内置 ResourceQuota Admission 接管限流**，超额由 API Server 拒绝，平台无需自己实现"调度器"。
> 5. **价值结句**：跨厂商、跨集群、跨部门，统一一套 API，统一一套配额，统一一套权限。

---

## 11. 一图记住整个系统

```
                  ┌──────────────────────────────────────────────┐
                  │       ComputeSpec  (唯一翻译中枢)             │
                  │                                              │
                  │  gpuBrand     → nvidia.com/gpu | amd.com/dcu │
                  │  nodeSelector → Pod.nodeSelector             │
                  │  tolerations  → Pod.tolerations              │
                  │  quotaKey     → platform.io/{name}           │
                  └─────────────────────┬────────────────────────┘
                                        │ 翻译
        ┌───────────────────────────────┼───────────────────────────────┐
        │                               │                               │
        ▼                               ▼                               ▼
  ┌──────────┐                  ┌──────────────┐                ┌──────────────┐
  │ 物理池   │ M:N              │ 逻辑池       │ 1:N            │ 工作空间      │
  │ Cluster  │ ◄─────────────── │ DB 聚合      │ ──────────────► │ Namespace    │
  │ labels   │                  │ spec_quota   │                │ ResourceQuota │
  │ taints   │                  │ total /      │                │ platform.io/* │
  │          │                  │ allocated L1 │                │ used / hard   │
  └──────────┘                  └──────────────┘                └──────┬───────┘
                                                                       │
                                                                       ▼
                                                                ┌─────────────┐
                                                                │ Pod 任务     │
                                                                │ limits 含    │
                                                                │ platform.io  │
                                                                │ + nodeSelect │
                                                                │ + tolerate   │
                                                                └─────────────┘
```

---

### 渲染建议

- 在 VSCode 中安装 *Markdown Preview Mermaid Support*
- 演示时可用 [mermaid.live](https://mermaid.live) 把单图复制进去全屏
- 导出 PNG：`mmdc -i MINDMAP.md -o mindmap.png`（需要 `@mermaid-js/mermaid-cli`）
