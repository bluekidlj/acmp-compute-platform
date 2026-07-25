# 监控运维 MVP 设计

## 1. 范围

监控运维第一版只提供两个独立入口：

1. 推理服务监控；
2. 集群监控。

两个模块均采用“列表 -> 详情”的页面层级。详情页顶部展示关键状态，下面使用同一个时间范围
查询少量原始监控序列。本阶段不增加告警配置、日志检索、事件中心和自动处置。

## 2. 数据链路

```text
vLLM /metrics --------------------------+
                                         |
node-exporter ---------------------------+--> Prometheus --> ACMP 后端 --> ACMP 前端
                                         |
kube-state-metrics ----------------------+
                                         |
NVIDIA DCGM Exporter --------------------+

Kubernetes API --> ACMP 后端（服务、集群、节点和副本基本信息）
```

Prometheus 是统一的历史数据查询入口。ACMP 前端不直接访问 Prometheus，也不在浏览器中拼接
PromQL。后端负责固定查询、标签关联和返回前端协议。

## 3. 推理服务监控

### 3.1 数据来源

| 页面数据 | 来源 |
|---|---|
| 服务名称、模型、状态、期望副本、就绪副本 | ACMP 数据库和 Kubernetes Deployment |
| 运行请求数、等待请求数 | vLLM `/metrics` |
| Prompt Token、Generation Token 累计值 | vLLM `/metrics` |
| Prompt Token/s、Generation Token/s | Prometheus 对累计值执行 `rate()` |
| GPU 利用率、显存已用 | DCGM Exporter，仅在 Pod 与 GPU UUID 关联准确后返回 |

第一版详情页固定展示：

1. 运行请求数和等待请求数；
2. Prompt Token/s 和 Generation Token/s；
3. 服务 GPU 利用率和显存已用（没有准确关联时不返回该曲线）。

不同 vLLM 版本的指标名称可能变化，后端配置必须以目标 vLLM 镜像 `/metrics` 的实际输出
为准，不在前端写死指标名。

### 3.2 列表协议

```http
GET /api/v1/monitoring/deployments?projectId={projectId}&status={status}
```

```json
[
  {
    "deploymentId": "deployment-id",
    "projectId": "project-id",
    "projectName": "项目名称",
    "name": "推理服务名称",
    "modelName": "模型名称",
    "status": "RUNNING",
    "readyReplicas": 1,
    "replicas": 1,
    "runningRequests": 6,
    "waitingRequests": 0,
    "lastCollectedAt": "2026-07-26T10:00:00+08:00"
  }
]
```

### 3.3 详情协议

```http
GET /api/v1/monitoring/deployments/{deploymentId}?start={ISO8601}&end={ISO8601}&step={seconds}
```

```json
{
  "summary": {
    "deploymentId": "deployment-id",
    "projectId": "project-id",
    "projectName": "项目名称",
    "name": "推理服务名称",
    "modelName": "模型名称",
    "status": "RUNNING",
    "readyReplicas": 1,
    "replicas": 1,
    "runningRequests": 6,
    "waitingRequests": 0,
    "promptTokensPerSecond": 320.5,
    "generationTokensPerSecond": 86.2,
    "lastCollectedAt": "2026-07-26T10:00:00+08:00"
  },
  "series": [
    {
      "metric": "running_requests",
      "unit": "requests",
      "points": [{"timestamp": 1785030900, "value": 4}]
    },
    {
      "metric": "waiting_requests",
      "unit": "requests",
      "points": [{"timestamp": 1785030900, "value": 0}]
    },
    {
      "metric": "prompt_tokens_per_second",
      "unit": "token/s",
      "points": [{"timestamp": 1785030900, "value": 285.2}]
    },
    {
      "metric": "generation_tokens_per_second",
      "unit": "token/s",
      "points": [{"timestamp": 1785030900, "value": 74.6}]
    }
  ]
}
```

## 4. 集群监控

### 4.1 数据来源

