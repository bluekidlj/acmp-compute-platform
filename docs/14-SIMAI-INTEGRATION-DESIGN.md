# ACMP 接入阿里 SimAI 仿真评估能力设计

## 1. 文档目的

本文定义 ACMP 算力管理平台接入阿里开源 SimAI 的总体方案，作为后续分阶段开发、
联调和验收的设计基线。

本方案遵循以下原则：

1. 优先跑通最核心的仿真任务流程；
2. ACMP 保持单体服务，不引入不必要的分布式组件；
3. SimAI 作为独立仿真引擎，不侵入现有资源管理和推理服务流程；
4. 缺少非核心拓扑字段时允许降级运行；
5. 仿真结果第一阶段只提供参考，不自动修改资源配置；
6. 真实 Kubernetes 部署、推理和资源分配不依赖 SimAI；
7. 所有输入、输出和错误均应保留，便于内网环境检查和调试。

## 2. SimAI 的定位

SimAI 不是传统工业场景中的三维数字孪生平台，而是面向大规模 AI 训练和推理的
全栈性能模拟器。

SimAI 可以模拟：

- 模型训练和推理工作负载；
- 计算与通信过程；
- 集合通信；
- 网络拓扑和网络传输；
- 不同 Gpu 数量和 Gpu 型号；
- 不同并行策略；
- 多请求推理调度；
- Prefill/Decode 分离等推理架构。

对于 ACMP，SimAI 的产品定位为：

> 算力集群和 AI 工作负载的仿真评估引擎。

它的价值不是展示三维集群，而是在真正申请或采购大量算力之前，回答容量、性能、
成本和瓶颈相关的问题。

## 3. 要解决的业务问题

### 3.1 模型部署前评估

业务方选择模型后，希望提前知道：

- 模型至少需要多少张 Gpu；
- 目标 Gpu 是否能够容纳模型权重和 KV Cache；
- 预计可以支持多少并发；
- 预计吞吐量和首 Token 延迟；
- 采用何种张量并行度；
- 增加 Gpu 后性能是否能线性提升；
- 当前集群是否适合部署该模型。

### 3.2 算力规格推荐

平台可以基于模型、并发目标和目标集群给出规格建议：

```text
模型：Qwen3 32B
目标：20 并发，平均输入 2K Token

建议：
- Gpu：4 × H20
- 张量并行：4
- CPU：16 Core
- 内存：64 GiB
- 最大上下文：8192
- 预计吞吐：仿真结果
- 预计显存占用：仿真结果
```

第一阶段只展示建议，由管理员或业务方确认后人工创建算力规格。

### 3.3 集群扩容和采购评估

运维人员可以比较多个假设方案：

| 方案 | Gpu | 网络 | 预计吞吐 | 预计耗时 |
| --- | --- | --- | --- | --- |
| 当前集群 | 8 × A100 | 100 GbE | 仿真结果 | 仿真结果 |
| 扩容方案 A | 16 × A100 | 100 GbE | 仿真结果 | 仿真结果 |
| 扩容方案 B | 8 × H800 | 400 GbE | 仿真结果 | 仿真结果 |

主要回答：

- 采购更多旧卡还是更少的新卡；
- 网络升级对性能的收益；
- 当前瓶颈是计算还是通信；
- 扩容后能够多承载多少业务；
- 不同方案的相对收益。

### 3.4 训练策略优化

对训练任务比较：

- 数据并行；
- 张量并行；
- 流水线并行；
- 集合通信算法；
- NCCL 参数；
- 节点内和节点间通信；
- 网络带宽和网络拓扑。

### 3.5 故障和降级推演

可以在不影响生产集群的情况下模拟：

- 部分节点不可用；
- 网络带宽下降；
- 可用 Gpu 数量减少；
- 请求并发突然升高；
- Prefill/Decode 资源比例变化。

## 4. 系统边界

### 4.1 ACMP 负责

- 管理集群、节点、Gpu 和资源池；
- 管理模型、租户、项目和算力规格；
- 收集用户的仿真目标；
- 从现有数据生成仿真输入；
- 创建和查询 Kubernetes Job；
- 保存仿真任务状态；
- 保存输入、输出摘要和错误信息；
- 解析 SimAI 输出；
- 展示业务化的评估结果；
- 支持多个仿真方案比较。

