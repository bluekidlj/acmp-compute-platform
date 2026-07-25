# Kubernetes Prometheus + DCGM Gpu 运维监控体系搭建

## 1. 文档目的

本文说明如何在具有 NVIDIA Gpu 的 Kubernetes 集群中搭建一套独立的运维监控体系。

第一阶段只建设集群监控，不接入 ACMP 业务代码。完成后应能够通过 Prometheus 和 Grafana
查看以下信息：

- Kubernetes 节点、Pod 和工作负载状态；
- 节点 CPU、内存、磁盘和网络使用情况；
- NVIDIA Gpu 使用率、显存、温度、功耗和健康状态；
- Gpu 指标的历史趋势；
- 基础的节点和 Gpu 告警。

本文采用以下组件：

| 组件 | 职责 |
| --- | --- |
| kube-prometheus-stack | 安装和管理 Prometheus、Grafana、Alertmanager 等组件 |
| Prometheus Operator | 通过 ServiceMonitor、PodMonitor 管理采集目标 |
| node-exporter | 采集节点 CPU、内存、磁盘和网络指标 |
| kube-state-metrics | 采集 Kubernetes 对象状态 |
| NVIDIA DCGM Exporter | 采集 NVIDIA 物理 Gpu 运行指标 |
| Grafana | 展示监控大盘 |
| Alertmanager | 接收并发送告警，第一阶段可以只部署不配置通知渠道 |

> 本文中的 Gpu 指 NVIDIA Gpu。DCGM Exporter 不能为没有真实 NVIDIA Gpu 的节点产生真实
> 利用率、显存、温度和功耗数据。

## 2. 总体架构

```text
Kubernetes API ───────────────→ kube-state-metrics ──┐
                                                     │
Linux Node ───────────────────→ node-exporter ───────┤
                                                     ├─→ Prometheus
NVIDIA Gpu ─→ DCGM ─→ DCGM Exporter ────────────────┘       │
                                                             ├─→ Grafana
                                                             └─→ Alertmanager
```

采集流程如下：

1. Exporter 在目标节点或 Pod 中暴露 `/metrics`；
2. Kubernetes Service 为 Exporter 提供稳定的发现入口；
3. ServiceMonitor 根据标签找到 Service；
4. Prometheus Operator 将 ServiceMonitor 转换成 Prometheus 采集配置；
5. Prometheus 定时拉取指标并保留历史数据；
6. Grafana 使用 Prometheus 作为数据源展示图表；
7. PrometheusRule 产生告警，Alertmanager 负责告警通知。

## 3. 实施边界

### 3.1 第一阶段包含

- 一套集群级 Prometheus；
- 一套 Grafana；
- Kubernetes 和 Linux 节点基础监控；
- 每个 Gpu 节点一个 DCGM Exporter Pod；
- 物理 Gpu 实时指标和历史趋势；
- 基础监控告警；
- Prometheus 数据持久化。

### 3.2 第一阶段不包含

- ACMP 后端查询 Prometheus；
- 多集群 Prometheus 联邦；
- Thanos、Mimir 等长期存储；
- 日志采集；
- 链路追踪；
- HAMi vGpu 容器级监控；
- vLLM 请求量、吞吐量和首 Token 延迟监控。

这些能力可以在基础监控稳定后逐项增加，不应阻塞第一阶段。

## 4. 前置条件

### 4.1 Kubernetes 集群

开始安装前，应确认：

- Kubernetes 集群运行正常；
- `kubectl` 能够连接目标集群；
- 安装账号有创建 Namespace、CRD、ClusterRole、DaemonSet 等资源的权限；
- 集群存在可用的 StorageClass；
- Gpu 节点运行 Linux；
- Gpu 节点已正确安装 NVIDIA 驱动；
- Kubernetes 已能识别 `nvidia.com/gpu` 扩展资源；
- Helm 3 已安装。

检查集群：

```bash
kubectl cluster-info
kubectl get nodes -o wide
kubectl get storageclass
```

检查 Kubernetes 是否已经识别 Gpu：

```bash
kubectl get nodes \
  -o custom-columns=NAME:.metadata.name,GPU:.status.allocatable.nvidia\\.com/gpu
```

如果所有节点的 `GPU` 都为空，先不要安装 DCGM Exporter，应先处理驱动、容器运行时、
NVIDIA Device Plugin 或 NVIDIA GPU Operator。

