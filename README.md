# ACMP 异构算力统一管理平台

ACMP 是一个面向内网 Kubernetes GPU 集群的轻量算力管理平台。它位于管理员和 Kubernetes 之间，把集群、GPU、资源池、算力规格、租户配额、项目和推理服务组织成一条可理解、可操作的管理链路。

平台不替代 Kubernetes、HAMi、Prometheus 或 GPU 驱动。它负责表达管理意图、维护业务关系，并通过 Kubernetes API 将资源选择、调度约束和推理服务部署落实到集群。

## 平台要解决的问题

- 多个 Kubernetes 集群和 GPU 节点缺少统一视图。
- 物理 GPU、共享 GPU 和不同品牌设备难以用一致方式管理。
- 算力资源与租户、项目、推理服务之间缺少清晰关系。
- 推理服务的镜像、模型目录、算力规格和 Kubernetes 部署链路相互割裂。
- 内网环境中的监控组件、镜像和应用发布缺少可重复的离线方案。

## 当前实现效果

当前版本已经形成一条完整的 MVP 主流程：

```text
注册 Kubernetes 集群
  → 同步真实 Node 与 GPU 信息
  → 按 Node 将全部 GPU 加入独享池或共享池
  → 生成或复用算力规格
  → 给租户分配规格配额
  → 在项目中登记模型并部署推理服务
  → 通过 Node 标签和设备资源符提交 Kubernetes
  → 使用 Prometheus、Node Exporter、DCGM 和 vLLM Metrics 观察运行状态
```

主要能力包括：

- 注册和同步 Kubernetes 集群、Node 与 GPU 设备。
- 支持 NVIDIA 等多品牌 GPU 的统一识别与展示。
- 以 Node 为管理单位加入独享池或共享池，避免逐卡维护。
- 共享池通过 `hami-system` 中的 HAMi 配置完成节点级切分。
- 依据 GPU 品牌、型号、资源池类型和切分比例形成算力规格。
- 支持租户、项目、规格配额、模型登记和推理服务部署。
- 自动生成 Deployment、Service、资源请求和 Node 调度标签。
- 提供集群、节点、GPU 和推理服务监控页面。
- 提供 Windows 开发脚本、Linux 发布脚本和监控组件离线安装脚本。

## 设计边界

当前平台定位是单管理员操作的完整 MVP，重点是主流程正确、概念清晰、便于内网部署和调试。当前阶段不追求复杂审批、高并发调度、跨系统分布式事务或自动执行生产级策略。

Kubernetes 是运行状态和调度结果的事实来源；ACMP 数据库保存平台管理关系、配额和部署记录。GPU 切分由 HAMi 执行，指标采集由 Prometheus 体系执行。

## 推荐阅读

| 文档 | 内容 |
|---|---|
| [文档导航](docs/README.md) | 当前主文档、专项资料和历史资料的阅读入口 |
| [运行环境与集群前置条件](docs/01-RUNTIME-PREREQUISITES.md) | Kubernetes、containerd、HAMi 和监控组件要求 |
| [工程结构与运行部署](docs/02-ENGINEERING-GUIDE.md) | 代码目录、开发启动、打包和离线部署 |
| [算力资源管理](docs/03-COMPUTE-RESOURCE-MANAGEMENT.md) | 集群、Node、GPU、资源池和算力规格 |
| [业务管理](docs/04-BUSINESS-MANAGEMENT.md) | 租户、项目、模型和推理服务 |
| [监控运维](docs/05-MONITORING-OPERATIONS.md) | 集群、节点、GPU、推理服务和告警 |
| [架构迭代经历](docs/06-ARCHITECTURE-EVOLUTION.md) | 从复杂设想到可落地 MVP 的取舍过程 |
| [后续扩展方向](docs/07-EXTENSIBILITY-ROADMAP.md) | 创新实验室、数字孪生和策略仿真 |

## 开发环境快速启动

运行前需要 Java 11、Maven、Node.js 和 npm。

Windows 下在工程根目录执行：

```powershell
.\scripts\dev-start.ps1
```

脚本默认重新构建并启动前后端，也可以单独启动：

```powershell
.\scripts\dev-start.ps1 -Target Backend
.\scripts\dev-start.ps1 -Target Frontend
```

默认访问地址：

- 前端：`http://127.0.0.1:3000/`
- 后端：`http://127.0.0.1:8080/`
- 健康检查：`http://127.0.0.1:8080/actuator/health`

默认开发管理员为 `admin / admin123`。

## 技术栈

| 层次 | 当前实现 |
|---|---|
| 后端 | Java 11、Spring Boot 2.7.18、MyBatis 2.3.2 |
| Kubernetes 接入 | Kubernetes Java Client 22.0.1 |
| 数据库 | H2 文件数据库 |
| 前端 | React 18、TypeScript、Vite、Ant Design、ECharts |
| 认证 | Spring Security、JWT |
| 监控 | Prometheus、Node Exporter、kube-state-metrics、DCGM Exporter、vLLM Metrics |

## 代码入口

- 后端业务：`src/main/java/com/acmp/compute/service`
- Kubernetes 适配：`src/main/java/com/acmp/compute/k8s`
- REST 接口：`src/main/java/com/acmp/compute/controller`
- 数据访问：`src/main/java/com/acmp/compute/mapper`、`src/main/resources/mapper`
- 前端页面：`frontend/src/pages/real`
- 前端接口封装：`frontend/src/api/real.ts`
- 工程脚本：`scripts`

更完整的结构、运行方式和发布建议见 [工程结构与运行部署](docs/02-ENGINEERING-GUIDE.md)。
