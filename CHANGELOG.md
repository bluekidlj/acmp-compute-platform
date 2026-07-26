# Changelog

所有项目的**显著改动**记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/)。

---

## [Unreleased] - 监控组件离线安装脚本

### Added
- 新增监控组件外网离线包下载脚本，固定 Helm Chart、渲染最终 values、提取镜像并导出镜像 tar
- 新增内网 containerd 镜像导入和 kube-prometheus-stack、gpu-operator 安装脚本
- 新增监控安装验证脚本和离线安装说明，Prometheus 默认通过 NodePort 30090 提供给集群外 ACMP 后端访问

---

## [Unreleased] - Linux Kubernetes Demo 集群脚本

### Added
- 新增 Ubuntu Master/Worker 安装脚本，固定 containerd 1.7.27、Kubernetes 1.28.15 和必要的 runc、CNI、crictl 版本
- 新增阿里云 Ubuntu、Kubernetes 软件源和 Kubernetes 核心镜像仓库配置
- 新增 Master 初始化、Flannel 安装、永久 Worker Join 命令生成及 Worker 加入脚本
- 新增在 Master 上为指定真实 Worker 安装 Fake GPU Operator、模拟 Tesla V100 并验证资源上报的脚本
- 新增 VM 克隆后重置脚本，用于清理 Kubernetes 残留、重置 machine-id 并保留基础安装环境
- 新增 VM 克隆后网络初始化脚本，用于修改主机名、IP 和 SSH host key
- 新增 [Linux Kubernetes Demo 集群安装说明](scripts/linux-k8s/README.md)

## [Unreleased] - Linux Jar 启动脚本

### Added
- 新增通用 Linux 启动脚本 `scripts/linux-app/start.sh`，支持 Jar + conf 目录式部署
- 启动脚本自动创建 `log/` 目录，并将 stdout / stderr 分开落盘，便于离线环境排查
- 启动脚本自动生成 PID 文件，支持重复启动检测和手工查看进程状态

## [Unreleased] - Spring Boot 文件日志

### Added
- 新增 `src/main/resources/logback-spring.xml`，同时输出控制台日志和按天滚动的文件日志
- 新增 `logging.file.path` 配置，默认写入 `./log` 目录，便于 Linux 目录式部署排查

## [Unreleased] - Linux 前后端发布目录

### Added
- 新增 `scripts/linux-release/build-package.sh`，可在 Linux 上一次性构建前后端并组装发布目录
- 新增发布目录总启动脚本 `start-all.sh`，按目录方式启动后端 Jar 和 Nginx 前端
- 新增 `docs/27-LINUX-FRONTEND-BACKEND-DEPLOYMENT.md`，说明打包、启动、日志和排查方式

---

## [Unreleased] - 资源池详情修复

### Fixed
- 固定资源池查询时自动补齐独享池和共享池，避免旧 Demo 数据库缺初始化数据导致详情接口返回 Not Found
- 新增资源池详情路由和页面，资源池列表可直接进入详情并查看规格和已入池 Node
- 算力规格支持删除；未被配额或推理服务引用时释放关联 Node/GPU 入池归属并删除规格

---

## [Unreleased] - 集群调试重置

### Added
- 集群管理增加“重置全部集群”入口，输入 `RESET` 后清除 ACMP Node 标签、规格、配额和库存，并从保留的 kubeconfig 重新同步
- 重置接口在存在任何推理服务记录时拒绝执行，并返回每个集群的标签清理和同步结果
- 推理服务列表增加删除入口；Kubernetes API 不可达时记录警告并继续删除平台记录，避免失效记录阻塞调试重置
- 新增 [集群调试重置 MVP 文档](docs/25-CLUSTER-DEBUG-RESET-MVP.md)

### Removed
- 删除未被业务代码使用的 `tenant_member` 遗留表；项目权限继续使用 `project_member`

---

## [Unreleased] - 推理服务部署流程

### Fixed
- 修复进入第三步运行配置时表单可能被默认提交的问题
- 部署操作改为显式“确认并部署”按钮和二次确认，不再使用 Form 自动提交
- 推理服务部署将“算力规格节点数”改为可调整的“副本数”，默认 1，并按租户剩余规格节点限制上限

### Added
- 部署表单增加轻量流程演示模式，使用 http-echo 真实验证 GPU 规格调度、Pod Ready 和 Service 代理
- 真实推理部署展示模型的 GPU 主机绝对目录，并为宿主机路径和容器路径提供填写示例
- Kubernetes 主提交链路打印 Namespace、Deployment、Service 完整 YAML，并记录各阶段成功或失败信息

### Changed
- 模型存储路径改为 GPU 主机完整绝对目录，生成 YAML 时直接写入 `hostPath.path`，不再自动追加模型名
- 模型广场已登记模型支持修改元信息和模型路径
- 模型广场将精选单模型改为 DeepSeek、通义千问、GLM、MiniMax M 四个模型系列，已登记模型增加系列归属

---

## [Unreleased] - GPU 入池规格复用

### Fixed
- 相同资源池、GPU 硬件和算力参数的 GPU 入池时复用已有算力规格，不再按每张卡重复创建规格
- GPU 入池默认规格名不再包含 GPU 编号
- 修复规格复用后容量仍按单张 GPU 计算及来源 GPU 多行查询异常
- 修复共享池 GPU 已入池但规格关联为空的孤儿数据

### Documentation
- 新增 GPU 入池规格复用与重复数据安全合并规则

### Changed
- 共享池按 vGPU 规格聚合展示，不再显示容易误解为 vGPU 编号的物理 GPU 编号
- 共享池列表增加总规格节点数，以及扣除租户已分配配额后的可用规格节点数

---