### 4.2 Gpu 节点检查

在每台 Gpu 节点上确认：

```bash
nvidia-smi
```

至少应正确显示：

- Gpu 型号；
- 驱动版本；
- Gpu 数量；
- 显存容量；
- 当前温度和功耗。

如果 `nvidia-smi` 本身失败，DCGM Exporter 也无法正常采集。

### 4.3 Docker Desktop 环境限制

当前 Windows Docker Desktop Kubernetes 可以验证：

- Prometheus Operator 和 CRD；
- Prometheus、Grafana、ServiceMonitor 生命周期；
- Kubernetes 对象状态；
- Windows 虚拟机内 Linux 节点的 CPU 和内存指标。

如果 Docker Desktop 所在环境没有向 Kubernetes 提供真实 NVIDIA Gpu，则不能验证：

- DCGM_FI_DEV_GPU_UTIL；
- 真实显存使用量；
- Gpu 温度和功耗；
- CUDA 工作负载与 Gpu 指标的对应关系。

模拟 `nvidia.com/gpu` 扩展资源只能验证平台资源发现，不能代替 DCGM 实测。

## 5. 安装方案选择

### 5.1 Prometheus

采用社区 Helm Chart `kube-prometheus-stack`。

它会统一安装：

- Prometheus Operator；
- Prometheus；
- Grafana；
- Alertmanager；
- node-exporter；
- kube-state-metrics；
- 默认监控规则和大盘。

### 5.2 DCGM Exporter

有两种可选方案。

#### 方案 A：已经使用 NVIDIA GPU Operator

优先使用 GPU Operator 自带的 DCGM Exporter。

优点：

- 驱动、Device Plugin 和 DCGM Exporter 的版本由 GPU Operator 统一管理；
- 默认以 DaemonSet 方式部署到 Gpu 节点；
- 可以直接启用 ServiceMonitor；
- 运维入口更少。

#### 方案 B：未使用 NVIDIA GPU Operator

单独安装 NVIDIA `dcgm-exporter` Helm Chart。

适用条件：

- 节点已经自行安装 NVIDIA 驱动；
- Device Plugin 已经独立安装并正常运行；
- 不准备让 GPU Operator 接管现有驱动。

同一个集群只保留一套 DCGM Exporter，禁止 GPU Operator 和独立 Chart 重复安装。

## 6. 安装 kube-prometheus-stack

### 6.1 添加 Helm 仓库

```bash
helm repo add prometheus-community \
  https://prometheus-community.github.io/helm-charts

helm repo update
```

### 6.2 创建配置文件

创建 `prometheus-values.yaml`：

```yaml
prometheus:
  prometheusSpec:
    retention: 15d
    scrapeInterval: 30s
    evaluationInterval: 30s

    # 允许发现其他 Namespace 中的 ServiceMonitor 和 PodMonitor。
    serviceMonitorNamespaceSelector: {}
    podMonitorNamespaceSelector: {}

    # 空选择器表示不额外限制 ServiceMonitor/PodMonitor 自身的标签。
    # 如果生产环境需要严格隔离，可在后续增加统一 release 标签。
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

注意：

- `50Gi` 是起步值，应根据节点数量、Gpu 数量、保留时间和采集频率调整；
- 如果集群没有默认 StorageClass，应在 PVC 中明确填写 `storageClassName`；
- 测试集群可以先使用较小容量；
- 生产环境必须使用持久卷，不能依赖 Pod 临时目录。

### 6.3 执行安装

```bash
kubectl create namespace monitoring

helm upgrade --install kube-prometheus-stack \
  prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --values prometheus-values.yaml
```

### 6.4 检查组件

```bash
kubectl get pods -n monitoring
kubectl get svc -n monitoring
kubectl get prometheus -n monitoring
kubectl get servicemonitor -A
```

主要 Pod 应为 `Running`：

- Prometheus Operator；
- Prometheus；
- Grafana；
- Alertmanager；
- kube-state-metrics；
- 每个节点上的 node-exporter。

Prometheus 和 Alertmanager 可能需要等待 PVC 绑定后才会启动。

## 7. 安装 DCGM Exporter

### 7.1 方案 A：通过 NVIDIA GPU Operator 启用

如果 GPU Operator 尚未安装，添加仓库：

```bash
helm repo add nvidia https://helm.ngc.nvidia.com/nvidia
helm repo update
```

创建 `gpu-operator-values.yaml`：

```yaml
dcgmExporter:
  enabled: true
  serviceMonitor:
    enabled: true
    interval: 15s
    honorLabels: false

  # 开启后，DCGM Exporter 可以把 Pod 信息映射到 Gpu 指标标签。
  # 这会增加其读取 Pod 的 RBAC 权限。
  enablePodLabels: true
