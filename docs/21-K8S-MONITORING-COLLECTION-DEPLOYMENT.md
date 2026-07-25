# Kubernetes 监控数据采集与服务接入手册

## 1. 目标与适用环境

本文说明 ACMP 要完整采集以下数据时，Kubernetes 集群需要安装什么，以及 vLLM
推理服务和 ACMP 后端如何接入：

- Kubernetes 集群、Node、Pod 和 Deployment 状态；
- Node CPU、内存、磁盘和网络；
- NVIDIA GPU 利用率、显存、温度和功耗；
- vLLM 运行请求、等待请求和 Token 吞吐；
- PromQL 告警与告警历史。

适用的 MVP 环境：

- 一台 Kubernetes Master；
- 一台真实 NVIDIA GPU Worker；
- 内网 Harbor；
- 集群不能直接访问公网；
- ACMP 只由管理员维护。

Docker Desktop 中模拟的 `nvidia.com/gpu` 只能验证 GPU 资产发现，不能产生真实利用率、
显存、温度和功耗数据。

## 2. 最终采集链路

```text
Kubernetes API
    └── kube-state-metrics ───────────────────────────┐

Master / GPU Worker
    └── node-exporter ────────────────────────────────┤

NVIDIA GPU Worker
    └── NVIDIA Driver / DCGM Exporter ────────────────┤

vLLM Pod
    └── http://<pod>:8000/metrics ────────────────────┤
                                                       │
                                               Prometheus
                                                       │
                    ┌──────────────────────────────────┼────────────────────┐
                    │                                  │                    │
                  Grafana                        Alertmanager           ACMP 后端
                                                                           │
                                                                        ACMP 前端
```

Exporter 和 vLLM 负责产生指标，Prometheus 负责发现、采集、存储和查询。ACMP 前端不能
直接访问 Prometheus，必须通过 ACMP 后端固定接口查询。

## 3. 安装清单

### 3.1 必装组件

| 组件 | 安装位置 | 作用 | 推荐来源 |
|---|---|---|---|
| Prometheus Operator | monitoring Namespace | 管理 Prometheus、ServiceMonitor 和 PrometheusRule | kube-prometheus-stack |
| Prometheus | monitoring Namespace | 采集并保存时序数据，提供 PromQL API | kube-prometheus-stack |
| kube-state-metrics | 集群内单实例 | 将 Node、Pod、Deployment 等 Kubernetes 状态转换为指标 | kube-prometheus-stack |
| node-exporter | 每个真实 Node 一个 Pod | 采集主机 CPU、内存、磁盘、网络 | kube-prometheus-stack |
| Alertmanager | monitoring Namespace | 接收 Prometheus 告警并发送 Webhook | kube-prometheus-stack |
| Grafana | monitoring Namespace | 安装和排查阶段查看原始指标 | kube-prometheus-stack |
| DCGM Exporter | 每个 NVIDIA GPU Node 一个 Pod | 采集真实 GPU 指标 | GPU Operator 或独立 dcgm-exporter Chart |

`kube-prometheus-stack` 一次安装前六项，不要分别安装六套组件。

### 3.2 GPU 前置条件

DCGM Exporter 安装前必须满足：

1. GPU Worker 能执行 `nvidia-smi`；
2. NVIDIA 驱动与 GPU 型号兼容；
3. 容器运行时已配置 NVIDIA Container Toolkit；
4. NVIDIA Device Plugin 或 GPU Operator 能向 Kubernetes 上报真实 GPU；
5. GPU Pod 能申请并使用 `nvidia.com/gpu`。

如果 `nvidia-smi` 失败，应先修复驱动和运行时，安装 DCGM Exporter 不能解决驱动问题。

### 3.3 二选一组件

DCGM Exporter 只能选择一种安装方式：

- 已使用 NVIDIA GPU Operator：启用 Operator 自带的 DCGM Exporter；
- 驱动和 Device Plugin 已独立维护：单独安装 `nvidia/dcgm-exporter` Chart。

禁止 GPU Operator 和独立 Chart 同时部署两套 DCGM Exporter。

### 3.4 可选组件

