# ACMP 异构算力管理平台 — 原理思维导图

> 演示场景：用一图讲清楚"这个平台到底是什么、靠什么工作、隔离链如何打通"。
> 所有图采用 Mermaid 语法，GitHub/VSCode/Typora/Obsidian 均可直接渲染。

---

## 1. 一句话定位

> **ACMP = K8s 原生对象的"语义化包装层"。**
> 平台不发明新调度器，而是把"用户、部门、规格、配额"翻译成 K8s 已经能听懂的对象。

---

## 2. 系统设计七模块

```mermaid
mindmap
  root((ACMP<br/>异构算力管理平台))
    模块一<br/>资源池管理
      逻辑资源池
        面向用户的配额单元
        定义池基本信息
        支持规格类型
        不创建 K8s 资源
      物理集群
        实际 K8s Cluster
        节点标签（GPU 类型）
        污点配置
        资源上限
      同构模式
        一个逻辑池
        绑定一个物理集群
      异构模式
        一个逻辑池
        绑定多个物理集群
        按 GPU 类型路由
    模块二<br/>部署服务
      ComputeSpec
        资源规格模板
        存储在 DB
        每副本资源配置
      用户只需指定
        GPU 类型
        GPU 数量
      平台自动推导
        显存大小
        算力比例
        CPU / 内存
      规格名称规则
        auto-{GPU类型}
        -{GPU数量}g
        -{CPU}c
        -{内存}g
    模块三<br/>调度器模式
      同构调度器
        直接定位唯一集群
        GPU 类型校验
      异构调度器
        按 GPU 类型路由
        遍历集群匹配标签
      统一接口
        pickCluster
        validateDeployment
    模块四<br/>部署预校验
      第一重
        GPU 类型校验
        是否在池支持范围
      第二重
        CPU 上限校验
        内存上限校验
      第三重
        L1 池级配额
        L2 工作空间配额
      错误前置
        校验失败
        提前拒绝
        不扣配额
    模块五<br/>配额管理
      L1 池级配额
        resource_pool_spec_quota
        total 总量
        allocated 已分配
      L2 工作空间配额
        workspace_pool_spec_quota
        max 最大
        used 已使用
      双层校验
        先扣 L1
        再扣 L2
      双层释放
        逐层回滚
    模块六<br/>K8s 资源构建
      ComputeSpec
        翻译为 K8s 资源
      生成内容
        Deployment
          limits
            nvidia.com/gpu
            nvidia.com/gpumem
            nvidia.com/gpucores
            cpu / memory
            platform.io/{spec}
        nodeSelector
        tolerations
        Service
      提交目标
        物理集群
        Namespace
    模块七<br/>节点管理
      节点扫描
        获取节点信息
        GPU 类型 / 卡数
        显存 / 算力
        CPU / 内存
        支持的切分规格
      自动同步
        填充资源上限
        maxCpuCores
        maxMemoryGib
      前端展示
        供用户选择
        资源池支持类型
```

---

## 3. 资源池两种模式

```mermaid
mindmap
  root((资源池))
    同构模式 HOMOGENEOUS
      一个逻辑池
      绑定一个物理集群
      直接定位
      适合简单场景
    异构模式 HETEROGENEOUS
      一个逻辑池
      绑定多个物理集群
      按 GPU 类型路由
      适合混合算力场景
```

---

## 4. 部署流程总览

```mermaid
flowchart LR
    A[用户提交部署请求] --> B[部署预校验]
    B --> C[GPU 类型校验]
    B --> D[资源上限校验]
    B --> E[配额校验]
    C --> F{通过?}
    D --> F
    E --> F
    F --否--> X[拒绝请求]
    F --是--> G[ComputeSpec 自动生成]
    G --> H[配额预扣]
    H --> I[调度器选集群]
    I --> J[同构直接返回]
    I --> K[异构按类型路由]
    J --> L[K8s 资源构建]
    K --> L
    L --> M[提交 Deployment + Service]

    style X fill:#f44336,color:#fff
    style M fill:#4caf50,color:#fff
```

---

## 5. 用户视角：极简输入

