# 监控运维

监控运维用于观察平台已经管理的集群、Node、GPU 和推理服务。它不自行采集指标，而是把 Prometheus 体系中的原始数据整理成与 ACMP 业务对象一致的页面。

## 1. 数据链路

```text
Node Exporter ───────┐
kube-state-metrics ──┤
DCGM Exporter ───────┼→ Prometheus → ACMP 后端 → 监控页面
vLLM /metrics ───────┘
```

数据职责：

| 来源 | 主要数据 |
|---|---|
| Kubernetes API | 集群、Node、Pod、Deployment、Ready 状态 |
| Node Exporter | CPU、内存、磁盘、网络、Load |
| kube-state-metrics | Kubernetes 对象、副本和调度状态 |
| DCGM Exporter | 单卡利用率、显存、温度、功耗等 GPU 指标 |
| vLLM Metrics | 请求、吞吐、Token、延迟和队列 |

Prometheus 是指标查询入口，ACMP 数据库不保存完整监控时间序列。

## 2. 集群监控

集群监控采用三层下钻，避免在集群层展示过多细节：

```text
集群列表
  → Node 列表
    → Node 监控详情
```

### 2.1 集群列表

集群列表用于判断集群整体是否健康，展示：

- 集群名称和 Kubernetes 版本；
- 状态；
- Node 总数与 Ready 数；
- GPU 设备数；
- CPU、内存等简要聚合指标。

集群层只提供概览，不在这里展开单卡曲线。

### 2.2 Node 列表

进入集群后，以列表展示真实 Kubernetes Node：

- Node 名称、角色、Internal IP 和 Ready 状态；
- CPU 使用率；
- 内存使用率；
- 磁盘使用率；
- GPU 平均利用率。

这些值用于快速定位异常主机。GPU 平均值为空不代表 GPU 库存不存在，只表示 Prometheus 没有查询到匹配的 DCGM 指标。

### 2.3 Node 详情

Node 详情分三部分：

1. 平均统计：CPU、内存、磁盘、Load、网络和 GPU 平均值；
2. 单卡仪表盘：每张 GPU 当前利用率；
3. 时间曲线：CPU、内存、磁盘、网络、Load、GPU 利用率和显存变化。

图表使用 ECharts。时间范围由用户选择，坐标轴根据数据波动自适应，但百分比和容量等指标仍保留可理解的单位。

代码入口：

- `src/main/java/com/acmp/compute/controller/ClusterMonitoringController.java`
- `src/main/java/com/acmp/compute/controller/NodeMonitoringController.java`
- `src/main/java/com/acmp/compute/service/ClusterMonitoringService.java`
- `src/main/java/com/acmp/compute/service/NodeMonitoringService.java`
- `frontend/src/pages/real/Monitoring.tsx`

## 3. 算力资源页面与监控页面的边界

“算力资源 / 集群管理”展示资源事实和节点配置，包括 Node、GPU 库存、标签、污点和资源池关系。

“监控运维 / 集群监控”展示随时间变化的运行指标。

两者使用相同集群和 Node 标识，但页面职责不同。资源详情页不重复加入监控曲线，避免管理信息和运维信息混在一起。

## 4. GPU 监控

GPU 库存来自 Kubernetes 同步；GPU 利用率来自 DCGM Exporter。这两条链路相互独立：

- 有库存、无 DCGM：能看到 GPU 型号和数量，但监控仪表盘为空；
- 有 DCGM、Node 标签不匹配：Prometheus 有数据，但平台无法关联到目标 Node；
- Fake GPU：可以验证资产和调度，不能产生真实温度、功耗和利用率。

真实 GPU 监控建议至少展示：

- 单卡 GPU 利用率；
- 单卡显存已用和显存利用率；
- 温度；
- 功耗；
- 可选的错误或健康指标。

平台不生成 Mock GPU 监控数据。无数据时保留坐标系和“暂无监控数据”状态，避免把模拟值误认为真实指标。

## 5. 推理服务监控

推理服务监控先展示服务列表，再进入单服务详情。

列表关注：

- 服务名称、模型、项目和状态；
- 期望副本与 Ready 副本；
- 使用的算力规格；
- 服务地址和最近状态。

详情顶部展示部署关键信息，下方只保留能够从 vLLM 原始指标稳定获得的曲线：

- 请求速率；
- 输入/输出 Token 吞吐；
- 请求延迟；
- 等待请求数或队列长度；
- 可选的 KV Cache 使用率。

当前部署状态来自平台记录和 Kubernetes；完整 vLLM 曲线需要目标 vLLM 服务暴露 `/metrics`，并被 Prometheus 抓取。接入前不应在前端构造业务监控数据。

页面入口：

- `frontend/src/pages/real/Monitoring.tsx`
- `frontend/src/pages/real/Deployments.tsx`
- `frontend/src/pages/real/DeploymentDetail.tsx`

部署记录接口入口：

- `src/main/java/com/acmp/compute/controller/GlobalDeploymentController.java`
- `src/main/java/com/acmp/compute/controller/ModelDeploymentController.java`

## 6. 监控告警

告警模块产品形态包括：

- 告警规则列表；
- 新增规则，支持 PromQL；
- 启用、停用和删除规则；
- 按时间查看告警事件。

规则至少包含名称、级别、PromQL、持续时间、状态和说明。

当前告警页面及交互协议已经形成，后端规则持久化、Prometheus Rule 同步和 Alertmanager 事件接收仍属于后续接入范围。当前实现边界以是否存在对应 Controller 和 Service 为准，不能仅根据页面存在判断后端已经完成。

前端入口：

- `frontend/src/pages/real/AlertMonitoring.tsx`

详细协议参考 `docs/20-MONITORING-OPERATIONS-MVP.md`。

## 7. 无数据约定

监控接口区分三种情况：

- 指标有值：返回真实时间序列；
- 查询成功但没有匹配指标：返回空序列；
- Prometheus 不可达或查询失败：返回明确错误并记录日志。

前端对空序列保留图表结构，对错误显示连接或查询异常。不能使用 `0` 替代“未采集”，因为真实 0 和无数据含义不同。

## 8. Prometheus 接入

后端使用：

```text
PROMETHEUS_URL=http://<NodeIP>:30090
```

代码入口：

- `src/main/java/com/acmp/compute/monitoring/PrometheusClient.java`
- `src/main/resources/application.yml`

内网安装和缺失镜像诊断见：

- `scripts/monitoring-offline/README.md`
- `docs/21-K8S-MONITORING-COLLECTION-DEPLOYMENT.md`

## 9. 模块边界

当前监控模块负责查询和展示，不负责：

- 替代 Prometheus 存储时间序列；
- 在数据库中复制全部指标；
- 自动修改资源池或部署策略；
- 在指标不足时生成模拟数据；
- 将告警直接转化为生产故障操作。

监控数据后续可以作为负载感知和数字孪生的输入，但两者之间应保留明确的快照和审核边界。
