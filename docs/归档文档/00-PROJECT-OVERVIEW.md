# ACMP-Compute 项目与工程结构概览

> 本文基于 2026-07-24 仓库中的设计文档与实际代码整理，作为后续需求分析、架构改造和代码评审的共同基线。  
> 重点区分“当前后端已实现能力”“前端展示/Mock 能力”和“历史文档描述”，避免后续改造建立在过期认知上。

## 1. 项目定位

ACMP-Compute 是一个面向异构 GPU/AI 算力的管理平台。定位是一个位于业务用户与 Kubernetes 之间的语义化管理层：

```text
用户与权限
  → 物理 Kubernetes 集群
  → 工作空间（租户）
  → 独占 / 共享 / 超分三类资源池
  → 项目及项目配额
  → 模型与 vLLM 推理部署
  → Kubernetes Namespace、RBAC、Volcano Queue、Quota、Deployment、Service
```

平台负责管理租户、规格、配额和部署意图，再将其翻译为 Kubernetes 对象。Kubernetes 负责实际的容器编排；Volcano、HAMi 等组件承担队列或 GPU 切分能力。

当前版本更接近“可演示、可验证的 1.0 管理平台原型”，尚不是完整生产级算力运营系统。

## 2. 核心领域模型

### 2.1 主对象关系

```text
PhysicalCluster
└── Workspace
    ├── ResourcePool: EXCLUSIVE
    ├── ResourcePool: SHARED
    ├── ResourcePool: OVERSELL
    └── Project
        ├── ProjectMember
        ├── ProjectResourceQuota
        └── ModelDeployment

ComputeSpec ── ResourcePool / ProjectResourceQuota / ModelDeployment
PoolCard    ── ResourcePool / ComputeSpec / ModelDeployment
Model      ── 模型资产与存储路径
```

### 2.2 对象职责

| 对象 | 代码中的职责 |
|---|---|
| `PhysicalCluster` | 保存物理 K8s 集群信息及加密 kubeconfig；支持节点、GPU、容量和 HAMi 切分扫描 |
| `Workspace` | 租户边界；当前绑定一个主集群，并映射到一个 Namespace、ServiceAccount 和 Volcano Queue |
| `ResourcePool` | 工作空间私有的逻辑池；类型为独占、共享或超分；维护总容量和已分配容量 |
| `PoolCard` | 池内具体 GPU 卡/节点/规格及槽位信息，是资源池细化管理的一层 |
| `ComputeSpec` | 全局算力规格，描述 GPU 品牌、物理/虚拟/超分类型、CPU/内存/GPU 请求和调度约束 |
| `Project` | 工作空间内的子租户，也是部署归属和项目成员管理边界 |
| `ProjectResourceQuota` | 项目从某个池获得的某个规格配额，维护 `totalNodes` 与 `usedNodes` |
| `Model` | 模型广场中的模型元数据、来源、存储后端和存储路径 |
| `ModelDeployment` | 推理服务记录；连接项目、工作空间、资源池、规格、卡和实际 K8s Deployment/Service |

### 2.3 三类资源池

| 规格类型 | 资源池 | 当前语义 |
|---|---|---|
| `PHYSICAL` | `EXCLUSIVE` | 独占整卡，使用 NVIDIA GPU、海光 DCU 等原生资源键 |
| `VIRTUAL` | `SHARED` | HAMi vGPU 切分，使用显存和核心百分比等扩展资源 |
| `OVERSELL` | `OVERSELL` | 超分记账模型；文档明确说明 1.0 未完整落地真实 K8s 提交 |

项目配额是部署时真正扣减的对象。资源约束大致为：

```text
项目已使用量 <= 项目规格配额
项目配额汇总 <= 池已分配量 <= 池总容量
```

## 3. 后端工程结构

后端位于 `src/main`，是单体分层 Spring Boot 工程。