| 组件 | 是否需要 | 说明 |
|---|---|---|
| metrics-server | 可选 | 仅用于 `kubectl top` 和 HPA，不保存历史数据，不代替 Prometheus |
| Node Problem Detector | 后续可选 | 采集内核、容器运行时和 Node Condition 问题 |
| 日志系统 | 本期不需要 | Loki、ELK 不属于本次指标采集范围 |
| OpenTelemetry | 本期不需要 | 当前没有链路追踪需求 |
| Pushgateway | 不需要 | vLLM 和 Exporter 都能被 Prometheus 主动抓取 |

## 4. 离线准备清单

### 4.1 在外网机器固定版本

正式下载前建立版本清单，不使用 `latest`：

```text
kube-prometheus-stack Chart 版本
GPU Operator 或 dcgm-exporter Chart 版本
Chart 渲染出的全部镜像名称、Tag 和 Digest
prometheus-values.yaml
gpu-operator-values.yaml 或 dcgm-exporter-values.yaml
Kubernetes 版本
NVIDIA Driver 版本
```

本文不写死 Chart 版本。应先在外网验证目标版本与当前 Kubernetes、驱动兼容，再锁定版本。

### 4.2 下载 Chart

```bash
helm repo add prometheus-community \
  https://prometheus-community.github.io/helm-charts

helm repo add nvidia \
  https://helm.ngc.nvidia.com/nvidia

helm repo update

helm pull prometheus-community/kube-prometheus-stack \
  --version <固定版本>
```

使用 GPU Operator：

```bash
helm pull nvidia/gpu-operator \
  --version <固定版本>
```

仅安装独立 DCGM Exporter：

```bash
helm pull nvidia/dcgm-exporter \
  --version <固定版本>
```

### 4.3 根据最终 Values 提取镜像

不要人工猜测镜像清单。使用最终 Values 渲染：

```bash
helm template kube-prometheus-stack \
  ./kube-prometheus-stack-<version>.tgz \
  --namespace monitoring \
  --values prometheus-values.yaml \
  > kube-prometheus-rendered.yaml
```

对 GPU Chart 执行同样操作，再从渲染结果中提取所有 `image:`。镜像至少包括：

- Prometheus Operator；
- Prometheus；
- Alertmanager；
- Grafana；
- kube-state-metrics；
- node-exporter；
- config-reloader；
- admission webhook；
- DCGM Exporter；
- 选用 GPU Operator 时的 Operator 相关镜像。

### 4.4 推送内网 Harbor

```text
公网拉取所有镜像
    -> 记录镜像 Digest
    -> 推送 harbor.internal/monitoring/*
    -> 修改 Helm Values 中的镜像仓库
    -> 将 Chart、Values、镜像清单一并带入内网
```

不同 Chart 的镜像覆盖字段不同。必须执行以下命令确认目标版本实际字段：

```bash
helm show values ./kube-prometheus-stack-<version>.tgz
helm show values ./gpu-operator-<version>.tgz
```

不要把其他版本的 Values 字段直接复制到当前 Chart。

## 5. 安装 kube-prometheus-stack

### 5.1 最小 Values

创建 `prometheus-values.yaml`：

```yaml
prometheus:
  prometheusSpec:
    retention: 15d
    scrapeInterval: 30s
    evaluationInterval: 30s

    # 允许 ACMP 创建在业务 Namespace 中的 ServiceMonitor 被发现。
    serviceMonitorNamespaceSelector: {}
    podMonitorNamespaceSelector: {}
    serviceMonitorSelector: {}
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelector: {}
    podMonitorSelectorNilUsesHelmValues: false

    storageSpec:
      volumeClaimTemplate:
        spec:
          accessModes:
            - ReadWriteOnce
          resources:
            requests:
              storage: 50Gi

grafana:
  enabled: true
  persistence:
    enabled: true
    size: 10Gi

alertmanager:
  enabled: true

kube-state-metrics:
  enabled: true

prometheus-node-exporter:
  enabled: true
```

如果没有默认 StorageClass，必须补充实际的 `storageClassName`。两节点 MVP 建议：

- 基础指标每 30 秒采集一次；
- GPU 指标每 15～30 秒采集一次；
- Prometheus 保留 15 天；
- Prometheus 初始存储 50 GiB。

### 5.2 内网安装

```bash
helm upgrade --install kube-prometheus-stack \
  ./kube-prometheus-stack-<version>.tgz \
  --namespace monitoring \
  --create-namespace \
  --values prometheus-values.yaml
```

### 5.3 验证

