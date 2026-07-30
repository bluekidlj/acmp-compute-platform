# 运行环境与集群前置条件

本文说明一个 Kubernetes 集群在接入 ACMP 前应具备的基础条件。版本以当前已经验证通过的环境为基线，目标是减少内网环境中的兼容性变量。

## 1. 推荐版本基线

| 组件 | 推荐版本 | 作用 |
|---|---:|---|
| Kubernetes | 1.28.15 | 集群编排与 Kubernetes API |
| containerd | 1.7.27 | 容器运行时与 CRI 实现 |
| runc | 1.2.6 | OCI 容器运行时 |
| CNI Plugins | 1.6.2 | 容器网络基础插件 |
| Flannel | 0.25.7 | 当前验证环境使用的集群网络 |
| crictl | 1.28.x | containerd/CRI 排查工具 |
| Java | 11 | ACMP 后端运行环境 |
| Nginx | 1.24 或兼容版本 | 前端静态资源和 API 反向代理 |

Kubernetes 1.28.15 与 containerd 1.7.27 是当前平台实际验证基线。新环境优先保持这一组合；升级 Kubernetes、Kubernetes Java Client 或 GPU Operator 时，应先在测试集群验证 API 和镜像依赖。

## 2. Kubernetes 集群条件

接入前至少满足以下条件：

- 集群 API Server 可从 ACMP 后端所在主机访问。
- 每个 Node 的主机名和 Internal IP 唯一。
- Node 状态为 `Ready`，containerd 与 kubelet 正常。
- kubeconfig 中的 API Server 地址不是 `127.0.0.1`，而是 ACMP 可达的地址。
- kubeconfig 对目标集群具有平台所需的读取和修改权限。

ACMP 主要通过 Kubernetes API 完成：

- 读取 Node、Pod、Namespace 和资源容量；
- 修改 Node 调度标签；
- 读取和更新 HAMi ConfigMap；
- 刷新 HAMi device-plugin Pod；
- 创建和删除推理服务的 Deployment、Service 等对象。

本项目提供的测试集群脚本位于 `scripts/linux-k8s`，具体使用方式见 `scripts/linux-k8s/README.md`。

## 3. GPU 节点条件

真实 NVIDIA GPU 节点应先保证：

1. 操作系统能够识别 GPU；
2. `nvidia-smi` 正常返回型号、驱动和显存；
3. containerd 已配置 NVIDIA 容器运行能力；
4. Kubernetes 能看到 GPU 设备资源；
5. 需要共享 GPU 时，HAMi 已正常运行。

GPU 是否被 Kubernetes 上报，与服务器上能否执行 `nvidia-smi` 是两个层次。只有驱动、容器运行时和设备插件链路完整，Kubernetes Node 的 Capacity/Allocatable 中才会出现对应设备资源。

GPU 型号、驱动和 CUDA 等详细信息可能来自 GPU Feature Discovery 或 HAMi 注解。缺少这些扩展元数据不会阻止基础独享流程，但页面中的设备详情可能不完整。

## 4. HAMi 前置条件

当前正式约定是：需要纳入平台管理的 GPU 节点安装 HAMi，HAMi 统一部署在：

```text
Namespace: hami-system
ConfigMap: hami-device-plugin
```

平台的职责是为目标 Node 写入节点级切分配置；真正的设备切分、资源上报和调度由 HAMi 完成。

行为约定：

- 独享池：使用整卡资源；开发测试环境未安装 HAMi 时仍允许验证独享流程。
- 共享池：必须检测到 `hami-system/hami-device-plugin`，否则拒绝加入。
- 同一 Node 上的全部 GPU 使用同一种池类型和同一个切分比例。
- 删除共享规格或重新入池时，平台清理旧 HAMi 节点配置和 ACMP 标签。

详细规则见 [算力资源管理](03-COMPUTE-RESOURCE-MANAGEMENT.md) 和 [HAMi 节点级共享设计](27-HAMI-NODE-SHARING-MVP.md)。

## 5. 监控组件条件

完整监控链路由以下组件组成：

| 组件 | 采集内容 |
|---|---|
| Prometheus | 指标存储与 PromQL 查询入口 |
| Node Exporter | CPU、内存、磁盘、网络、系统负载 |
| kube-state-metrics | Kubernetes 对象和副本状态 |
| DCGM Exporter | NVIDIA GPU 利用率、显存、温度、功耗等 |
| vLLM `/metrics` | 推理吞吐、请求、Token、延迟和队列指标 |
| Alertmanager | 告警路由与通知，可按需启用 |

ACMP 后端通过环境变量连接 Prometheus：

```text
PROMETHEUS_URL=http://<Kubernetes节点IP>:30090
```

离线安装脚本位于 `scripts/monitoring-offline`。无 Harbor 时，需要在可能运行监控 Pod 的每台 Kubernetes Node 上将镜像导入 containerd 的 `k8s.io` namespace。

## 6. ACMP 应用主机条件

ACMP 可以部署在 Kubernetes 集群外的独立 Linux 主机，只要它能够访问：

- Kubernetes API Server；
- Prometheus 暴露地址；
- 需要调用的推理服务地址；
- 数据库文件或后续替换的外部数据库。

Linux 主机至少安装 Java 11 和 Nginx。构建机还需要 Maven、Node.js 和 npm；运行机使用已经打好的发布包时不需要 Maven 和 Node.js。

## 7. 内网部署建议

内网环境建议将依赖分成三类准备：

- 应用发布包：后端 Jar、前端静态文件、配置和启动脚本；
- Kubernetes 组件包：Helm Chart、values 和安装脚本；
- 容器镜像包：监控组件、GPU 组件、vLLM 或 Demo 镜像。

镜像导入成功不代表所有 Node 都已具备镜像。没有内部镜像仓库时，应在每个可能调度目标上执行导入，并使用 `crictl images` 或 `ctr -n k8s.io images ls` 验证。

## 8. 接入前检查

注册集群前建议依次确认：

```bash
kubectl get nodes -o wide
kubectl describe node <gpu-node>
kubectl get pods -n hami-system -o wide
kubectl get configmap -n hami-system hami-device-plugin
kubectl get pods -n monitoring -o wide
```

若使用 NVIDIA GPU，再确认：

```bash
nvidia-smi
kubectl get node <gpu-node> -o jsonpath='{.status.capacity}'
```

这些检查通过后，再在 ACMP 中注册集群并执行同步。