```

如果节点驱动已经由人工或操作系统镜像安装，应避免 GPU Operator 重复安装驱动：

```yaml
driver:
  enabled: false
```

如果 NVIDIA Device Plugin 也已经独立安装，应根据现网情况决定是否关闭 GPU Operator
的 Device Plugin。不能让两套 Device Plugin 同时管理同一批 Gpu。

安装：

```bash
kubectl create namespace gpu-operator

helm upgrade --install gpu-operator \
  nvidia/gpu-operator \
  --namespace gpu-operator \
  --values gpu-operator-values.yaml
```

检查：

```bash
kubectl get pods -n gpu-operator -o wide
kubectl get servicemonitor -n gpu-operator
kubectl get svc -n gpu-operator
```

应在每台 Gpu 节点看到一个 DCGM Exporter Pod。

### 7.2 方案 B：独立安装 DCGM Exporter

仅当没有使用 GPU Operator 管理 DCGM Exporter 时使用本方案。

```bash
helm repo add nvidia https://helm.ngc.nvidia.com/nvidia
helm repo update

helm upgrade --install dcgm-exporter \
  nvidia/dcgm-exporter \
  --namespace monitoring \
  --set serviceMonitor.enabled=true
```

不同 Chart 版本的 values 字段可能发生变化，正式执行前必须确认当前 Chart：

```bash
helm show values nvidia/dcgm-exporter
```

不要仅凭示例字段直接覆盖生产配置。

检查：

```bash
kubectl get daemonset -n monitoring
kubectl get pods -n monitoring -o wide
kubectl get svc -n monitoring
kubectl get servicemonitor -n monitoring
```

### 7.3 验证 Exporter 原始指标

先找到 DCGM Exporter Service：

```bash
kubectl get svc -A | grep dcgm
```

临时端口转发，Service 名称和 Namespace 按实际结果替换：

```bash
kubectl port-forward \
  -n gpu-operator \
  service/nvidia-dcgm-exporter \
  9400:9400
```

另开终端：

```bash
curl http://127.0.0.1:9400/metrics
```

至少应能搜索到部分指标：

```text
DCGM_FI_DEV_GPU_UTIL
DCGM_FI_DEV_FB_USED
DCGM_FI_DEV_FB_FREE
DCGM_FI_DEV_GPU_TEMP
DCGM_FI_DEV_POWER_USAGE
```

实际指标集合取决于 Gpu、驱动、DCGM 和 DCGM Exporter 版本。某个非核心指标缺失时，
不应直接判定整个监控系统失败。

## 8. 验证 Prometheus 采集

### 8.1 访问 Prometheus

找到 Prometheus Service：

```bash
kubectl get svc -n monitoring | grep prometheus
```

端口转发：

```bash
kubectl port-forward \
  -n monitoring \
  service/kube-prometheus-stack-prometheus \
  9090:9090
```

浏览器访问：

```text
http://127.0.0.1:9090
```

### 8.2 检查 Targets

在 Prometheus 页面进入：

```text
Status → Targets
```

确认以下目标为 `UP`：

- kube-state-metrics；
- node-exporter；
- kubelet；
- DCGM Exporter。

也可以使用查询检查 DCGM 采集目标：

```promql
up{job=~".*dcgm.*"}
```

返回值：

- `1`：Prometheus 能正常采集；
- `0`：目标已发现，但采集失败；
- 无结果：ServiceMonitor 没有被 Prometheus 选中，或标签匹配错误。

### 8.3 检查 Gpu 指标

Gpu 使用率：

```promql
DCGM_FI_DEV_GPU_UTIL
```

显存已用量：

```promql
DCGM_FI_DEV_FB_USED
```

显存占用率：

```promql
100 *
DCGM_FI_DEV_FB_USED
/
(
  DCGM_FI_DEV_FB_USED
  +
  DCGM_FI_DEV_FB_FREE
)
```

温度：

```promql
DCGM_FI_DEV_GPU_TEMP
```

功耗：

```promql
DCGM_FI_DEV_POWER_USAGE
```

集群平均 Gpu 使用率：

```promql
avg(DCGM_FI_DEV_GPU_UTIL)
```

按节点统计平均 Gpu 使用率：

```promql
avg by (Hostname) (DCGM_FI_DEV_GPU_UTIL)
```

标签名应以 Prometheus 中的真实序列为准。常见标签可能包括：

- `UUID`；
- `gpu`；
- `Hostname`；
- `modelName`；
- `namespace`；
- `pod`；
- `container`。

不能假设所有 DCGM Exporter 版本都具有完全相同的标签。

## 9. 配置 Grafana

### 9.1 获取管理员密码

```bash
kubectl get secret \
  -n monitoring \
  kube-prometheus-stack-grafana \
  -o jsonpath="{.data.admin-password}" | base64 --decode
