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
./01-download-monitoring-bundle.sh
```

产物在项目根目录：

```text
acmp-monitoring-offline-bundle.tar.gz
```

## 内网导入

把离线包放到每台 K8s 节点上，执行：

```bash
tar -xzf acmp-monitoring-offline-bundle.tar.gz
cd acmp-monitoring-offline-bundle
sudo ./scripts/02-install-monitoring-offline.sh --load-images
```

每台节点都需要导入镜像。因为没有 Harbor，Pod 会从本机 containerd 镜像缓存启动。

## Master 安装

只在 Master 上执行：

```bash
cd acmp-monitoring-offline-bundle
sudo ./scripts/02-install-monitoring-offline.sh --install
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