```mermaid
flowchart TB
    USER[用户只需填写<br/>GPU 类型 = A100-80GB（1/4 切分）<br/>GPU 数量 = 2] --> PLATFORM

    subgraph PLATFORM[平台自动处理]
        A[查切分规格表<br/>1/4 A100 → 20GB / 25%]
        B[生成规格名称<br/>auto-nvidia-a100-80g-1/4-2g-4c-16g]
        C[查 DB<br/>有则复用 无则新建]
        D[生成 K8s 资源清单<br/>GPU 2卡 + 显存 40GB<br/>+ 算力 50% + CPU 4核 + 内存 16Gi]
    end

    PLATFORM --> K8S[K8s Deployment]

    style USER fill:#e3f2fd
    style K8S fill:#e8f5e9
```

---

## 6. 三层资源模型

```mermaid
flowchart TB
    subgraph PHY["① 物理集群 (K8s Cluster)"]
        direction LR
        N1["node-nvidia<br/>labels: pool=nvidia-a100-80g-1/4<br/>maxCpu / maxMem"]
        N2["node-dcu<br/>labels: pool=hygon-dcu-32g-1/4"]
    end

    subgraph SPEC["② 算力规格 (ComputeSpec)"]
        S1["nvidia-a100-80g-1/4<br/>gpumemMb: 20480<br/>gpucores: 25"]
        S2["hygon-dcu-32g-1/4<br/>gpumemMb: 8192<br/>gpucores: 25"]
    end

    subgraph POOL["③ 逻辑资源池"]
        P1["算法部资源池<br/>poolMode: HETEROGENEOUS<br/>关联: 两个物理集群"]
    end

    subgraph WS["④ 工作空间 (K8s Namespace)"]
        W1["llm-training<br/>ResourceQuota<br/>platform.io/nvidia-a100-80g-1/4 = N"]
    end

    PHY -.cluster 关联.-> POOL
    SPEC --被引用--> POOL
    POOL --切配合额--> WS
    SPEC --驱动构建--> TASK
    WS --提供 namespace--> TASK

    TASK["Pod Deployment<br/>limits 含 platform.io/*<br/>nodeSelector 匹配节点"]
```

---

## 7. 双层配额体系

```mermaid
flowchart LR
    subgraph L1["L1 池级配额"]
        Q1["resource_pool_spec_quota"]
        Q1 --> T1["total 总量"]
        Q1 --> A1["allocated 已分配"]
    end

    subgraph L2["L2 工作空间配额"]
        Q2["workspace_pool_spec_quota"]
        Q2 --> M2["max 最大"]
        Q2 --> U2["used 已使用"]
    end

    subgraph K8S["K8s 层兜底"]
        RQ["ResourceQuota.hard<br/>platform.io/{spec}<br/>按 used 计数"]
    end

    L1 --> L2
    L2 --> K8S
```

---

## 8. 隔离链关键 — ResourceQuota 真生效

```mermaid
flowchart TB
    SPEC["ComputeSpec<br/>name = nvidia-a100-80g-1/4<br/>resourceQuotaKey = platform.io/nvidia-a100-80g-1/4"]

    subgraph WSC["工作空间创建时"]
        RQ["ResourceQuota.hard<br/>platform.io/nvidia-a100-80g-1/4 = N"]
    end

    subgraph POD["Pod 创建时"]
        POD_LIMITS["Container.resources.limits<br/>nvidia.com/gpu = 1<br/>platform.io/nvidia-a100-80g-1/4 = 1  ← 关键"]
    end

    subgraph K8S["K8s 内置控制器"]
        QC["ResourceQuota Admission<br/>拦截 Pod limits 含 platform.io/*<br/>累加到 ResourceQuota.used"]
    end

    SPEC --写入 hard 上限--> RQ
    SPEC --写入 limits 计量--> POD_LIMITS
    POD_LIMITS --被 K8s 拦截--> QC
    QC --used 达到 hard--> BLOCK["第 N+1 个 Pod 被 K8s 拒绝"]

    style SPEC fill:#fff3e0
    style POD_LIMITS fill:#ffeb3b
    style BLOCK fill:#f44336,color:#fff
```

---