```

用户名通常为：

```text
admin
```

### 9.2 访问 Grafana

```bash
kubectl port-forward \
  -n monitoring \
  service/kube-prometheus-stack-grafana \
  3000:80
```

浏览器访问：

```text
http://127.0.0.1:3000
```

`kube-prometheus-stack` 通常已经配置好 Prometheus 数据源。

### 9.3 建议的大盘

第一阶段建议建立三个大盘。

#### 集群总览

- 节点总数和异常节点数；
- CPU、内存整体使用率；
- Pod 总数和异常 Pod；
- Gpu 总数；
- 当前活跃 Gpu 数；
- 集群平均 Gpu 使用率；
- 集群平均显存占用率。

#### Gpu 节点大盘

- 每个节点的 Gpu 数量；
- 每张 Gpu 的使用率；
- 每张 Gpu 的显存已用和总量；
- 温度；
- 功耗；
- XID 错误或其他可用健康指标；
- 最近 1 小时、24 小时和 7 天趋势。

#### Gpu 工作负载大盘

只有 DCGM Exporter 正确映射 Kubernetes Pod 标签后才展示：

- Namespace；
- Pod；
- Container；
- 使用的 Gpu UUID；
- Gpu 使用率；
- 显存使用量。

如果某个版本无法提供 Pod 映射标签，先保留节点和物理 Gpu 维度，不应阻塞整套监控上线。

## 10. 基础告警

创建 `gpu-alert-rules.yaml`：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: gpu-basic-alerts
  namespace: monitoring
  labels:
    release: kube-prometheus-stack
spec:
  groups:
    - name: gpu.rules
      rules:
        - alert: DcgmExporterDown
          expr: up{job=~".*dcgm.*"} == 0
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "DCGM Exporter 无法采集"
            description: "目标 {{ $labels.instance }} 已连续 5 分钟不可用。"

        - alert: GpuTemperatureHigh
          expr: DCGM_FI_DEV_GPU_TEMP > 85
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Gpu 温度过高"
            description: "Gpu {{ $labels.UUID }} 温度已连续 10 分钟超过 85°C。"

        - alert: GpuMemoryNearlyFull
          expr: |
            100 * DCGM_FI_DEV_FB_USED
            /
            (DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE)
            > 95
          for: 15m
          labels:
            severity: warning
          annotations:
            summary: "Gpu 显存接近耗尽"
            description: "Gpu {{ $labels.UUID }} 显存占用率已连续 15 分钟超过 95%。"
```

应用：

```bash
kubectl apply -f gpu-alert-rules.yaml
```

阈值必须结合具体机型、机房散热和业务负载调整。特别是温度阈值，不同 Gpu 型号不能
长期使用完全相同的经验值。

第一阶段可先在 Prometheus 的 Alerts 页面验证告警规则，不必立即接入短信、邮件或企业微信。

## 11. 内网环境部署

内网环境仍然可以连接 Kubernetes，但集群节点可能无法访问公网镜像仓库和 Helm 仓库。
因此需要在有公网访问能力的机器提前准备 Chart 和镜像。

### 11.1 下载 Helm Chart

```bash
helm pull prometheus-community/kube-prometheus-stack \
  --untar=false

helm pull nvidia/gpu-operator \
  --untar=false
```

如果使用独立 DCGM Exporter：

```bash
helm pull nvidia/dcgm-exporter \
  --untar=false
```