### 4.2 SimAI 负责

- 生成或读取 AI 工作负载；
- 模拟计算和通信过程；
- 模拟集合通信；
- 模拟网络拓扑；
- 输出执行时间和性能数据；
- 在支持条件满足时执行推理流量仿真。

### 4.3 Kubernetes 负责

- 运行 SimAI 容器；
- 调度仿真 Job；
- 挂载输入和输出存储；
- 提供 Job、Pod 和日志状态；
- 限制仿真任务使用的 CPU、内存和 Gpu。

### 4.4 Prometheus 和 DCGM 负责

后续阶段用于提供真实运行基线：

- 真实 Gpu 使用率；
- 真实显存占用；
- 温度和功耗；
- 实际运行负载；
- 节点 CPU 和内存；
- 真实任务的运行趋势。

Prometheus/DCGM 不是第一阶段运行 SimAI 的必要条件。

## 5. 总体架构

```text
┌─────────────────────────────────────────────────────────┐
│                       ACMP 前端                          │
│  仿真评估创建 / 任务列表 / 结果详情 / 多方案比较       │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│                    ACMP 单体后端                         │
│                                                         │
│  集群数据 ─┐                                            │
│  模型数据 ─┼─→ 输入构建 ─→ Kubernetes Job 管理          │
│  规格数据 ─┘                     │                      │
│                                 │                      │
│  结果解析 ←─ 输出读取 ←──────────┘                      │
└────────────────────────────┬────────────────────────────┘
                             │ Kubernetes API
                             ▼
┌─────────────────────────────────────────────────────────┐
│                   Kubernetes 集群                        │
│                                                         │
│  SimAI Job ─→ SimAI Container ─→ 输出文件 / 日志         │
└─────────────────────────────────────────────────────────┘

后续校准：

Prometheus + DCGM ─→ ACMP ─→ 实测数据与仿真结果比较
```

## 6. 核心业务流程

### 6.1 创建仿真任务

```text
业务方选择项目
  → 选择模型
  → 选择现有集群或假设集群
  → 选择训练或推理
  → 选择快速分析或精细仿真
  → 填写必要目标
  → ACMP 校验核心字段
  → 创建仿真任务
```

核心字段：

- 项目；
- 模型；
- 仿真模式；
- Gpu 型号；
- 节点数量；
- 每节点 Gpu 数量；
- 工作负载类型。

非核心字段：

- NVLink 详细拓扑；
- 交换机拓扑；
- 精确 RDMA 配置；
- 温度和功耗；
- 历史运行负载。

非核心字段缺失时，系统应使用默认值或降级为快速分析模式。

### 6.2 生成仿真输入

ACMP 根据以下数据生成输入：

```text
模型元数据
+ 用户目标
+ 集群 Gpu 信息
+ 网络参数
+ 并行参数
= SimAI 输入文件
```

生成的输入文件必须保存，不能只保存在内存中。

建议保存：

```text
/simulations/{taskId}/input/
  workload.txt
  topology.yaml
  busbw.yaml
  simai.conf
  request.json
```

并不是每一种模式都需要全部文件。

### 6.3 创建 Kubernetes Job

ACMP 使用目标集群已有的 Kubernetes 客户端创建 Job：

```text
PENDING
  → 创建 Job
  → RUNNING
  → 读取执行状态
  → SUCCEEDED 或 FAILED
```

Job 必须包含 ACMP 标签：

```yaml
metadata:
  labels:
    app.kubernetes.io/managed-by: acmp
    acmp.io/workload-type: simulation
    acmp.io/simulation-id: "<task-id>"
    acmp.io/project-id: "<project-id>"
```

### 6.4 执行 SimAI

第一阶段执行 SimAI Analytical：

```bash
./bin/SimAI_analytical \
  -w /input/workload.txt \
  -g 64 \
  -g_p_s 8 \
  -busbw /input/busbw.yaml \
  -r /output/result-
```

具体参数以选定 SimAI 版本为准，不能长期依赖未经版本管理的命令行格式。

### 6.5 获取结果

任务完成后，ACMP 获取：