```bash
kubectl get pods -n monitoring -o wide
kubectl get pvc -n monitoring
kubectl get prometheus -n monitoring
kubectl get servicemonitor -A
```

验收条件：

- Prometheus Operator、Prometheus、Grafana、Alertmanager 为 Running；
- kube-state-metrics 为 Running；
- 每个真实 Node 上有一个 node-exporter；
- Prometheus PVC 为 Bound；
- 重启 Prometheus Pod 后历史数据仍存在。

检查 Prometheus Targets：

```bash
kubectl port-forward \
  -n monitoring \
  service/kube-prometheus-stack-prometheus \
  9090:9090
```

访问 `http://127.0.0.1:9090/targets`，确认 node-exporter 和 kube-state-metrics 为 `UP`。
Service 名称以实际安装结果为准。

## 6. 安装 GPU 监控

### 6.1 GPU Operator 方案

适用于希望由 GPU Operator 统一管理 NVIDIA 组件的环境。

`gpu-operator-values.yaml`：

```yaml
dcgmExporter:
  enabled: true
  serviceMonitor:
    enabled: true
    interval: 30s

  # 需要把 GPU 指标关联到 Kubernetes Pod 时启用。
  enablePodLabels: true
```

如果驱动已经安装且继续由操作系统维护：

```yaml
driver:
  enabled: false
```

如果 Device Plugin 已独立安装，也要避免 GPU Operator 重复部署 Device Plugin。

安装：

```bash
helm upgrade --install gpu-operator \
  ./gpu-operator-<version>.tgz \
  --namespace gpu-operator \
  --create-namespace \
  --values gpu-operator-values.yaml
```

### 6.2 独立 DCGM Exporter 方案

适用于驱动和 Device Plugin 已经独立安装的环境：

```bash
helm upgrade --install dcgm-exporter \
  ./dcgm-exporter-<version>.tgz \
  --namespace monitoring \
  --values dcgm-exporter-values.yaml
```

目标 Values 必须开启 Service 和 ServiceMonitor。字段名称以该 Chart 的
`helm show values` 输出为准。

### 6.3 验证

```bash
kubectl get pods -A -o wide | grep dcgm
kubectl get svc -A | grep dcgm
kubectl get servicemonitor -A | grep dcgm
```

在 GPU Worker 上应存在一个 DCGM Exporter Pod。转发 DCGM Service 后检查：

```bash
curl http://127.0.0.1:9400/metrics
```

MVP 至少验证：

```text
DCGM_FI_DEV_GPU_UTIL
DCGM_FI_DEV_FB_USED
DCGM_FI_DEV_FB_FREE
DCGM_FI_DEV_GPU_TEMP
DCGM_FI_DEV_POWER_USAGE
```

实际可用指标与 GPU、驱动、DCGM 和 Exporter 版本相关。某个非核心指标缺失时，以
Exporter 实际输出为准。

Prometheus 中至少验证：

```promql
DCGM_FI_DEV_GPU_UTIL
```

```promql
DCGM_FI_DEV_FB_USED
```

必须运行一次真实 CUDA 或 vLLM 负载再验收。仅出现指标名称但数值始终无效，不能视为
GPU 监控正常。

## 7. vLLM 推理服务接入

### 7.1 确认 vLLM 原始指标

vLLM OpenAI API Server 在同一个服务端口暴露 `/metrics`：

```bash
kubectl port-forward \
  -n <业务-namespace> \
  service/<vllm-service> \
  8000:8000

curl http://127.0.0.1:8000/metrics
```

第一版重点检查：

```text
vllm:num_requests_running
vllm:num_requests_waiting
vllm:prompt_tokens_total
vllm:generation_tokens_total
```

指标名可能随 vLLM 版本变化，必须以 Harbor 中实际 vLLM 镜像的 `/metrics` 输出为准。

### 7.2 确保 Service 端口有名称

Prometheus Operator 的 ServiceMonitor 通过 Service 的命名端口发现采集地址：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: vllm-demo
  namespace: tenant-demo
  labels:
    app: vllm-demo
    acmp_tenant_id: tenant-demo
    acmp_project_id: project-demo
    acmp_deployment_id: deployment-demo
spec:
  selector:
    app: vllm-demo
  ports:
    - name: http
      port: 8000
      targetPort: 8000
