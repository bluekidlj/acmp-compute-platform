# ACMP 监控离线安装脚本

## 场景

- 内网 Kubernetes 使用 containerd；
- 不使用 Harbor；
- ACMP 后端部署在集群外其他主机；
- 监控组件安装在 Kubernetes 集群内；
- Prometheus 通过 NodePort `30090` 暴露给 ACMP 后端访问。

## 外网打包

```bash
cd scripts/monitoring-offline
chmod +x ./*.sh
./01-download-monitoring-bundle-cn.sh
```

产物在项目根目录：

```text
acmp-monitoring-offline-bundle-cn.tar.gz
```

## 内网导入

把离线包放到每台 K8s 节点上，执行：

```bash
tar -xzf acmp-monitoring-offline-bundle-cn.tar.gz
cd acmp-monitoring-offline-bundle-cn
sudo ./scripts/02-install-monitoring-offline.sh --load-images
```

每台节点都需要导入镜像。因为没有 Harbor，Pod 会从本机 containerd 镜像缓存启动。

如果离线包是平铺目录结构，也可以直接在解压目录执行 `02-install-monitoring-offline.sh`，脚本会自动寻找 `images/`、`charts/` 和 `values/`。

如果你已经有主离线包，但只想补 GPU Operator / DCGM 的缺失镜像，可以单独执行：

```bash
chmod +x ./04-download-gpu-missing-components.sh
./04-download-gpu-missing-components.sh
```

生成的 `gpu-missing-components.tar.gz` 可以和主离线包放在一起，`02-install-monitoring-offline.sh --load-images` 会自动一起导入。

## 关闭 MIG Manager 和 Profiling

这套离线包默认按“只做基础 GPU 监控，不做 MIG 切分和 profiling”的方式安装。

对应配置在：

- [`scripts/monitoring-offline/values/gpu-operator-values.yaml`](./values/gpu-operator-values.yaml)

默认值是：

```yaml
migManager:
  enabled: false

dcgmExporter:
  env:
    - name: DCGM_EXPORTER_COLLECTORS
      value: /etc/dcgm-exporter/default-counters.csv
```

含义很简单：

- `migManager.enabled=false`：不让 GPU Operator 去管理 MIG 切分
- `DCGM_EXPORTER_COLLECTORS=/etc/dcgm-exporter/default-counters.csv`：不用 `dcp-metrics-included.csv`，避免 profiling module 报错

如果你现在已经有旧的离线包，想快速修复，可以直接把这个 values 文件拷进去，然后重新执行：

```bash
sudo ./scripts/02-install-monitoring-offline.sh --install-gpu-operator
```

如果是重新打包，直接先改这个 values 文件，再跑 `01` 脚本，新的离线包会自动带上这个默认配置。

`04` 脚本默认使用国内镜像优先模式。GPU Operator v25.3.0 的 Validator
会依次尝试国内 Docker Hub 代理和 Docker Hub 上的等价镜像，校验 linux/amd64
Image ID 后重新标记为：

```text
nvcr.io/nvidia/cloud-native/gpu-operator-validator:v25.3.0
```

该等价镜像的 manifest digest 与 NVIDIA 官方镜像一致。不要将 Validator
单独降级为国内站已有的 `v23.6.0`，它与当前 GPU Operator Chart 版本不匹配。

如果所在网络有自己的 Docker Hub 加速配置，也可以直接执行：

```bash
docker pull giantswarm/gpu-operator-validator:v25.3.0
docker image inspect --format '{{.Id}}' giantswarm/gpu-operator-validator:v25.3.0
docker tag giantswarm/gpu-operator-validator:v25.3.0 \
  nvcr.io/nvidia/cloud-native/gpu-operator-validator:v25.3.0
```

linux/amd64 的期望 Image ID 为：

```text
sha256:7e44a407c823370301701efdffd676701a05c91af2a4f954ddb0aa4bb9ab6682
```

需要强制只使用 NVIDIA 原始仓库时：

```bash
MIRROR_MODE=original PULL_RETRIES=5 ./04-download-gpu-missing-components.sh
```

如果这次导入后仍有 Pod 处于 `ImagePullBackOff`，在内网 Master 执行诊断：

```bash
chmod +x ./05-diagnose-missing-images.sh
sudo ./05-diagnose-missing-images.sh
```

