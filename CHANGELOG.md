# Changelog

所有项目的**显著改动**记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/)。

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
