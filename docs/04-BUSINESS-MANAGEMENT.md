# 业务管理

业务管理把算力资源转换为可被业务使用的对象。当前主线是：

```text
租户
  → 规格配额
  → 项目
  → 已登记模型
  → 推理服务
```

租户获得可使用的算力范围，项目承载具体业务，推理服务将模型、镜像和算力规格提交到 Kubernetes。

## 1. 租户

租户是平台分配算力配额的业务边界。当前版本面向单管理员操作，不引入复杂的租户成员体系和审批流程。

租户主要维护：

- 基本信息和状态；
- 可使用的算力规格；
- 每种规格的总配额和已使用数量；
- 归属项目。

代码入口：

- `src/main/java/com/acmp/compute/controller/TenantController.java`
- `src/main/java/com/acmp/compute/service/TenantService.java`
- `src/main/java/com/acmp/compute/service/TenantSpecQuotaService.java`

## 2. 规格配额

租户配额按算力规格分配，而不是只给一个模糊的 GPU 总数。

这样可以表达：

- NVIDIA 与其他品牌分别分配；
- V100、A100 等型号分别分配；
- 独享整卡和 `1/4`、`1/8` 等共享规格分别分配；
- 不同 CPU、内存组合分别分配。

租户部署多个副本时，每个副本消耗一个规格节点。部署前校验剩余配额，提交失败时恢复本次占用，删除推理服务后释放配额。

配额不直接改变 Kubernetes Node 的 Capacity，它是平台侧业务约束。

## 3. 项目

项目是推理服务的业务归属，用于将同一租户下的不同应用分开管理。

项目保存租户关系和基本信息，并在部署时继承租户可用规格。当前 MVP 不在项目层重复建立一套复杂配额体系，避免租户配额和项目配额产生双重维护。

代码入口：

- `src/main/java/com/acmp/compute/controller/ProjectController.java`
- `src/main/java/com/acmp/compute/service/ProjectService.java`

## 4. 模型系列与已登记模型

模型广场分为两层：

- 模型系列：用于按厂商或技术系列组织模型，如 DeepSeek、通义千问、智谱 GLM、MiniMax；
- 已登记模型：一份可以实际部署的模型记录。

已登记模型主要保存：

- 展示名称和所属系列；
- 模型来源类型；
- GPU 主机上的模型绝对目录；
- 推荐 vLLM 镜像；
- 说明和运行提示。

模型文件不建议放入 ACMP 数据库。内网大模型文件体积较大，当前方案将模型目录预先放到 GPU Node 本地磁盘，再通过 Kubernetes `hostPath` 挂载到容器。

代码入口：

- `src/main/java/com/acmp/compute/controller/ModelController.java`
- `src/main/java/com/acmp/compute/service/ModelService.java`
- `frontend/src/pages/real/Models.tsx`

## 5. 推理服务部署输入

部署向导分为业务信息、算力规格和运行配置三个阶段，提交前必须允许用户确认关键参数。

核心输入包括：

| 输入 | 作用 |
|---|---|
| 服务名称 | 平台记录和 Kubernetes 对象命名基础 |
| 算力规格 | 决定池类型、Node 标签和设备资源请求 |
| 副本数 | Deployment 副本数，同时决定规格配额消耗 |
| 容器镜像 | 直接写入 Kubernetes Container `image` |
| 模型 | 引用模型广场登记记录 |
| 主机模型目录 | 写入 `volumes.hostPath.path` |
| 容器内模型目录 | 写入 `volumeMounts.mountPath`，同时传给 vLLM |
| 端口 | 容器端口和 Service 端口 |
| vLLM 参数 | 转换为容器 command/args |

镜像字段填写的是 Kubernetes 能识别的镜像引用，例如：

```text
harbor.internal/acmp/vllm:0.10.0
```

`harbor://` 不是 Kubernetes 镜像地址格式，不应写入该字段。

## 6. 模型目录映射

模型存在 GPU Node 的真实路径，例如：

```text
/data/models/Qwen2.5-3B-Instruct
```

容器内可以统一挂载为：

```text
/models/Qwen2.5-3B-Instruct
```

对应的 vLLM 参数：

```text
serve /models/Qwen2.5-3B-Instruct
```

这里的 `serve` 表示启动 vLLM OpenAI 兼容服务，后面的路径是容器内模型路径，不是宿主机路径。宿主机路径由 `hostPath` 提供。

使用本地模型目录意味着副本只能调度到实际拥有该目录的 Node。当前平台通过算力规格标签约束调度，正式环境还应确保同规格目标 Node 的模型目录一致，或后续替换为共享存储。

## 7. Kubernetes 落地

提交部署时，后端：

1. 校验项目、模型、规格和租户剩余配额；
2. 选择具备足够规格节点的集群；
3. 生成 Deployment 和 Service；
4. 写入镜像、模型挂载、端口、环境变量和 vLLM 参数；
5. 根据规格生成 Node Selector；
6. 根据独享或共享类型生成 GPU 资源请求；
7. 通过 Kubernetes API 提交 YAML；
8. 保存 Kubernetes 对象名称和服务地址；
9. 后续根据 Deployment/Pod 状态更新服务状态。

主流程会记录生成的 Kubernetes YAML 和 API 操作日志，便于内网环境排查镜像、挂载、资源符和调度标签。

代码入口：

- `src/main/java/com/acmp/compute/service/ModelDeploymentService.java`
- `src/main/java/com/acmp/compute/k8s/K8sResourceBuilder.java`
- `src/main/java/com/acmp/compute/k8s/KubernetesClientManager.java`
- `frontend/src/pages/real/ProjectDetail.tsx`

## 8. 服务状态

平台记录的是 Kubernetes 实际状态，而不是在前端直接将服务标记为就绪。

常见阶段包括：

- 已提交：Deployment 和 Service 已创建；
- 处理中：Pod 尚未全部 Ready；
- 就绪：Ready Replicas 达到期望副本数；
- 失败：镜像、挂载、资源、调度或容器启动失败。

Pod 长期 Pending 时，应优先查看 Kubernetes Events。常见原因是 Node Selector 不匹配、GPU 资源符不存在、配额不足、内存不足或控制平面污点。

## 9. 删除推理服务

删除流程应同时：

- 删除 Kubernetes Deployment 和 Service；
- 释放租户规格配额；
- 删除或更新平台部署记录；
- 保留足够日志用于判断 Kubernetes 删除是否成功。

模型登记和主机模型文件不会随推理服务删除，以便多个服务复用。

## 10. 模块边界

当前业务管理实现的是推理服务发布主流程，不负责：

- 自动下载大模型到每个 GPU Node；
- 管理 Harbor 仓库生命周期；
- 自动进行在线扩缩容；
- 实现模型训练或模型版本治理平台；
- 提供复杂租户计费与审批。

这些能力可以在主链路稳定后独立扩展。