```text
src/main/java/com/acmp/compute/
├── AcmpComputeApplication.java
├── config/       Spring Security、MyBatis 类型处理
├── controller/   REST API
├── dto/          API 请求和响应模型
├── entity/       数据库实体及枚举
├── exception/    统一异常和业务异常
├── k8s/          K8s 客户端管理与资源构建
├── mapper/       MyBatis Mapper 接口
├── security/     JWT、用户主体、角色、AES 加密
├── service/      领域业务与编排逻辑
└── util/         NFS 模型路径等工具

src/main/resources/
├── application.yml
├── schema-h2.sql
├── data-h2.sql
├── mapper/       MyBatis XML
└── k8s-templates-example/
```

### 3.1 技术栈

| 层 | 实际代码 |
|---|---|
| 语言/运行时 | Java 11 |
| Web 框架 | Spring Boot 2.7.18、Spring MVC |
| 安全 | Spring Security、JWT、BCrypt |
| 持久层 | MyBatis 2.3.2 |
| 数据库 | H2 文件数据库 |
| Kubernetes | Kubernetes 官方 Java Client 20.0.1 |
| 构建 | Maven |

注意：根 README 写的是 Fabric8 6.13.0，但 `pom.xml` 和实际 imports 使用的是 Kubernetes 官方 Java Client。后续应以代码为准并修正文档。

### 3.2 主要调用链

```text
Controller
  → Service
    → MyBatis Mapper / XML
    → KubernetesClientManager
      → Kubernetes API
```

`KubernetesClientManager` 根据数据库中加密保存的 kubeconfig 创建并管理各物理集群的客户端。`EncryptionService` 使用 AES 保护 kubeconfig。`K8sResourceBuilder` 和客户端管理器负责创建 Namespace、RBAC、Quota、Deployment、Service 等资源。

### 3.3 后端 API 模块

当前 Controller 实际暴露的主要路由如下：

| 模块 | 路由前缀 |
|---|---|
| 认证 | `/api/v1/auth` |
| 物理集群与 GPU 扫描 | `/api/v1/clusters` |
| 算力规格 | `/api/v1/specs` |
| 工作空间及成员 | `/api/v1/workspaces` |
| 工作空间资源池 | `/api/v1/workspaces/{workspaceId}/pools`、`/api/v1/pools/{id}` |
| 池内卡管理 | `/api/v1/pools/{poolId}/cards` |
| 项目及成员 | `/api/v1/workspaces/{workspaceId}/projects`、`/api/v1/projects/{id}` |
| 项目配额 | `/api/v1/projects/{projectId}/quotas` |
| 模型广场 | `/api/v1/models` |
| 推理部署 | `/api/v1/projects/{projectId}/deployments` |

`docs/03-API-REFERENCE.md` 基本贴近当前 1.0 后端；`frontend/docs/API-REFERENCE.md` 中仍有 `/physical-clusters`、`/resource-pools` 等旧路径，不能作为当前联调依据。

## 4. 数据库与初始化方式

数据库结构集中在 `schema-h2.sql`，应用每次启动都会执行 schema 和 data 初始化：

- H2 文件位置：`./data/acmp`
- 默认组织：`Default Org`
- 默认管理员：`admin / admin123`
- 预置 7 个规格：A100、H100、海光 DCU、HAMi A100 切分和超分占位规格

当前 schema 使用 `CREATE TABLE IF NOT EXISTS`、`ALTER TABLE ... IF NOT EXISTS` 和 `MERGE` 兼顾重复启动，但没有 Flyway/Liquibase 版本化迁移。复杂改造前需要先建立数据库迁移机制，否则结构演进、回滚和多环境一致性风险较高。

## 5. 前端工程结构

前端位于 `frontend`，使用 React 单页应用：

| 层 | 技术 |
|---|---|
| 框架 | React 18、TypeScript |
| 构建 | Vite 6 |
| UI | Ant Design 5 |
| 路由 | React Router 6 |
| 请求 | Axios |
| 图表 | Recharts |

