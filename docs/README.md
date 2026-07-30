# ACMP 文档导航

本目录同时包含当前工程知识、阶段设计稿、验证记录和专项部署说明。为了避免把历史方案误认为当前实现，阅读时以本页列出的“主文档”为准。

## 主文档

建议按以下顺序阅读：

1. [项目 README](../README.md)：平台定位、解决的问题和当前效果。
2. [运行环境与集群前置条件](01-RUNTIME-PREREQUISITES.md)：接入 ACMP 前需要准备的 Kubernetes 与基础组件。
3. [工程结构与运行部署](02-ENGINEERING-GUIDE.md)：代码组织、开发方式和发布方式。
4. [算力资源管理](03-COMPUTE-RESOURCE-MANAGEMENT.md)：集群、Node、GPU、资源池和算力规格。
5. [业务管理](04-BUSINESS-MANAGEMENT.md)：租户、项目、模型和推理服务。
6. [监控运维](05-MONITORING-OPERATIONS.md)：集群、节点、GPU、推理服务监控和告警。
7. [架构迭代经历](06-ARCHITECTURE-EVOLUTION.md)：架构选择和落地过程。
8. [后续扩展方向](07-EXTENSIBILITY-ROADMAP.md)：创新实验室及后续演进。

## 专项操作文档

以下文档面向具体部署或排错任务，不作为理解平台的第一入口：

| 文档 | 用途 |
|---|---|
| [Linux 前后端部署](27-LINUX-FRONTEND-BACKEND-DEPLOYMENT.md) | Linux 打包、启动、停止、日志与健康检查 |
| [监控采集组件部署](21-K8S-MONITORING-COLLECTION-DEPLOYMENT.md) | Prometheus 与 GPU 监控组件安装 |
| [离线模型与 Harbor](16-OFFLINE-HARBOR-MODEL-DEPLOYMENT.md) | 内网镜像和本地模型文件方案 |
| [HAMi 节点级共享](27-HAMI-NODE-SHARING-MVP.md) | 共享池切分的详细设计 |
| [本地 Kubernetes 验证](10-LOCAL-K8S-VERIFICATION.md) | 本地测试集群验证 |
| [真实链路验证记录](11-LOCAL-K8S-REAL-TRACE.md) | API 与 Kubernetes 对象的实际验证过程 |

脚本本身的用法分别见：

- `scripts/linux-k8s/README.md`
- `scripts/monitoring-offline/README.md`
- `scripts/linux-release/`

## 历史设计资料

`00` 至 `27` 编号文档记录了平台在不同阶段的设计和问题修正。这些资料保留了决策背景，但部分对象名称、接口路径和约束已被后续方案替代。

使用原则：

- 理解当前平台：看本页主文档。
- 执行具体安装：看专项操作文档和对应脚本 README。
- 追溯某项设计为什么出现：再看编号历史文档。
- 判断当前行为：以 Controller、Service、Kubernetes 适配代码为最终依据。

`docs/专利` 下的内容属于技术创新构想，不代表当前产品已经实现。