| 页面数据 | 来源 |
|---|---|
| 集群状态、版本、节点数、GPU 数 | ACMP 数据库和 Kubernetes API |
| Node CPU 使用率 | node-exporter，经 Prometheus 使用标准 `rate()` |
| Node 内存使用率 | node-exporter |
| GPU 利用率、显存已用 | NVIDIA DCGM Exporter |
| 节点 Ready 状态 | kube-state-metrics 或 Kubernetes API |

第一版详情页固定展示：

1. 集群 CPU 使用率；
2. 集群内存使用率；
3. GPU 平均利用率；
4. GPU 显存已用。

集群曲线按真实 Node 聚合。Master 没有 GPU 时不产生 GPU 序列，不补零制造设备数据。

### 4.2 列表协议

```http
GET /api/v1/monitoring/clusters
```

```json
[
  {
    "clusterId": "cluster-id",
    "name": "集群名称",
    "status": "ACTIVE",
    "nodeCount": 2,
    "readyNodeCount": 2,
    "gpuCount": 8,
    "cpuUsagePercent": 34.2,
    "memoryUsagePercent": 48.7,
    "gpuUsagePercent": 61.5,
    "gpuMemoryUsedMib": 20480,
    "lastCollectedAt": "2026-07-26T10:00:00+08:00"
  }
]
```

### 4.3 详情协议

```http
GET /api/v1/monitoring/clusters/{clusterId}?start={ISO8601}&end={ISO8601}&step={seconds}
```

响应结构与推理服务详情相同，包含 `summary` 和 `series`。集群序列使用固定名称：

- `cpu_usage_percent`
- `memory_usage_percent`
- `gpu_usage_percent`
- `gpu_memory_used_mib`

当前两节点 MVP 通过后端环境变量配置一套 Prometheus：

```text
PROMETHEUS_URL=http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090
```

未配置、不可达或查询无数据时，接口仍返回真实集群资产摘要，对应指标为 `null`，
`series` 为空数组。

## 5. 时间参数

前端提供：

- 最近 15 分钟；
- 最近 1 小时；
- 最近 6 小时；
- 最近 24 小时；
- 自定义范围。

前端传递 `start`、`end` 和 `step`。后端根据固定指标模板查询 Prometheus
`/api/v1/query_range`。建议步长：

| 时间范围 | step |
|---|---:|
| 15 分钟 | 15 秒 |
| 1 小时 | 60 秒 |
| 6 小时 | 300 秒 |
| 24 小时 | 900 秒 |

## 6. 无数据约定

- 没有 Prometheus 数据时，`series` 返回空数组；
- 没有服务与 GPU 的准确对应关系时，不返回服务 GPU 序列；
- 前端显示“暂无监控数据”，不能使用 Node GPU 数据冒充服务 GPU 数据；
- `lastCollectedAt` 为空表示从未采集；
- Docker Desktop 模拟 GPU 只能展示设备资产，不能生成真实 DCGM 性能曲线。

## 7. 本轮前端实现

本轮只实现页面、路由和交互。列表基本信息使用现有 ACMP 接口；监控曲线使用明确标记的
前端样例数据预览页面效果。后端监控接口完成后，用本文协议替换样例数据。

## 8. 监控告警

### 8.1 页面

“监控告警”是监控运维下的第三个独立入口，页面包含两个 Tab：

1. 告警规则：展示规则并提供新增、启用、停用和删除操作；
2. 告警记录：按照告警发生时间倒序展示告警中和已恢复的事件。

新增规则只保留以下字段：

- 规则名称；
- 监控集群；
- 告警级别：`WARNING` 或 `CRITICAL`；
- PromQL 表达式；
- 持续时间；
- 告警信息。

Prometheus 查询语言的正式名称是 PromQL。前端提供常用模板，但指标名和标签必须以目标
集群 Prometheus 中实际存在的数据为准。

### 8.2 执行链路

```text
管理员填写 PromQL
    -> ACMP 后端校验表达式
    -> ACMP 后端保存规则
    -> ACMP 后端创建或更新 Kubernetes PrometheusRule
    -> Prometheus 执行规则
    -> Alertmanager 接收告警
    -> Alertmanager Webhook 回调 ACMP
    -> ACMP 保存告警发生和恢复记录
    -> 前端查询告警记录
```