```

ACMP 创建推理服务时，应自动为 Service 增加：

- `app`；
- `acmp_tenant_id`；
- `acmp_project_id`；
- `acmp_deployment_id`。

不要使用请求 ID、用户输入或会话 ID 作为 Prometheus Label。

### 7.3 创建 ServiceMonitor

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: vllm-demo
  namespace: tenant-demo
spec:
  selector:
    matchLabels:
      app: vllm-demo
  endpoints:
    - port: http
      path: /metrics
      interval: 30s
  targetLabels:
    - acmp_tenant_id
    - acmp_project_id
    - acmp_deployment_id
```

`targetLabels` 把 Service 上的平台标识复制到采集后的时间序列，ACMP 后端才能按租户、
项目和推理服务过滤数据。

推荐由 ACMP 后端在创建 Deployment 和 Service 时同步创建 ServiceMonitor，而不是要求
管理员为每个推理服务手工创建。

### 7.4 验证采集

```bash
kubectl get servicemonitor -n tenant-demo vllm-demo
kubectl get service -n tenant-demo vllm-demo
kubectl get endpoints -n tenant-demo vllm-demo
```

在 Prometheus Targets 页面中，目标应为 `UP`。再执行：

```promql
vllm:num_requests_running{acmp_deployment_id="deployment-demo"}
```

```promql
rate(vllm:prompt_tokens_total{
  acmp_deployment_id="deployment-demo"
}[5m])
```

```promql
rate(vllm:generation_tokens_total{
  acmp_deployment_id="deployment-demo"
}[5m])
```

如果 `/metrics` 能访问但 Prometheus Target 不存在，依次检查：

1. ServiceMonitor 与 Service 的 Label 是否匹配；
2. `endpoints.port` 是否等于 Service 端口名称，而不是数字；
3. Prometheus 是否允许发现业务 Namespace；
4. Prometheus 的 ServiceMonitor Selector 是否选中该对象；
5. NetworkPolicy 是否允许 Prometheus访问业务 Pod。

## 8. GPU 与推理服务关联

集群 GPU 监控只需要 DCGM Exporter 的 Node 和 GPU UUID 标签。推理服务 GPU 监控还需要：

```text
ACMP Deployment
    -> Kubernetes Pod
    -> Pod 分配的 GPU UUID
    -> DCGM 指标中的 UUID
```

启用 DCGM 的 Kubernetes Pod 映射后，先检查实际标签：

```promql
DCGM_FI_DEV_GPU_UTIL
```

不同版本可能提供 `pod`、`namespace`、`container`、`Hostname`、`UUID` 等不同标签。后端
必须根据真实序列建立查询，不能假设所有版本标签完全一致。

如果只能确定 Pod 所在 Node，不能确定实际 GPU UUID：

- 集群监控可以展示 Node/GPU 数据；
- GPU 设备详情可以展示单卡数据；
- 推理服务详情不能把整台 Node 的 GPU 指标描述为该服务数据。

## 9. 告警接入

### 9.1 PrometheusRule

ACMP 告警页面提交 PromQL 后，后端负责：

1. 调用目标 Prometheus 校验表达式；
2. 保存 ACMP 告警规则；
3. 在目标集群创建或更新 `PrometheusRule`；
4. 启停、删除时同步修改该 CR；
5. 用户不能直接提交任意 PrometheusRule YAML。

示例：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: acmp-gpu-high-utilization
  namespace: monitoring
spec:
  groups:
    - name: acmp.rules
      rules:
        - alert: AcmpGpuHighUtilization
          expr: avg by (Hostname, UUID) (DCGM_FI_DEV_GPU_UTIL) > 90
          for: 5m
          labels:
            severity: warning
            source: acmp
          annotations:
            summary: GPU 利用率连续5分钟超过90%