## [Unreleased] - Node 整体入池与规格调度方案

### Added
- 新增 Node 级资源池归属和算力规格字段
- 新增整台 Node 全部 GPU 一次性入池接口，并自动调用 Kubernetes API 写入调度标签
- 推理 Deployment 根据资源池类型和算力规格自动生成 `nodeSelector`
- 新增静态单元测试验证 Deployment 调度标签

### Changed
- 资源池前端从逐张 GPU 选择改为 Node 选择、统一规格设置和 Node 列表展示
- 移除逐张 GPU 入池接口，规格复用和容量统计改由 Node 批量入池驱动

### Documentation
- 新增 Node 全量 GPU 一次性加入独享池或共享池的 MVP 改造方案
- 明确 Kubernetes Node 规格标签、Deployment nodeSelector、演示验收流程和后续 TODO

---

## [Unreleased] - 监控运维 MVP

### Added
- 新增推理服务监控列表与详情页，展示服务摘要、运行/等待请求和 Token 吞吐曲线
- 新增集群监控列表与详情页，展示集群摘要及 CPU、内存、GPU、显存曲线
- 实现集群监控后端接口，通过固定 PromQL 查询 Prometheus 并返回统一时间序列
- 集群监控前端改为读取真实后端监控接口，Prometheus 无数据时展示空状态
- 集群监控列表以现有集群资产为主数据，监控接口不可用时仍展示状态、版本、节点、GPU 和同步时间
- 新增监控告警入口，支持 PromQL 规则新增、启停、删除及告警记录列表
- 新增监控数据来源、时间范围和前后端接口协议文档
- 新增 Kubernetes 监控组件清单、离线安装、vLLM ServiceMonitor、DCGM 与 ACMP 接入手册
- 详见 [docs/20-MONITORING-OPERATIONS-MVP.md](docs/20-MONITORING-OPERATIONS-MVP.md)
- 详见 [docs/21-K8S-MONITORING-COLLECTION-DEPLOYMENT.md](docs/21-K8S-MONITORING-COLLECTION-DEPLOYMENT.md)

---

## [Unreleased] - 创新实验室 SimAI MVP 方案

### Added
- 增加银行业大模型负载感知、数字孪生和策略化运营的一体化 MVP 方案
- 数字孪生方案支持流量、并发、长请求三类负载注入
- 数字孪生方案支持 GPU 下线、Node 下线和网络带宽降级三类模拟故障注入
- 明确 SimAI Analytical 的接入边界、策略映射和 KPI 对比方式
- 详见 [docs/19-INNOVATION-LAB-SIMAI-MVP.md](docs/19-INNOVATION-LAB-SIMAI-MVP.md)

---

## [Unreleased] - Kubernetes 真实 Node 列表

### Added
- 集群详情增加 Kubernetes 实际 Node 列表和真实 Node 拓扑 Tab
- Node 详情页展示 Internal IP、角色、状态、CPU、内存及所属 GPU 列表
- `cluster_node.internal_ip` 保存 Kubernetes Node InternalIP
- 详见 [docs/18-REAL-NODE-LIST-MVP.md](docs/18-REAL-NODE-LIST-MVP.md)

### Changed
- 集群同步补充 Kubernetes Version API 版本信息
- 集群列表删除“同步信息”，将 Node、Gpu 列名明确为“节点数”“GPU设备数”
- Node 详情过滤已离线的历史 GPU，Labels/Taints 改为按需展开的标签摘要

---

## [Unreleased] - 异构算力资源池

### Added
- `pool_card` 表（卡 ↔ 池 + 切分粒度）
- 3 个端点：`POST/DELETE/GET /api/v1/pools/{id}/cards`
- `PoolCard` entity/mapper/service/controller
- `K8sResourceBuilder.buildVllmDeployment` 加 `preferredNodes` 参数 → 生成 `nodeAffinity`
- 部署失败回滚 `prq.used`（保证 DB ↔ K8s 一致）
- 删部署回滚 `prq.used`
- 详见 [docs/08-HETEROGENEOUS-POOL.md](docs/08-HETEROGENEOUS-POOL.md)

### Changed
- `ModelDeploymentService.deploy` 加 `preferredNodes`（从 `pool_card.node_name` 聚合）
- `ProjectQuotaService.allocate` 池容量校验改用 `pool_card.slots` 累加
- `ResourcePool.totalNodes` 由 `pool_card` 自动 sum
- `ModelDeployment` 加 `poolCardId` + `resourceKey` 字段

### Deprecated
- `ResourcePoolUpdateRequest.totalNodes` 字段（保留不报错，不再生效）

### Removed
- 无

### Fixed
- 1.0 同构池的"按品牌配额无法独立计量"问题

### Security
- 无

---

## [1.0.0] - 2026-06-XX

### Added - 同构资源池初始版本
- 7 条预置 ComputeSpec（3 EXCLUSIVE + 3 SHARED + 1 OVERSELL）
- K8s 资源落地：NS / SA / Role / RB / Deployment / Service / ResourceQuota
- 三层配额：pool.total / prq.total / prq.used
- vLLM 一键部署 + 模型广场 CRUD
- io.kubernetes:client-java 20.0.0（替代 fabric8）
- 详见 [docs/01-07](docs/)
# 2026-07-26

- 创新实验室前端按业务流程拆分为“负载感知、数字孪生、策略仿真”三个独立左侧导航入口。
- 负载感知增加项目与推理服务选择、Prometheus 时间范围、负载模式、四类曲线和负载快照保存流程。
- 数字孪生增加负载快照、主流模型快照、流量突增或 GPU 下线注入及基线保存流程。
- 策略仿真增加四种调度策略、SimAI KPI 对比结果和结果阅读指南。
