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
- 如果 `kwok-controller` 被 `Evicted`，优先检查节点磁盘压力和 `/var/lib/containerd`、`/var/lib/kubelet` 占用。