将下载的 `.tgz` 文件复制到内网运维机。

内网安装时直接使用本地 Chart：

```bash
helm upgrade --install kube-prometheus-stack \
  ./kube-prometheus-stack-<version>.tgz \
  --namespace monitoring \
  --create-namespace \
  --values prometheus-values.yaml
```

### 11.2 收集镜像清单

不要人工猜测 Chart 使用的镜像。应根据最终 values 渲染清单：

```bash
helm template kube-prometheus-stack \
  ./kube-prometheus-stack-<version>.tgz \
  --namespace monitoring \
  --values prometheus-values.yaml \
  > kube-prometheus-rendered.yaml
```

对 GPU Operator 或 DCGM Exporter 执行同样操作，然后从渲染结果中提取所有 `image:`。

必须包含直接和间接使用的镜像，例如：

- Prometheus Operator；
- Prometheus；
- Alertmanager；
- Grafana；
- kube-state-metrics；
- node-exporter；
- config-reloader；
- admission webhook；
- DCGM Exporter；
- GPU Operator 相关组件。

### 11.3 同步到内网镜像仓库

推荐流程：

```text
公网仓库拉取
  → 扫描镜像和记录摘要
  → 推送至内网 Harbor
  → 修改 Helm values 的 registry/repository
  → 在测试 Namespace 预拉取
  → 正式安装
```

镜像应固定明确版本，生产环境不要使用 `latest`。

Chart 和镜像必须作为同一个版本包归档，至少记录：

- Chart 名称和版本；
- 每个镜像的完整名称和标签；
- 镜像 digest；
- 自定义 values；
- 安装时间；
- Kubernetes、驱动和 Gpu 型号；
- 回滚版本。

### 11.4 内网时间同步

Prometheus 是时序数据库，所有 Kubernetes 节点必须保持时间同步。如果节点时间偏差过大，
会导致图表错位、数据延迟或查询结果异常。

## 12. 容量与保留策略

第一阶段建议：

| 项目 | 建议值 |
| --- | --- |
| 基础采集间隔 | 30 秒 |
| Gpu 采集间隔 | 15～30 秒 |
| Prometheus 保留时间 | 15 天 |
| Prometheus 初始存储 | 50 GiB |
| Grafana 存储 | 10 GiB |

实际容量与以下因素相关：

- 节点数量；
- Gpu 数量；
- 每个指标的标签数量；
- Pod 变化频率；
- 采集间隔；
- 保留时间；
- 是否启用 Pod 标签映射。

标签维度越多，时序数量越大。禁止把请求 ID、用户输入等高基数字段作为 Prometheus 标签。

## 13. 安全要求

- Prometheus、Grafana 和 Alertmanager 默认只提供 ClusterIP；
- 运维访问优先通过内网 Ingress、VPN、堡垒机或临时 port-forward；
- 不要直接将 Prometheus 9090 暴露到公网；
- Grafana 必须修改默认管理员密码；
- Ingress 应配置 TLS 和身份认证；
- ServiceMonitor 只选择受控 Namespace 和标签；
- 告警通知中的凭证使用 Kubernetes Secret 保存；
- 为 Prometheus 设置必要但不过量的 RBAC；
- 对 Prometheus PVC 和 Grafana 配置制定备份策略。

## 14. 常见故障排查

### 14.1 Prometheus 没有发现 DCGM Exporter

依次检查：

```bash
kubectl get servicemonitor -A
kubectl get svc -A | grep dcgm
kubectl get endpoints -A | grep dcgm
kubectl get prometheus -n monitoring -o yaml
```

重点核对：

- ServiceMonitor 的 `selector` 是否匹配 Service 标签；
- `namespaceSelector` 是否包含 DCGM 所在 Namespace；
- endpoint 的 `port` 是否是 Service 端口名称，而不是数字；
- Prometheus 的 `serviceMonitorSelector` 是否选中该 ServiceMonitor。

### 14.2 Target 存在但状态为 DOWN

检查：

```bash
kubectl logs -n <namespace> <dcgm-exporter-pod>
kubectl describe pod -n <namespace> <dcgm-exporter-pod>
```

并确认：

- Exporter Pod 为 Running；
- Service Endpoints 不为空；
- `/metrics` 可以访问；
- NetworkPolicy 没有阻止 Prometheus；
- 指标端口与 ServiceMonitor 一致。