MVP 使用 Prometheus Operator 的 `PrometheusRule` 执行规则，不在 ACMP 后端增加定时任务
重复执行 PromQL。

Alertmanager 默认主要维护当前告警状态，不适合作为长期历史库。因此告警发生和恢复事件
通过 Webhook 回调 ACMP，由 ACMP 保存最小历史记录。

### 8.3 告警规则协议

查询规则：

```http
GET /api/v1/monitoring/alert-rules?clusterId={clusterId}
```

新增规则：

```http
POST /api/v1/monitoring/alert-rules
Content-Type: application/json
```

```json
{
  "name": "GPU 利用率持续过高",
  "clusterId": "cluster-id",
  "severity": "WARNING",
  "expression": "avg by (Hostname, UUID) (DCGM_FI_DEV_GPU_UTIL) > 90",
  "durationMinutes": 5,
  "summary": "GPU 利用率连续5分钟超过90%"
}
```

响应：

```json
{
  "id": "rule-id",
  "name": "GPU 利用率持续过高",
  "clusterId": "cluster-id",
  "clusterName": "内网推理集群",
  "severity": "WARNING",
  "expression": "avg by (Hostname, UUID) (DCGM_FI_DEV_GPU_UTIL) > 90",
  "durationMinutes": 5,
  "summary": "GPU 利用率连续5分钟超过90%",
  "enabled": true,
  "createdAt": "2026-07-26T10:00:00+08:00"
}
```

启用或停用：

```http
PATCH /api/v1/monitoring/alert-rules/{ruleId}/status
Content-Type: application/json
```

```json
{
  "enabled": false
}
```

删除：

```http
DELETE /api/v1/monitoring/alert-rules/{ruleId}
```

删除成功返回 `204 No Content`。启停和删除操作必须同步修改目标集群中的
`PrometheusRule`。

### 8.4 PromQL 校验

新增规则时后端必须：

1. 检查表达式非空且长度在平台限制内；
2. 使用目标 Prometheus 的查询接口校验语法；
3. 禁止前端提交 PrometheusRule YAML，只接受单条表达式；
4. 由后端生成稳定的规则名称、Labels 和 Annotations；
5. 表达式无数据时允许保存，但响应中返回校验提示；
6. Prometheus 不可访问或语法错误时拒绝创建，并返回明确错误信息。

校验接口：

```http
POST /api/v1/monitoring/promql/validate
Content-Type: application/json
```

```json
{
  "clusterId": "cluster-id",
  "expression": "DCGM_FI_DEV_GPU_UTIL > 90"
}
```

```json
{
  "valid": true,
  "hasData": true,
  "message": "PromQL 校验通过"
}
```

### 8.5 Alertmanager Webhook

Alertmanager 调用内部接口：

```http
POST /internal/v1/monitoring/alert-events
```

该接口只允许集群内 Alertmanager 调用，不暴露给普通前端用户。后端根据
`fingerprint + startsAt` 识别同一次告警，收到 `firing` 时创建或更新记录，收到
`resolved` 时补充恢复时间。

### 8.6 告警记录协议

```http
GET /api/v1/monitoring/alert-events?clusterId={clusterId}&status={status}&severity={severity}&start={ISO8601}&end={ISO8601}
```

响应按 `startsAt` 倒序：

```json
[
  {
    "id": "event-id",
    "ruleId": "rule-id",
    "ruleName": "GPU 利用率持续过高",
    "clusterId": "cluster-id",
    "clusterName": "内网推理集群",
    "severity": "WARNING",
    "status": "FIRING",
    "target": "gpu-worker-01 / GPU-0",
    "value": "96",
    "summary": "GPU 利用率连续5分钟超过90%",
    "startsAt": "2026-07-26T10:00:00+08:00",
    "endsAt": null
  }
]
```

### 8.7 本轮前端状态

本轮规则保存在浏览器 `localStorage`，用于验证新增、启停和删除流程。告警记录不生成
虚假事件，接入 Alertmanager Webhook 后再由后端接口返回真实记录。