- Job 状态；
- Pod 状态；
- 容器退出码；
- 标准输出；
- 标准错误；
- SimAI 原始结果文件；
- ACMP 解析后的结果摘要。

读取优先级：

1. 结果文件；
2. 容器日志；
3. Job 和 Pod 状态。

即使结果解析失败，也必须保留原始输出。

### 6.6 展示业务结果

前端优先展示：

- 预计完成时间；
- 预计吞吐；
- 预计通信时间占比；
- 预计计算时间占比；
- Gpu 数量和节点数量；
- 并行策略；
- 主要瓶颈；
- 与其他方案的相对差异。

原始日志放在详情页的次要区域，供技术人员排查。

## 7. 仿真模式

### 7.1 快速分析

对应 `SimAI-Analytical`。

用途：

- 模型部署前评估；
- Gpu 数量比较；
- 并行策略比较；
- 集群扩容初筛；
- 快速方案对比。

特点：

- 运行快；
- 可以运行在普通 CPU 节点；
- 不要求完整网络拓扑；
- 第一阶段优先实现。

### 7.2 精细网络仿真

对应 `SimAI-Simulation`，使用 NS-3 等组件模拟网络通信。

用途：

- 网络方案比较；
- 大规模训练集群设计；
- 通信瓶颈分析；
- 交换网络和带宽评估。

特点：

- 输入参数更多；
- 运行时间更长；
- 对网络拓扑数据要求更高；
- 第二阶段以后实现。

### 7.3 多请求推理仿真

用于评估：

- 请求并发；
- 请求调度；
- Prefill/Decode；
- KV Cache；
- 推理吞吐和时延。

当前官方实现中的部分计算分析依赖 DeepGEMM 和 FlashMLA，并要求 Hopper
或 Blackwell 架构的 NVIDIA Gpu。

如果环境只有 A100 或没有真实 Gpu，不将本模式作为第一阶段验收内容。

## 8. 数据来源

### 8.1 Kubernetes API 可获取

- 节点数量；
- 节点 CPU 和内存容量；
- 每个节点的 Gpu 数量；
- Gpu 型号标签；
- Gpu 分配状态；
- 推理服务 Deployment 和 Pod；
- 当前使用的算力规格；
- 模型和部署参数。

### 8.2 Prometheus/DCGM 可获取

- Gpu 使用率；
- 显存使用量；
- Gpu 温度；
- 功耗；
- 节点 CPU 和内存使用；
- 真实工作负载趋势。

### 8.3 需要额外探测或人工配置

- NVLink/NVSwitch 拓扑；
- 节点间网络带宽；
- RDMA 网卡；
- RoCE/InfiniBand；
- 交换机拓扑；
- NCCL 算法和参数；
- 实际集合通信带宽。

可能的数据来源：

```text
nvidia-smi topo -m
ibstat
ethtool
NCCL Test
网络设备配置
管理员维护的拓扑模板
```

### 8.4 缺失字段处理

| 缺失数据 | 处理方式 |
| --- | --- |
| Gpu 型号 | 阻止提交并提示选择，属于核心字段 |
| Gpu 数量 | 阻止提交并提示填写，属于核心字段 |
| 网络带宽 | 使用平台默认值并明确标注 |
| NVLink 拓扑 | 使用标准节点内拓扑模板 |
| RDMA 配置 | 按未启用处理 |
| Prometheus 指标 | 不影响仿真任务运行 |
| 温度和功耗 | 不参与第一阶段仿真 |

所有默认值必须在结果页显示，避免业务方误以为全部数据来自真实集群。

## 9. 最小数据模型

第一阶段只新增一张仿真任务表。

### 9.1 SimulationTask

```text
id                  UUID
project_id          UUID
cluster_id          UUID，可为空
model_id            UUID
name                VARCHAR
workload_type       TRAINING / INFERENCE
simulation_mode     ANALYTICAL / NETWORK / MULTI_REQUEST
status              PENDING / RUNNING / SUCCEEDED / FAILED

target_gpu_model     VARCHAR
node_count          INTEGER
gpu_per_node        INTEGER
total_gpu_count     INTEGER

input_config        JSON/CLOB
result_summary      JSON/CLOB
input_path          VARCHAR
result_path         VARCHAR

k8s_namespace       VARCHAR
k8s_job_name        VARCHAR
pod_name            VARCHAR
exit_code           INTEGER
error_message       VARCHAR

created_at          TIMESTAMP
started_at          TIMESTAMP
completed_at        TIMESTAMP
updated_at          TIMESTAMP
```