```text
frontend/src/
├── api/          后端 API 封装、Mock 切换
├── components/   布局与通用组件
├── contexts/     登录态、集群上下文
├── mock/         运营、实验室、大屏等演示数据
├── pages/        页面
├── App.tsx       路由入口
├── types.ts      类型
├── theme.ts      主题
└── styles.css    全局样式
```

### 5.1 页面域

实际路由已经超过 `frontend/README.md` 所描述的早期版本，可分为：

- 智算运营：总览、推理服务、项目、规格、资源池、模型广场、训练。
- 创新实验室：实验室总览、数字孪生、策略实验、负载洞察、数据治理。
- 监控预警：监控、告警、告警规则。
- 集群运维：集群、工作负载、存储。
- 独立算力大屏：`/screen`。

### 5.2 Mock 与真实后端边界

前端 `api/client.ts` 的默认逻辑是：

```text
localStorage 中 ACMP_USE_MOCK 不是 "false"
→ USE_MOCK = true
```

因此新浏览器默认使用 Mock。监控、告警、训练列表、存储等模块明确只有 Mock 数据；运营大屏和实验室也大量依赖 `frontend/src/mock`。这意味着“页面存在”不等于“后端能力已实现”。

后续改造应先给所有页面建立能力矩阵：

| 状态 | 含义 |
|---|---|
| Real | 已与当前后端 API 对接并可验证 |
| Partial | 部分真实、部分 Mock |
| Mock | 纯演示页面，暂无后端 |
| Stale | API 或数据模型已过期 |

## 6. 认证与权限

后端使用无状态 JWT：

- 登录接口公开；
- `/api/v1/**` 其余接口需要认证；
- 方法级权限通过 `@PreAuthorize` 控制；
- kubeconfig 使用 AES 加密后存入数据库；
- 默认 JWT 密钥和 AES 密钥可由环境变量覆盖。

当前需要特别注意：

- `application.yml` 带有可运行的默认 JWT/AES 密钥，只适合开发演示。
- CORS 当前允许所有来源。
- H2 Console 可访问且被 Security 放行。
- 文档和前端曾出现 `TRAINING_USER`，但核心 1.0 文档主要列出 `PLATFORM_ADMIN / ORG_ADMIN / INFERENCE_USER`，需要以后端 `Role` 枚举和业务权限检查为最终准绳，并统一角色模型。

## 7. 部署与本地验证

### 7.1 后端

```bash
mvn spring-boot:run
```

默认端口为 `8080`。根目录提供 Dockerfile，可构建单个后端容器。

### 7.2 前端

```bash
cd frontend
npm install
npm run dev
```

Vite 开发环境代理 API 到后端。生产构建产物为 `frontend/dist`，当前根 Dockerfile 不包含前端打包和统一托管。

### 7.3 Kubernetes 验证

`scripts` 目录提供 kind 集群、模拟节点标签、HAMi 注解、NVIDIA 插件以及成功/失败路径验证脚本。文档中的验证体系主要面向后端 1.0 核心链路。

## 8. 文档与代码的一致性结论

| 项目 | 结论 |
|---|---|
| 根 README / `docs/01~08` | 主要描述后端 1.0 核心模型，仍有局部技术栈过期 |
| `docs/08-HETEROGENEOUS-POOL.md` | 对池卡、异构资源和容量策略有扩展说明，部分已反映到 schema 和代码 |
| `frontend/README.md` | 明显落后于当前前端页面与路由 |
| `frontend/docs/API-REFERENCE.md` | 属于旧 API 契约，与当前 Controller 路径不一致 |
| 大屏、实验室、前端设计文档 | 更偏产品设计和演示目标，不代表已有后端实现 |
| 专利文档 | 描述潜在算法与创新方向，不应直接视为当前系统能力 |

## 9. 当前工程的主要改造风险

1. **文档、前端和后端版本错位**  
   改造前必须确认目标能力对应的是 Real、Mock 还是设计稿，避免只改前端或重复实现。

