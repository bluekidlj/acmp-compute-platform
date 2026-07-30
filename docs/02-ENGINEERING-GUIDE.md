# 工程结构与运行部署

本文面向第一次接触代码的开发者，说明工程边界、主要目录、开发启动和 Linux 发布方式。具体业务规则由后续专题文档说明。

## 1. 工程形态

ACMP 当前采用前后端分离的单体架构：

```text
浏览器
  → React 前端
  → Spring Boot REST API
  → H2 / Kubernetes API / Prometheus API
```

单体形态适合当前单管理员 MVP：部署简单、日志集中、联调路径短。Kubernetes、HAMi 和 Prometheus 通过适配层接入，不嵌入平台进程。

## 2. 目录结构

```text
acmp-compute-platform/
├── src/main/java/com/acmp/compute/
│   ├── controller/      REST 接口入口
│   ├── service/         业务流程编排
│   ├── k8s/             Kubernetes 访问与资源构建
│   ├── monitoring/      Prometheus 客户端
│   ├── mapper/          MyBatis Mapper 接口
│   ├── entity/          持久化对象
│   ├── dto/             前后端协议对象
│   ├── security/        JWT 与认证
│   └── exception/       统一异常处理
├── src/main/resources/
│   ├── mapper/          MyBatis SQL
│   ├── application.yml  应用配置
│   ├── logback-spring.xml
│   ├── schema-h2.sql
│   └── data-h2.sql
├── frontend/src/
│   ├── pages/real/      当前真实业务页面
│   ├── api/             API 调用
│   ├── components/      布局与通用组件
│   └── App.tsx          路由入口
├── scripts/
│   ├── linux-k8s/       测试 Kubernetes 集群脚本
│   ├── monitoring-offline/ 监控组件离线包
│   ├── linux-release/   Linux 应用发布
│   └── *.ps1            Windows 开发和打包
└── docs/                工程知识与专项资料
```

判断当前接口和行为时，优先查看 Controller、Service 和 Kubernetes 适配代码；历史设计稿只用于理解决策背景。

## 3. 后端结构

后端使用 Java 11、Spring Boot 2.7.18、MyBatis 和 Kubernetes Java Client。

主要调用关系为：

```text
Controller
  → Service
    → Mapper / H2
    → KubernetesClientManager
    → PrometheusClient
```

常用代码入口：

| 关注内容 | 代码位置 |
|---|---|
| 集群注册与同步 | `PhysicalClusterController`、`PhysicalClusterService`、`ClusterInventoryService` |
| 资源池和入池 | `ResourcePoolController`、`ResourcePoolService` |
| 算力规格 | `ComputeSpecController`、`ComputeSpecService` |
| 租户与配额 | `TenantController`、`TenantService`、`TenantSpecQuotaService` |
| 项目 | `ProjectController`、`ProjectService` |
| 模型与推理服务 | `ModelController`、`ModelDeploymentController`、`ModelDeploymentService` |
| Kubernetes 操作 | `KubernetesClientManager`、`K8sResourceBuilder` |
| 监控 | `ClusterMonitoringService`、`NodeMonitoringService`、`PrometheusClient` |

## 4. 前端结构

前端使用 React、TypeScript、Vite、Ant Design 和 ECharts。

当前导航分为：

- 算力资源：集群管理、资源池、算力规格；
- 业务管理：租户、项目、模型广场、推理服务；
- 监控运维：推理服务监控、集群监控、监控告警；
- 创新实验室：负载感知、数字孪生、策略仿真。

当前路由以 `frontend/src/App.tsx` 为准，页面以 `frontend/src/pages/real` 为准，后端调用主要集中在 `frontend/src/api/real.ts`。

## 5. Windows 开发

运行前准备：

- Java 11；
- Maven；
- Node.js 与 npm。

默认同时重建并启动前后端：

```powershell
.\scripts\dev-start.ps1
```

单独操作：

```powershell
.\scripts\dev-start.ps1 -Target Backend
.\scripts\dev-start.ps1 -Target Frontend
.\scripts\clean-dev.ps1
.\scripts\package-backend.ps1
```

运行日志位于工程根目录 `.runtime`。后端正式运行日志由 `logback-spring.xml` 管理，标准输出日志主要用于启动失败排查。

## 6. Linux 发布

推荐形态是：

- 后端以 Spring Boot Jar 运行；
- 前端构建为静态文件，由 Nginx 托管；
- Nginx 将 `/api/` 代理到本机 8080 端口；
- 发布目录放在 `/opt/acmp`，避免 Nginx 访问 `/root` 时出现权限问题。

构建入口：

```bash
bash scripts/linux-release/build-package.sh
```

脚本构建前后端并生成带配置、日志目录、启动和停止脚本的发布目录。部署脚本 `deploy-release-package.sh` 从 `/root/acmp/release` 读取发布压缩包，解压到 `/opt/acmp/runs`，先停止旧版本，再启动新版本并执行健康检查。

详细命令见 [Linux 前后端部署](27-LINUX-FRONTEND-BACKEND-DEPLOYMENT.md)。

## 7. Kubernetes 测试环境

`scripts/linux-k8s` 提供：

- 公共依赖安装；
- Master 初始化；
- Worker 加入；
- Fake GPU Worker；
- 虚拟机克隆后的辅助处理。

Fake GPU 适合验证集群同步、独享池和调度标签，不具备真实 CUDA、DCGM 指标或 HAMi 共享资源能力。

## 8. 监控组件离线部署

`scripts/monitoring-offline` 将外网下载和内网安装分离：

1. 外网生成 Chart 与镜像离线包；
2. 将离线包复制到内网；
3. 每个可能运行 Pod 的 Node 导入镜像；
4. Master 安装 kube-prometheus-stack 和 GPU 监控组件；
5. 执行验证或缺失镜像诊断脚本。

具体使用方式见 `scripts/monitoring-offline/README.md`。

## 9. 配置与日志

后端默认端口为 8080。常用外部配置包括：

| 配置 | 作用 |
|---|---|
| `PROMETHEUS_URL` | Prometheus API 地址 |
| `JWT_SECRET` | JWT 签名密钥 |
| `AES_KEY` | kubeconfig 加密密钥 |

开发环境可使用工程内默认值；部署到长期运行环境时，应通过环境变量或外部配置文件覆盖密钥和外部地址。

Linux 发布目录中的日志分为：

- 后端业务日志：由 Logback 滚动输出；
- 后端启动日志：用于 Java 命令、配置加载和早期退出；
- Nginx access/error 日志：用于页面和代理排查。

## 10. 发布建议

当前阶段建议保持以下原则：

- 固定已验证的 Kubernetes 和 containerd 版本；
- 应用包、监控离线包和推理镜像分别管理；
- 配置文件不直接打入不可修改的 Jar；
- 每次发布保留独立版本目录；
- 使用健康检查判断后端是否真正启动；
- 内网部署前在外网完成镜像清单和完整性校验。

当前 MVP 不需要拆成微服务。只有当资源同步、部署编排和监控查询出现明确的独立扩容需求时，再考虑拆分。