H2 第一阶段可以使用 CLOB 保存 JSON 字符串，不必为了配置内容建立大量子表。

### 9.2 状态定义

```text
PENDING
RUNNING
SUCCEEDED
FAILED
```

第一阶段不增加复杂的取消中、重试中等状态。

删除仿真任务时：

- 如果 Job 仍然存在，先删除指定 Job；
- 只删除任务自身对应的 Kubernetes 对象；
- 不删除共享模型、集群或项目；
- 记录清理失败信息。

## 10. 输入配置

第一阶段建议使用一个统一请求对象：

```json
{
  "name": "Qwen3-32B A100 方案评估",
  "projectId": "project-id",
  "clusterId": "cluster-id",
  "modelId": "model-id",
  "workloadType": "INFERENCE",
  "simulationMode": "ANALYTICAL",
  "target": {
    "gpuModel": "NVIDIA A100 80GB",
    "nodeCount": 2,
    "gpuPerNode": 8,
    "networkBandwidthGbps": 100
  },
  "parallel": {
    "tensorParallelSize": 8,
    "pipelineParallelSize": 1,
    "dataParallelSize": 2
  },
  "inference": {
    "concurrency": 20,
    "averageInputTokens": 2048,
    "averageOutputTokens": 512,
    "maxModelLength": 8192
  }
}
```

第一阶段可以允许 `parallel` 和 `inference` 中部分非核心字段为空，由后端补默认值。

## 11. Kubernetes Job 设计

### 11.1 基本模板

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: acmp-sim-<short-id>
  namespace: acmp-simulation
  labels:
    app.kubernetes.io/name: simai
    app.kubernetes.io/managed-by: acmp
    acmp.io/workload-type: simulation
    acmp.io/simulation-id: "<task-id>"
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        app.kubernetes.io/name: simai
        acmp.io/simulation-id: "<task-id>"
    spec:
      restartPolicy: Never
      containers:
        - name: simai
          image: registry.internal/acmp/simai:<version>
          imagePullPolicy: IfNotPresent
          command:
            - /opt/acmp/run-simulation.sh
          args:
            - --mode
            - analytical
            - --input
            - /workspace/input
            - --output
            - /workspace/output
          resources:
            requests:
              cpu: "2"
              memory: 4Gi
            limits:
              cpu: "4"
              memory: 8Gi
          volumeMounts:
            - name: workspace
              mountPath: /workspace
      volumes:
        - name: workspace
          persistentVolumeClaim:
            claimName: acmp-simulation-workspace
```

### 11.2 脚本职责

容器内包装脚本负责：

1. 校验输入目录；
2. 根据模式执行 SimAI；
3. 将标准输出写入结果目录；
4. 保存退出码；
5. 生成统一的 `result.json`；
6. 以 SimAI 真实退出码退出。

ACMP 不应在 Java 代码中拼接复杂 Shell 命令。

### 11.3 资源限制

快速分析默认使用 CPU 和内存，不申请真实 Gpu。

只有以下场景才申请 Gpu：

- AICB 真实计算时间剖析；
- 多请求推理仿真需要真实加速库；
- 后续明确需要 Gpu 的校准任务。

仿真的目标 Gpu 数量不等于 Job 实际申请的 Gpu 数量。

例如模拟 128 张 Gpu，不代表 Kubernetes Job 要申请 128 张真实 Gpu。

### 11.4 Namespace

建议使用独立 Namespace：

```text
acmp-simulation
```

便于：

- 设置 ResourceQuota；
- 设置 LimitRange；
- 限制镜像和网络访问；
- 清理仿真任务；
- 与真实推理服务隔离。

## 12. 后端模块设计

保持单体服务内部的简单分层：

```text
SimulationController
        │
SimulationService
        ├─ SimulationInputBuilder
        ├─ SimulationJobBuilder
        └─ SimulationResultParser
                │