2. **数据库无版本迁移**  
   所有结构集中在一个 H2 schema；切换 MySQL/PostgreSQL 或升级模型前，应先引入迁移工具和环境化配置。

3. **自动化测试不足**  
   仓库未发现正式的 `src/test` 后端测试集或前端单元测试；现有验证主要是 shell 端到端脚本。

4. **跨数据库事务与 K8s 副作用的一致性**  
   创建/删除工作空间、调整配额、部署服务会同时修改数据库和 K8s。失败补偿、幂等、重试和状态对账是生产化改造重点。

5. **容量与配额模型仍偏简化**  
   `totalNodes/usedNodes/slots` 同时存在，GPU 卡、vGPU 切片、CPU/内存和副本消耗的统一计量边界需要进一步明确。

6. **单集群租户模型限制**  
   当前 `Workspace.primaryClusterId` 体现 1 工作空间绑定 1 集群。跨集群调度、容灾迁移或统一资源视图会触及核心模型。

7. **生产安全配置不足**  
   默认密钥、开放 CORS、H2 Console、默认管理员和 kubeconfig 生命周期管理都需要收紧。

8. **Mock 默认开启**  
   容易让页面联调结果产生误判；建议开发环境显式配置、页面显示数据源标识，生产构建禁止 Mock。

9. **后端单体内部职责较集中**  
   当前规模下单体合理，但 K8s 适配、资源计量、调度策略、部署编排和运营监控应形成清晰模块边界，不宜直接按微服务拆分。

## 10. 建议的后续改造顺序

### 第一阶段：建立可信基线

1. 明确本轮目标场景和目标用户。
2. 建立“页面—API—Service—表—K8s 对象”能力矩阵。
3. 统一 README、API 路由、角色和术语。
4. 固化一套可重复的核心链路测试。

### 第二阶段：补齐工程底座

1. 引入数据库迁移工具。
2. 区分 dev/test/prod 配置，移除生产默认密钥。
3. 为配额计算和部署编排增加单元/集成测试。
4. 增加 K8s 操作幂等、失败补偿和对账机制。

### 第三阶段：按目标扩展业务

根据实际优先级选择一条主线：

- 多集群与跨集群调度；
- 异构 GPU 统一规格、卡与切片管理；
- 配额、计量、计费与资源回收；
- 训练任务全生命周期；
- 推理服务弹性、监控与网关；
- 监控告警和大屏从 Mock 接入真实指标。

## 11. 后续修改时的阅读入口

| 修改类型 | 首要阅读位置 |
|---|---|
| 领域模型/表结构 | `schema-h2.sql`、`entity/`、`mapper/` |
| API 契约 | `controller/`、`dto/`，其次才是 API 文档 |
| 配额逻辑 | `ProjectQuotaService`、`ResourcePoolService`、对应 Mapper |
| 工作空间创建 | `WorkspaceService`、`KubernetesClientManager` |
| GPU 扫描与规格 | `GpuInventoryService`、`ComputeSpecService` |
| 推理部署 | `ModelDeploymentService`、`K8sResourceBuilder` |
| 权限 | `SecurityConfig`、`security/`、Controller 的 `@PreAuthorize` |
| 前端路由与页面 | `frontend/src/App.tsx`、`pages/` |
| 前后端联调 | `frontend/src/api/`、Controller 实际路由 |
| 演示数据 | `frontend/src/mock/`、`frontend/src/api/mock.ts` |

## 12. 总结

ACMP-Compute 已具备一个较完整的算力平台领域骨架：多物理集群接入、租户工作空间、三类资源池、异构规格、项目配额、GPU 卡槽、模型资产和推理部署，并能将核心对象落到 Kubernetes。

当前最大的结构性问题不是“缺少页面”，而是后端 1.0、扩展中的异构池模型、前端新产品形态和大量 Mock 能力并存。后续修改应先确定真实能力边界，再围绕一条业务主线演进，避免继续扩大文档、页面和实现之间的差距。
