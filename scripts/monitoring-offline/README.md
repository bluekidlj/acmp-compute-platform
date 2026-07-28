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
