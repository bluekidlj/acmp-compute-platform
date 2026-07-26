# ACMP Linux Kubernetes Demo 集群脚本

## 固定版本和范围

- Ubuntu 22.04/24.04 x86_64
- containerd 1.7.27
- Kubernetes 1.28.15
- runc 1.2.6
- CNI plugins 1.6.2
- crictl 1.28.0
- Flannel 0.25.7
- 1 台 Master + 1 台或多台 Worker

Kubernetes 1.28 已结束上游维护，仅用于与当前 ACMP Demo 固定版本联调，不建议用于生产。

## 网络源

- Ubuntu 软件包：阿里云镜像。
- Kubernetes Debian 包：阿里云 `kubernetes-new` 镜像。
- kubeadm 核心镜像：`registry.aliyuncs.com/google_containers`。
- containerd、runc、CNI、crictl、Flannel、KWOK：固定版本的上游 GitHub Release。

大陆网络无法直接下载 GitHub 时，可以设置可信代理前缀，例如：

```bash
export GITHUB_PROXY='https://你的可信代理地址/'
```

代理必须支持形成 `${GITHUB_PROXY}https://github.com/...` 的完整地址。脚本不内置第三方 GitHub 代理，避免二进制供应链风险。

## Master

把整个 `linux-k8s` 目录复制到 Master：

```bash
chmod +x ./*.sh
sudo NODE_NAME=master-01 ./10-init-master.sh
```

多网卡时明确指定 Kubernetes API 地址：

```bash
sudo NODE_NAME=master-01 \
  APISERVER_ADVERTISE_ADDRESS=192.168.10.10 \
  ./10-init-master.sh
```

安装完成后会生成：

```text
/root/acmp-worker-join.sh
/etc/kubernetes/admin.conf
```

ACMP 注册集群时使用 `/etc/kubernetes/admin.conf`。如果 ACMP 后端不在 Master 上，确保 kubeconfig 中的 API 地址是 ACMP 后端可访问的 Master IP，不能是 `127.0.0.1`。

## Worker

在新 Worker 主机上先把基础环境装好，然后从 Master 查看 join 命令：

```bash
sudo cat /root/acmp-worker-join.sh
```

在 Worker 执行：

```bash
chmod +x ./*.sh
sudo NODE_NAME=gpu-worker-01 \
  JOIN_COMMAND='kubeadm join 192.168.10.10:6443 --token ... --discovery-token-ca-cert-hash sha256:...' \
  ./20-join-worker.sh
```

回到 Master：

```bash
kubectl get nodes -o wide
```

等待 Master 和 Worker 都为 `Ready`。

两台虚拟机应使用固定 IP，主机名必须不同，并确保彼此能访问 Master 的 TCP 6443 端口。脚本不会修改虚拟机网卡和防火墙，避免误改宿主网络。

## 给 Worker 启用 Fake GPU

Fake GPU Operator 是集群级组件，应在持有管理员 kubeconfig 的 Master 执行：

```bash
sudo ./30-enable-fake-gpu-worker.sh <worker-node-name>
```

默认模拟 2 张 Tesla V100 16GB。自定义示例：

```bash
sudo GPU_COUNT=4 \
  GPU_PRODUCT=NVIDIA-TESLA-V100-SXM2-32GB \
  GPU_MEMORY_MIB=32768 \
  ./30-enable-fake-gpu-worker.sh gpu-worker-01
```

之后在 ACMP 点击集群同步，应看到：

- Master GPU 数为 0。
- 指定 Worker GPU 数为配置值。
- 品牌为 NVIDIA，型号和显存来自 Node 标签/注解。

Fake GPU 只暴露 `nvidia.com/gpu`，适合验证独享池。它不提供真实 CUDA，也不提供 HAMi 的 `nvidia.com/gpucores`、`nvidia.com/gpumem-percentage`，因此不要用它验证真实 vLLM 或共享切分。

## 重复执行与清理

公共安装脚本和 Master/Worker 脚本会检查主要状态，可用于安装失败后的再次执行。不要在已有正式 Kubernetes 集群的机器上运行。

如需彻底重建节点，应先执行 `kubeadm reset` 并自行确认需要清理的 CNI 配置和数据；这些脚本不会自动删除已有集群，避免误清理。