KubernetesClientManager
```

### 12.1 SimulationController

负责：

- 接收请求；
- 基础字段校验；
- 返回统一响应；
- 提供接口注释。

不负责：

- 拼接 SimAI 参数；
- 直接构造 Kubernetes 对象；
- 解析结果文件。

### 12.2 SimulationService

负责核心流程：

- 检查项目、模型和集群；
- 保存任务；
- 创建 Job；
- 同步状态；
- 获取结果；
- 删除任务。

### 12.3 SimulationInputBuilder

负责：

- 将 ACMP 数据转换为 SimAI 输入；
- 补充默认值；
- 记录默认值来源；
- 输出可审计的输入文件。

### 12.4 SimulationJobBuilder

负责构建：

- Namespace；
- Job；
- 标签；
- 环境变量；
- 资源限制；
- 存储挂载。

### 12.5 SimulationResultParser

负责：

- 读取统一结果文件；
- 解析核心指标；
- 保存原始内容；
- 对解析失败进行降级。

如果解析失败：

```text
Job 成功 + 结果解析失败
```

第一阶段可以将任务标记为 `FAILED`，错误信息明确写为“结果解析失败”，同时保留原始文件。

## 13. API 设计

### 13.1 创建任务

```http
POST /api/v1/projects/{projectId}/simulations
```

响应：

```json
{
  "id": "simulation-id",
  "name": "Qwen3-32B A100 方案评估",
  "status": "PENDING",
  "simulationMode": "ANALYTICAL",
  "createdAt": "2026-07-25T16:00:00+08:00"
}
```

### 13.2 查询项目任务

```http
GET /api/v1/projects/{projectId}/simulations
```

### 13.3 查询任务详情

```http
GET /api/v1/projects/{projectId}/simulations/{simulationId}
```

查询详情时可以同步一次 Kubernetes Job 状态。

### 13.4 查询原始输入

```http
GET /api/v1/projects/{projectId}/simulations/{simulationId}/input
```

### 13.5 查询原始输出

```http
GET /api/v1/projects/{projectId}/simulations/{simulationId}/output
```

### 13.6 删除任务

```http
DELETE /api/v1/projects/{projectId}/simulations/{simulationId}
```

删除动作只处理指定任务及其 Job。

### 13.7 第一阶段不提供

- 任意 Shell 命令输入；
- 用户自定义容器镜像；
- 用户提交任意 SimAI 配置文件；
- 自动修改算力规格；
- 自动发起真实部署；
- 自动重试。

这些能力会扩大安全风险和排查范围。

## 14. 响应结果设计

任务详情示例：

```json
{
  "id": "simulation-id",
  "name": "Qwen3-32B A100 方案评估",
  "status": "SUCCEEDED",
  "workloadType": "INFERENCE",
  "simulationMode": "ANALYTICAL",
  "target": {
    "gpuModel": "NVIDIA A100 80GB",
    "nodeCount": 2,
    "gpuPerNode": 8,
    "totalGpuCount": 16,
    "networkBandwidthGbps": 100,
    "networkBandwidthSource": "USER_INPUT"
  },
  "parallel": {
    "tensorParallelSize": 8,
    "pipelineParallelSize": 1,
    "dataParallelSize": 2
  },
  "result": {
    "estimatedDurationSeconds": 120.5,
    "computeRatioPercent": 68.2,
    "communicationRatioPercent": 31.8,
    "bottleneck": "INTER_NODE_COMMUNICATION",
    "summary": "当前方案主要受节点间通信限制"
  },
  "kubernetes": {
    "namespace": "acmp-simulation",
    "jobName": "acmp-sim-abc123",
    "podName": "acmp-sim-abc123-xxxxx",
    "exitCode": 0
  },
  "createdAt": "2026-07-25T16:00:00+08:00",
  "startedAt": "2026-07-25T16:00:02+08:00",
  "completedAt": "2026-07-25T16:00:18+08:00"
}
```

任何估算数据必须带有“仿真结果”语义，不得展示为真实生产指标。

## 15. 前端页面设计

左侧导航增加：

```text
业务管理
  租户
  项目
  模型广场
  推理服务
  仿真评估