```

### 9.2 Alertmanager Webhook

Alertmanager 告警发生和恢复时回调：

```http
POST /internal/v1/monitoring/alert-events
```

ACMP 后端保存最小告警历史，监控告警页面再查询：

```http
GET /api/v1/monitoring/alert-events
```

详细请求和响应见
[20-MONITORING-OPERATIONS-MVP.md](20-MONITORING-OPERATIONS-MVP.md)。

## 10. ACMP 后端接入 Prometheus

### 10.1 连接地址

ACMP 与 Prometheus 在同一集群时，使用 ClusterIP DNS：

```text
http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090
```

Service 名称以实际安装结果为准。

ACMP 在集群外时，推荐通过内网 Ingress 或内网负载均衡访问，并配置 TLS 和认证。禁止将
Prometheus 9090 直接暴露到公网。

### 10.2 后端查询方式

后端调用 Prometheus HTTP API：

```http
GET /api/v1/query
GET /api/v1/query_range
```

前端只调用 ACMP 业务接口：

```http
GET /api/v1/monitoring/clusters
GET /api/v1/monitoring/clusters/{clusterId}
GET /api/v1/monitoring/deployments
GET /api/v1/monitoring/deployments/{deploymentId}
```

前端传递时间范围，后端使用固定 PromQL 模板，返回统一的 `summary + series`。完整协议见
[20-MONITORING-OPERATIONS-MVP.md](20-MONITORING-OPERATIONS-MVP.md)。

MVP 不允许前端把任意 PromQL 传给通用查询接口。只有管理员创建告警规则时可以提交一条
PromQL，并由后端单独校验。

## 11. 完整验收清单

### 11.1 Kubernetes 与主机

- [ ] kube-state-metrics Target 为 UP；
- [ ] 每个 Node 的 node-exporter Target 为 UP；
- [ ] Prometheus 能查询 Node CPU 和内存历史；
- [ ] Kubernetes Node Ready、Pod 和 Deployment 指标存在；
- [ ] Prometheus PVC 已绑定并能保留历史。

### 11.2 GPU

- [ ] GPU Worker 上 `nvidia-smi` 正常；
- [ ] 真实 GPU 能被 Kubernetes 调度；
- [ ] GPU Worker 上存在一个且只有一个 DCGM Exporter；
- [ ] DCGM ServiceMonitor 被 Prometheus 发现；
- [ ] GPU 利用率、显存、温度和功耗至少核心指标有值；
- [ ] 真实 GPU 负载运行时曲线发生合理变化。

### 11.3 vLLM

- [ ] vLLM `/metrics` 可以从 Service 访问；
- [ ] Service 端口具有名称；
- [ ] ServiceMonitor 与 Service Label 匹配；
- [ ] Prometheus Target 为 UP；
- [ ] 运行请求、等待请求和 Token Counter 可查询；
- [ ] 发送真实推理请求后 Token Counter 增长；
- [ ] 时间序列包含 ACMP Deployment 标识。

### 11.4 ACMP

- [ ] ACMP 后端能够访问 Prometheus HTTP API；
- [ ] 集群监控接口能返回真实 Node 和 GPU 序列；
- [ ] 推理服务监控接口能按 Deployment 过滤 vLLM 序列；
- [ ] 无 GPU UUID 关联时不冒充服务 GPU 数据；
- [ ] 告警规则能生成 PrometheusRule；
- [ ] Alertmanager Webhook 能保存 firing 和 resolved 事件；
- [ ] 前端不再显示“前端样例数据”。

## 12. 推荐安装顺序

严格按照以下顺序执行，便于逐层定位故障：

1. 校准 Master 与 GPU Worker 时间；
2. 验证 Kubernetes、StorageClass 和 PVC；
3. 验证 GPU 驱动、NVIDIA Container Toolkit 和 Device Plugin；
4. 安装 kube-prometheus-stack；
5. 验证 node-exporter 和 kube-state-metrics；
6. 安装且只安装一套 DCGM Exporter；
7. 运行真实 GPU 负载并验证 DCGM；
8. 部署 vLLM 并直接检查 `/metrics`；
9. 创建 vLLM ServiceMonitor；
10. 验证 Prometheus Targets 和 PromQL；
11. 配置 Alertmanager Webhook；
12. 实现 ACMP 后端监控接口；
13. 将 ACMP 前端样例曲线替换为真实接口数据。

## 13. 官方参考

- [Prometheus Operator](https://prometheus-operator.dev/)
- [kube-prometheus-stack Helm Chart](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
- [Kubernetes kube-state-metrics](https://kubernetes.io/docs/concepts/cluster-administration/kube-state-metrics/)
- [NVIDIA DCGM Exporter](https://docs.nvidia.com/datacenter/dcgm/latest/gpu-telemetry/dcgm-exporter.html)
- [vLLM Production Metrics](https://docs.vllm.ai/en/latest/usage/metrics/)
