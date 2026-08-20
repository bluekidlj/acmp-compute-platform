# vLLM + HAMi 推理部署修复说明

日期：2026-08-20

## 目标

本次修复只聚焦推理部署链路，解决以下问题：

1. 独享整卡场景仍会经过 HAMi 时，需要按条件注入 `CUDA_DISABLE_CONTROL=true`。
2. 单 Pod 独占多卡时，在算力规格中固定每个规格节点使用的 GPU 张数。
3. vLLM 启动参数需要由后端生成安全默认值，避免前端手工拼接遗漏关键参数。

## 本次调整

### 算力规格与容量

- `replicas`：Deployment 副本数。
- `ComputeSpec.gpuCount`：一个规格节点（一个 Pod 副本）请求的物理 GPU 张数，在 Node 入池创建规格时确定。
- 独享规格容量：`规格关联物理 GPU 总数 / ComputeSpec.gpuCount`。
- 例如 4 卡 Node 选择 1、2、4 卡规格时，分别形成 4、2、1 个规格节点。
- 独享规格只允许选择能够整除 Node 总卡数的 GPU 张数，避免产生无法表达的剩余卡。
- 共享规格固定为单卡切分，容量为物理卡数乘以切分份数。
- 部署页面不再允许覆盖每副本 GPU 数；后端始终从算力规格读取，防止规格容量、配额和 Pod 实际申请不一致。
- `tensorParallelSize`：单个 vLLM 实例的张量并行度，默认等于规格 GPU 数，且不能超过规格 GPU 数。
- `gpuMemoryUtilization`：默认 `0.8`。
- `maxModelLength`：默认 `8192`。

### HAMi 兼容变量

- 独享池生成 `CUDA_DISABLE_CONTROL=true`。
- 共享池不生成该变量。

### vLLM 参数生成

后端负责生成以下关键参数：

- `--host 0.0.0.0`
- `--port <port>`
- `--gpu-memory-utilization 0.8`
- `--max-model-len 8192`
- `--tensor-parallel-size <tensorParallelSize>`
- `--served-model-name <modelName>`

前端只负责采集业务输入和高级参数，不再手工拼接 vLLM 核心参数。

## 验证重点

1. 独享单卡部署时，Pod 中应只看到 1 张卡。
2. 4 卡 Node 创建 1、2、4 卡独享规格后，容量应分别为 4、2、1 个规格节点。
3. 独享多卡部署时，`nvidia.com/gpu` 应等于 `ComputeSpec.gpuCount`，默认 `--tensor-parallel-size` 与其相等。
4. 共享部署不应出现 `CUDA_DISABLE_CONTROL`。
5. Service `targetPort` 必须与 vLLM `--port` 一致。

## 推理服务真实监控

- 后端通过 Kubernetes API Server 的 Service Proxy 请求部署对应 vLLM `/metrics`，不要求平台主机解析集群内 Service DNS。
- 当前值直接读取 vLLM Prometheus 指标：运行请求、等待请求、GPU KV Cache 使用率。
- 累计值直接读取 vLLM 指标：Prompt Token、Generation Token、成功请求。
- 前端每 5 秒采样一次，用相邻两次累计 Token 的差值计算真实 Token/s。
- 未接入 Prometheus 历史存储前，曲线只展示页面打开后的真实采样点，不生成历史模拟数据。
- `/metrics` 不可达或指标缺失时，页面显示暂无数据和错误原因，不使用固定值或随机值兜底。