诊断脚本会扫描所有 namespace 的 Pod、InitContainer 和 Kubernetes Events，生成一个报告目录，其中：

- `missing-images.txt`：当前失败且本机 containerd 未发现的镜像；
- `download-missing-images.sh`：复制到外网后可直接 pull/save/打包的脚本；
- `pod-failures.txt`：失败 Pod、所在节点和容器；
- `report.txt`：汇总报告。

外网下载有两种方式：

```bash
# 方式一：直接使用诊断脚本生成的清单
./04-download-gpu-missing-components.sh --images-file missing-images.txt

# 方式二：把诊断目录中的脚本和清单放在一起
./download-missing-images.sh
```

新生成的 `missing-images-*.tar.gz` 放回内网离线包的 `images/` 目录，或放在 `02-install-monitoring-offline.sh` 能找到的 bundle `images/` 目录中，再执行：

```bash
sudo ./02-install-monitoring-offline.sh --load-images
sudo ./02-install-monitoring-offline.sh --verify-images
```

也可以不解压，把 `gpu-missing-components*.tar.gz` 或 `missing-images*.tar.gz` 直接放在 `02-install-monitoring-offline.sh` 当前目录；脚本会自动临时解压并导入。

注意：`ctr` 只代表执行命令的那台机器。Pod 如果调度到多个节点，每台节点都要导入同一个补充 tar；诊断报告会记录缺失 Pod 所在节点。

当前这版 GPU Operator v25.3.0 的补充镜像清单建议如下：

- `nvcr.io/nvidia/cloud-native/gpu-operator-validator:v25.3.0`
- `nvcr.io/nvidia/k8s-device-plugin:v0.17.1`（你的 chart 中 `gpu-feature-discovery` 容器实际使用这个镜像）
- `nvcr.io/nvidia/k8s/dcgm-exporter:4.1.1-4.0.4-ubuntu22.04`
- `quay.io/prometheus-operator/prometheus-config-reloader:v0.77.2`

注意：`gpu-feature-discovery` 是 Pod 里的容器名，不一定是镜像仓库名。你当前看到的实际镜像是 `nvcr.io/nvidia/k8s-device-plugin:v0.17.1`，不要再拉 `nvcr.io/nvidia/cloud-native/gpu-feature-discovery:v0.17.1`，这个地址会返回 `Access Denied`。后续如果还有新缺失镜像，按 `kubectl describe pod` / `05-diagnose-missing-images.sh` 的实际结果补齐。

如果 `nvidia-dcgm-exporter` 日志里出现 `The third-party Profiling module returned an unrecoverable error`，通常就是 collectors 还在走 profiling 路线。先确认 `values/gpu-operator-values.yaml` 里已经是 `default-counters.csv`，不要再用 `dcp-metrics-included.csv`。

## Master 安装

只在 Master 上执行：

```bash
cd acmp-monitoring-offline-bundle-cn
sudo ./scripts/02-install-monitoring-offline.sh --install-stack
sudo ./scripts/02-install-monitoring-offline.sh --install-gpu-operator
./scripts/03-verify-monitoring.sh
```

## ACMP 接入

ACMP 后端配置：

```text
PROMETHEUS_URL=http://<任一K8s节点内网IP>:30090
```

然后重启 ACMP 后端。

## 说明

fake-gpu 只能验证 GPU 资产和调度，不能产生真实 DCGM 利用率、显存、温度和功耗指标。
真实 V100 节点需要先保证 `nvidia-smi` 正常，再安装 GPU Operator。

## 常见问题

- 如果 `ctr import` 报 `content digest ... not found`，通常说明镜像包损坏或导出链路不完整，建议重新生成离线包。
- 如果 `gpu-operator` 相关 Pod 继续拉 `gpu-operator-validator` 或 `prometheus-config-reloader`，说明离线包版本和 chart 依赖不一致，先重新生成离线包，再执行 `--verify-images`。
- 如果 `helm upgrade --install gpu-operator` 报 `spec.selector ... field is immutable`，先卸载旧的 `gpu-operator` 再单独执行 `--install-gpu-operator`。
- 如果以后要重新启用 MIG，把 `migManager.enabled` 改回 `true`，但只适合明确需要 MIG 切分的节点。
- 如果 `kwok-controller` 被 `Evicted`，优先检查节点磁盘压力和 `/var/lib/containerd`、`/var/lib/kubelet` 占用。
