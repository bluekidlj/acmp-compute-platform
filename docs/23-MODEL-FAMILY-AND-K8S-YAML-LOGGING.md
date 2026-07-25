# 模型系列与 Kubernetes YAML 日志

## 模型系列

模型广场固定展示四个系列：

- `DEEPSEEK`：DeepSeek 系列
- `QWEN`：阿里巴巴通义千问系列
- `GLM`：智谱 GLM 系列
- `MINIMAX_M`：MiniMax M 系列

模型系列是固定分类，不单独建立管理表。登记或修改模型时必须选择一个系列，
部署时仍然选择具体的已登记模型。

## Kubernetes 提交日志

推理部署主流程使用以下日志关键字：

- `推理部署准备`：项目、集群、规格、副本、镜像和模型路径。
- `推理部署阶段开始`：当前执行阶段。
- `K8S YAML 提交`：即将提交的 Namespace、Deployment 或 Service 完整 YAML。
- `K8S API 成功`：资源提交成功。
- `K8S API 失败`：资源类型、名称、HTTP 状态码和 API Server 响应体。
- `推理部署提交成功`：Deployment、Service 和集群内访问地址。

日志不输出 JWT、kubeconfig、Harbor 密码或 Kubernetes Secret 内容。

Windows 本地运行时查看：

```powershell
Get-Content .runtime/backend.out.log -Wait
```

离线服务器使用 systemd 时查看：

```bash
journalctl -u acmp-compute -f
```