```

### 15.1 仿真评估首页

展示：

- 仿真任务总数；
- 运行中任务；
- 成功任务；
- 失败任务；
- 最近任务；
- 新建仿真按钮。

### 15.2 新建仿真

采用三步流程。

#### 第一步：评估对象

- 项目；
- 模型；
- 工作负载：训练/推理；
- 模式：快速分析/精细网络/多请求推理。

第一阶段仅开放：

```text
快速分析
```

其他模式显示“后续支持”，不能提交。

#### 第二步：目标环境

- 使用现有集群；
- 使用假设集群；
- Gpu 型号；
- 节点数；
- 每节点 Gpu 数；
- 网络带宽；
- 并行参数。

选择现有集群时自动带出已知字段。

#### 第三步：确认

明确展示：

- 哪些字段来自真实集群；
- 哪些字段由用户填写；
- 哪些字段使用默认值；
- 本任务是否申请真实 Gpu；
- 预计仿真类型。

### 15.3 仿真详情

页面分为：

1. 任务状态；
2. 输入方案；
3. 结果摘要；
4. 性能构成；
5. 瓶颈说明；
6. Kubernetes Job 信息；
7. 原始输入；
8. 原始输出和错误。

### 15.4 方案比较

第二阶段支持选择 2～3 个已成功任务进行比较：

| 指标 | 方案 A | 方案 B | 方案 C |
| --- | --- | --- | --- |
| Gpu | 8×A100 | 16×A100 | 8×H800 |
| 网络 | 100GbE | 100GbE | 400GbE |
| 预计耗时 | 结果 | 结果 | 结果 |
| 通信占比 | 结果 | 结果 | 结果 |
| 相对提升 | 基准 | 结果 | 结果 |

## 16. 错误处理和降级

### 16.1 SimAI 镜像不存在

- 创建 Job 可能成功；
- Pod 进入 `ImagePullBackOff`；
- 任务同步后标记为 `FAILED`；
- 返回镜像拉取错误；
- 不影响其他 ACMP 功能。

### 16.2 Kubernetes 不可连接

- 创建任务失败；
- 不创建无意义的长期 `PENDING` 记录，或保存后立即标记 `FAILED`；
- 返回明确的集群连接错误；
- 不影响其他集群。

### 16.3 输入字段不完整

- 核心字段缺失：拒绝提交；
- 网络等非核心字段缺失：补默认值；
- 结果中标记默认值。

### 16.4 Job 执行失败

保存：

- Pod 状态；
- 退出码；
- 最后日志；
- Kubernetes 事件摘要；
- 错误信息。

### 16.5 结果解析失败

- 保留原始文件；
- 返回“仿真已执行，但结果解析失败”；
- 不因为某个非核心结果字段缺失而丢弃全部结果；
- 可以展示已成功解析的字段。

### 16.6 SimAI 不可用

SimAI 不可用时：

- 集群发现正常；
- 资源池正常；
- 算力规格正常；
- 租户和项目正常；
- 模型登记正常；
- 推理服务部署正常；
- 仅仿真评估不可用。

## 17. 安全设计

- SimAI 镜像由平台配置，用户不能任意指定；
- Job 命令由后端模板生成；
- 用户不能提交任意 Shell；
- 所有参数必须进行范围校验；
- Job 使用专用 ServiceAccount；
- 仿真 Namespace 设置 ResourceQuota；
- 输出目录按任务 ID 隔离；
- API 校验项目归属；
- 删除时严格按任务 ID 和标签定位；
- 原始输出避免包含 kubeconfig、Token 和 Secret；
- 内网镜像固定版本和 digest。

## 18. 可观测性

第一阶段至少记录：

- 仿真任务 ID；
- 项目 ID；
- 集群 ID；
- Kubernetes Job 名称；
- 创建时间；
- 开始时间；
- 完成时间；
- 状态；
- 退出码；
- 错误摘要；
- SimAI 版本；
- 输入文件路径；
- 输出文件路径。

后续接入 Prometheus 后增加：

- Job 运行时长；
- 仿真任务成功率；
- 排队任务数；
- 仿真容器 CPU 和内存；
- 结果解析失败数。

## 19. 仿真结果校准

仿真结果不能天然等同于真实运行结果。

后续校准流程：

```text
选择一个已部署模型
  → 记录真实 Gpu、网络和并行参数
  → 从 Prometheus/DCGM 获取实测数据
  → 用相同输入执行 SimAI
  → 比较预计值与实测值
  → 记录偏差
  → 调整默认带宽或模型参数