### 14.3 DCGM Exporter Pod 启动失败

检查：

- 节点 `nvidia-smi` 是否正常；
- NVIDIA 驱动与 DCGM Exporter 是否兼容；
- 容器运行时是否配置 NVIDIA Runtime；
- Pod 是否调度到真实 Gpu 节点；
- 是否重复部署两套 DCGM；
- 是否存在权限或设备挂载错误。

### 14.4 能看到指标，但没有 Pod 标签

检查：

- DCGM Exporter 是否启用 Kubernetes 映射；
- 是否能读取 Kubelet PodResources API；
- `enablePodLabels` 是否启用；
- ServiceAccount 是否有读取 Pod 的权限；
- 当前 Gpu 分配方式是否受该版本支持。

Pod 映射失败不影响物理 Gpu 指标采集，第一阶段可先上线节点和 Gpu 维度。

### 14.5 Prometheus Pod Pending

通常是存储问题：

```bash
kubectl get pvc -n monitoring
kubectl describe pvc -n monitoring <pvc-name>
kubectl get storageclass
```

确认 StorageClass 存在、支持动态供给，并能满足访问模式和容量。

## 15. 验收步骤

### 15.1 基础组件

- [ ] Prometheus Operator 正常运行；
- [ ] Prometheus 正常运行且 PVC 已绑定；
- [ ] Grafana 正常运行；
- [ ] Alertmanager 正常运行；
- [ ] kube-state-metrics Target 为 UP；
- [ ] 所有节点的 node-exporter Target 为 UP。

### 15.2 Gpu 采集

- [ ] 每台 Gpu 节点存在一个 DCGM Exporter Pod；
- [ ] DCGM Exporter `/metrics` 可以访问；
- [ ] DCGM Exporter Target 为 UP；
- [ ] `DCGM_FI_DEV_GPU_UTIL` 有真实数据；
- [ ] `DCGM_FI_DEV_FB_USED` 有真实数据；
- [ ] `DCGM_FI_DEV_GPU_TEMP` 有真实数据；
- [ ] 指标中能够区分节点、Gpu 编号或 Gpu UUID。

### 15.3 真实负载验证

在一张 Gpu 上运行经过批准的 CUDA 或推理测试负载，观察：

- [ ] Gpu 使用率随负载明显升高；
- [ ] 显存使用量随模型或测试程序加载而升高；
- [ ] 负载结束后使用率和显存回落；
- [ ] Prometheus 能查看完整历史曲线；
- [ ] Grafana 图表与 `nvidia-smi` 同期观察结果基本一致。

不能仅凭 Prometheus 中出现指标名称就判断监控验收通过，必须执行一次真实 Gpu 负载。

### 15.4 持久化验证

- [ ] 重启 Prometheus Pod 后历史数据仍然存在；
- [ ] 重启 Grafana Pod 后数据源和大盘仍然存在；
- [ ] 数据保留时间符合配置；
- [ ] PVC 容量和增长速度处于预期范围。

## 16. 后续扩展顺序

基础监控稳定后，建议按以下顺序扩展：

1. 增加 HAMi vGpu 宿主和容器级指标；
2. 为 vLLM 推理服务创建 ServiceMonitor 或 PodMonitor；
3. 建立租户、项目、模型和推理服务维度的监控视图；
4. 配置企业微信、邮件或其他 Alertmanager 通知；
5. 由 ACMP 后端通过 Prometheus HTTP API 查询聚合数据；
6. 多集群数量和历史保留需求明确后，再评估 Thanos 或 Mimir。

不要在单集群基础采集尚未稳定时提前引入多集群聚合和长期存储。

## 17. 官方参考

- Prometheus Operator：
  <https://prometheus-operator.dev/docs/getting-started/introduction/>
- Prometheus Operator ServiceMonitor：
  <https://prometheus-operator.dev/docs/developer/getting-started/>
- NVIDIA DCGM Exporter：
  <https://docs.nvidia.com/datacenter/dcgm/latest/gpu-telemetry/dcgm-exporter.html>
- NVIDIA GPU Operator：
  <https://docs.nvidia.com/datacenter/cloud-native/gpu-operator/latest/getting-started.html>
- NVIDIA Gpu 监控数据源：
  <https://docs.nvidia.com/enterprise-reference-architectures/observability-guide/latest/configuration-of-data-sources.html>
