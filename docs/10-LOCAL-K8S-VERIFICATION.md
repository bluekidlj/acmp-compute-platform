# Windows 本地 Kubernetes 验证

## 当前环境

- Docker Desktop Kubernetes
- Context：`docker-desktop`
- 单节点：`desktop-control-plane`
- Kubernetes：`v1.31.14`
- 本地无真实 GPU 和 HAMi

当前直接使用 Docker Desktop 自带的 Kubernetes，不再额外创建 kind 集群。
原因是两者都能验证 Kubernetes API 对象生命周期，而当前 Docker Desktop
只分配约 2GB 内存，同时运行两套控制面没有必要。

## 能验证的内容

- kubeconfig 连接和 Node 查询；
- Node CPU、内存、标签和扩展资源解析；
- Namespace、Deployment 和 Service 创建；
- Deployment Ready 状态查询；
- 自定义容器端口、Service 端口和访问 URL；
- Kubernetes 对象删除；
- 平台业务数据、规格和租户配额流程。

## 不能替代的真实环境验收

- NVIDIA Device Plugin；
- HAMi 显存和算力隔离；
- CUDA 设备注入；
- vLLM 真实 GPU 推理；
- GPU 占用和释放。

## 模拟 GPU

执行：

```powershell
.\scripts\local-k8s\setup-mock-gpu.ps1
```

默认向 `desktop-control-plane` 发布：

```text
nvidia.com/gpu.product=NVIDIA-A100-SXM4-80GB
nvidia.com/gpu=8
```

这是 Kubernetes 扩展资源模拟，只用于平台的 Node/GPU 发现和调度对象验证，
不会产生真实 CUDA 设备。

## 验证 Kubernetes 对象生命周期

执行：

```powershell
.\scripts\local-k8s\verify-lifecycle.ps1
```

脚本会：

1. 创建 `acmp-test-lifecycle` Namespace；
2. 使用集群已有的 `pause:3.10` 镜像创建 Deployment；
3. 创建端口为 `9000` 的 Service；
4. 等待 Deployment Ready；
5. 校验副本和端口；
6. 删除测试 Namespace。

成功输出：

```text
K8S_LIFECYCLE_OK readyReplicas=1 port=9000
```

## 平台接入

集群注册时使用当前 kubeconfig：

```text
C:\Users\jiang\.kube\config
```

平台会通过 Kubernetes API 执行 `listNode`。注册成功后同步结果应至少包含：

```text
nodeCount = 1
gpuCount = 8
gpuModel = NVIDIA-A100-SXM4-80GB
```