```

建议展示：

```text
预计耗时
真实耗时
误差百分比
校准时间
校准环境
```

第一阶段不需要自动校准。

## 20. 分阶段实施计划

### 20.1 阶段一：独立技术验证

目标：证明 SimAI 能在当前 Kubernetes 中作为 Job 完成一次快速仿真。

工作：

1. 固定 SimAI 版本；
2. 拉取代码和子模块；
3. 编译 SimAI Analytical；
4. 构建内网镜像；
5. 准备一个固定示例；
6. 手工创建 Kubernetes Job；
7. 保存完整 YAML；
8. 保存真实输入、输出、日志和退出码；
9. 验证任务清理。

本阶段不修改 ACMP 代码。

完成标准：

- Job 成功结束；
- 能获取输出文件；
- 能确认退出码；
- 重复运行结果稳定；
- 不需要申请目标规模的真实 Gpu。

### 20.2 阶段二：ACMP 最小闭环

目标：

```text
创建任务 → 创建 Job → 查询状态 → 获取结果 → 页面展示
```

工作：

- 新增 SimulationTask；
- 新增 Mapper；
- 新增 Service；
- 新增 Controller；
- 新增 SimAI 输入构建；
- 新增 Kubernetes Job 构建；
- 新增结果解析；
- 新增任务列表和详情页面；
- 保留真实请求和响应。

只支持：

- `ANALYTICAL`；
- 一个固定 SimAI 镜像；
- 一个固定 Namespace；
- CPU 运行；
- 基础结果摘要。

### 20.3 阶段三：方案比较

工作：

- 支持假设集群；
- 支持多个 Gpu 数量方案；
- 支持并行参数；
- 支持 2～3 个任务对比；
- 输出瓶颈说明；
- 生成算力规格建议。

建议仍需人工确认。

### 20.4 阶段四：真实数据校准

工作：

- 接入 Prometheus；
- 接入 DCGM；
- 读取真实部署数据；
- 比较仿真和实测；
- 记录偏差；
- 调整默认参数。

### 20.5 阶段五：精细网络和推理仿真

条件成熟后增加：

- SimAI-Simulation；
- NS-3 网络拓扑；
- RDMA/NCCL 数据；
- 多请求推理；
- Prefill/Decode；
- Hopper/Blackwell 环境验证。

## 21. 第一阶段明确不做

- 不做复杂工作流引擎；
- 不做消息队列；
- 不做多服务拆分；
- 不做自动重试策略；
- 不做自动采购决策；
- 不自动修改算力规格；
- 不自动部署真实推理服务；
- 不接收用户自定义镜像；
- 不接收用户自定义 Shell；
- 不做多集群联邦仿真；
- 不实现 SimAI 已经提供的算法。

## 22. 后续开发前需要确认的事项

开始阶段一之前确认：

1. 固定使用的 SimAI 版本或 Git commit；
2. 内网镜像仓库地址；
3. Kubernetes 运行 Namespace；
4. 可用 StorageClass 或共享存储；
5. 第一份仿真模型；
6. 第一份目标集群模板；
7. SimAI 输出中需要展示的最小指标；
8. 是否先在 Docker Desktop Kubernetes 验证 Job 生命周期；
9. 真实 Gpu 集群的后续验证窗口。

## 23. 推荐的第一个验证场景

建议第一份场景保持简单：

```text
仿真模式：SimAI-Analytical
工作负载：固定推理或训练 workload
目标 Gpu：A100
节点数：2
每节点 Gpu：8
总 Gpu：16
网络带宽：100 Gbps
运行环境：Kubernetes CPU Job
输出：预计总时间、计算时间、通信时间
```

先证明任务闭环，再增加 Qwen、DeepSeek 和复杂推理流量。

## 24. 官方参考

- SimAI 官方仓库：
  <https://github.com/aliyun/SimAI>
- SimAI 文档入口：
  <https://github.com/aliyun/SimAI/tree/master/docs>
- SimAI AICB：
  <https://github.com/aliyun/aicb>
- SimAI 论文：
  <https://www.usenix.org/conference/nsdi25/presentation/zhang-junxue>