## 9. 部署时序

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant API as ACMP API
    participant DB as DB
    participant K as K8s

    U->>API: POST /resource-pools/{poolId}/deploy<br/>{gpuType, gpuCount, cpuCores, memoryGib}
    rect rgb(255, 243, 224)
    Note over API,DB: 预校验阶段
    API->>API: validateDeployment(gpuType, cpuCores, memoryGib)
    end
    API->>DB: ensureComputeSpec(request)
    DB-->>API: ComputeSpec (auto-xxx)

    rect rgb(255, 243, 224)
    Note over API,DB: L1 + L2 配额校验
    API->>DB: quotaService.validateBothLevelQuotas()
    API->>DB: quotaService.deductBothLevelQuotas()
    end

    API->>DB: pickCluster(poolId, spec)
    DB-->>API: PhysicalCluster

    Note over API: K8sResourceBuilder.buildDeployment(spec)
    API->>K: client.apply(Deployment + Service)

    alt K8s 成功
        K-->>API: 201 Created
        API-->>U: 成功
    else K8s 失败
        K-->>API: 失败
        API->>DB: 回滚 L1 + L2 配额
        API-->>U: 错误信息
    end
```

---

## 10. 数据模型

```mermaid
erDiagram
    physical_cluster ||--o{ resource_pool_physical_cluster : "M to N"
    resource_pool    ||--o{ resource_pool_physical_cluster : "M to N"
    resource_pool    ||--o{ resource_pool_spec_quota       : "按规格 L1"
    compute_spec     ||--o{ resource_pool_spec_quota       : "被引用"
    resource_pool    ||--o{ workspace                      : "1 to N"
    workspace        ||--|| workspace_resource_pool        : "绑定"
    workspace        ||--o{ workspace_pool_spec_quota      : "L2 配额"
    compute_spec     ||--o{ workspace_pool_spec_quota      : "被引用"
    workspace        ||--o{ model_deployment               : "部署"
```

---

## 11. 演示讲稿建议（3 分钟串词）

> 1. **定位**（30 秒）：平台是 K8s 原生对象的语义化包装层，不发明新调度器，把"用户、规格、配额"翻译成 K8s 能听懂的对象。
> 2. **结构**（60 秒）：七模块设计 — 资源池管理（同构/异构）/ 部署服务（ComputeSpec 自动生成）/ 调度器模式 / 部署预校验 / 配额管理 / K8s 资源构建 / 节点管理。
> 3. **核心简化**（60 秒）：用户只告诉平台"要几张什么类型的卡"，平台自动查切分规格表算显存算力，生成 ComputeSpec，提交 K8s。
> 4. **隔离机制**（30 秒）：ResourceQuota 用 `platform.io/{spec}` 计量，Pod limits 必须带这个字段，K8s 内置 Admission 接管限流，超额由 API Server 拒绝。
> 5. **价值结句**：跨厂商、跨集群、跨部门，统一一套 API，统一一套配额。

---

## 12. 一图记住整个系统

```
                  ┌──────────────────────────────────────────────┐
                  │       ComputeSpec  (平台翻译中枢)             │
                  │                                              │
                  │  gpuBrand     → nvidia.com/gpu | amd.com/dcu │
                  │  gpumemMb     → nvidia.com/gpumem            │
                  │  gpucores     → nvidia.com/gpucores           │
                  │  nodeSelector → Pod.nodeSelector             │
                  │  tolerations  → Pod.tolerations               │
                  │  quotaKey     → platform.io/{name}            │
                  └─────────────────────┬────────────────────────┘
                                        │ 翻译
        ┌───────────────────────────────┼───────────────────────────────┐
        │                               │                               │
        ▼                               ▼                               ▼
  ┌──────────┐                  ┌──────────────┐                ┌──────────────┐
  │ 物理池   │ M:N              │ 逻辑池       │ 1:N            │ 工作空间      │
  │ Cluster  │ ◄─────────────── │ DB 聚合      │ ──────────────► │ Namespace    │
  │ labels   │                  │ poolMode     │                │ ResourceQuota │
  │ taints   │                  │ 同构/异构    │                │ platform.io/* │
  │ maxCpu   │                  │              │                │               │
  │ maxMem   │                  │              │                │               │
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