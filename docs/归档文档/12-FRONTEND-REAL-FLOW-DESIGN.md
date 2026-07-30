# ACMP 前端真实功能链路改造设计稿

状态：功能方案和视觉方向已确认，待开始实施  
目标：前端只展示后端已经具备或本轮明确补齐的真实能力，不再使用旧模型和随机 Mock 数据。

## 0. 视觉设计原则

整体定位：

> 高端、克制、专业、具有算力基础设施的科技感，以邮储绿色建立品牌识别。

科技感不依赖大面积高饱和渐变、荧光描边和复杂动画，而来自清晰的信息层级、
精细的状态表达、适度的数据可视化和稳定的交互反馈。

### 0.1 色彩体系

主色使用邮储绿色：

```text
品牌主绿：#007D4C
交互主绿：#008A57
高亮绿色：#21C785
深绿色：  #064D37
浅绿背景：#EAF7F1
```

基础中性色：

```text
页面背景：#F3F7F5
内容背景：#FFFFFF
深色导航：#071D17
主文字：  #17231F
次文字：  #66756F
边框：    #DCE7E2
```

状态色：

```text
成功 / RUNNING：品牌绿色
处理中 / PENDING：科技蓝
警告：琥珀色
失败：红色
未激活：中性灰
```

绿色只用于品牌、主操作、选中状态、正常运行和关键数据，避免所有卡片和图表同时发绿。

### 0.2 明暗结构

- 左侧导航和顶部品牌区域采用深绿色黑底，形成控制中心视觉；
- 主工作区使用浅灰绿色背景和白色内容面板，保证长时间使用的可读性；
- 平台概览顶部可使用一块深色集群状态区域；
- 表单、列表和详情不使用纯深色主题，避免降低信息密度和输入效率；
- 对话页面可以使用深色模型状态栏，消息正文仍使用浅色阅读区。

### 0.3 布局

- 固定左侧导航，宽度约 232px；
- 顶部栏显示当前集群、连接状态、用户和环境标识；
- 内容区最大化利用宽屏，不把核心表格压缩在窄卡片中；
- 页面统一由标题区、关键状态区、筛选/操作区和主体内容组成；
- 表格承担资源列表，Descriptions 承担对象详情，Drawer 承担新增和编辑；
- 避免卡片套卡片和一页堆叠大量统计数字。

### 0.4 组件风格

- 圆角控制在 6–10px，避免过度圆润；
- 阴影轻且少，主要通过边框和背景层级区分区域；
- 主按钮为品牌绿色实底，页面中同一操作区只保留一个主按钮；
- 状态使用圆点、文字和轻量 Tag 共同表达，不只依赖颜色；
- ID、Service URL、镜像和 Kubernetes 名称使用等宽字体；
- 删除操作始终二次确认；
- 空状态明确告诉用户下一步可以做什么。

### 0.5 科技感细节

- 集群 ACTIVE 状态使用轻微呼吸点，不使用整块闪烁；
- SUBMITTED 到 RUNNING 使用简洁的阶段进度；
- Node、Gpu、Pod 和 Service 关系可使用细线拓扑，但只在详情页出现；
- 数字使用等宽数字特性，资源量和状态变化保持视觉稳定；
- 页面切换、抽屉和状态变化使用 150–220ms 的短动效；
- 遵守 `prefers-reduced-motion`，不使用循环背景动画；
- 图标统一使用 Ant Design Icons，不混入 Emoji 或多套图标。

### 0.6 字体和文案

- 中文优先使用系统 UI 字体，数字和代码字段使用等宽字体；
- 页面标题简短，例如“集群”“资源池”“Spec”“推理服务”；
- 统一使用 `Gpu`，不出现 Card；
- 统一使用 `Spec`，不混用 Flavor、规格模板；
- 统一使用“租户”，不出现 Workspace；
- 状态在界面显示中文，悬停时可查看后端原始枚举值；
- 不使用“智能优化”“成功率 99%”等没有真实数据支撑的宣传文案。

### 0.7 响应式范围

- 首要适配 1440×900 和 1920×1080 的演示屏幕；
- 支持 1280px 宽度下完整操作；
- 小于 1100px 时收起侧边栏，表格允许横向滚动；
- 本轮不专门设计手机端资源管理操作。

## 1. 设计结论

现有前端不能继续沿用原来的页面模型。它仍包含 Workspace、三类资源池、Card、
超分、训练、监控随机数和模拟聊天等旧设计，与当前后端主流程不一致。

本轮前端应围绕以下唯一主链路重构：

```text
集群
  → Node / Gpu
  → 独享池 / 共享池
  → Spec
  → 租户规格配额
  → 项目
  → 推理服务
  → Kubernetes Deployment / Pod / Service
  → OpenAI 兼容对话测试
```

本轮不展示后端没有真实数据支撑的训练、告警、存储、数字孪生、策略实验室、
随机监控指标和算力大屏。

## 2. 导航设计

侧边栏精简为三个分组。

### 2.1 概览

- 平台概览

### 2.2 算力资源

- 集群管理
- 资源池
- Spec

### 2.3 业务管理

- 租户
- 项目
- 模型
- 推理服务

右上角保留当前集群选择器，但选项必须来自 `GET /api/v1/clusters`。
删除硬编码集群和 Mock 开关。

## 3. 页面方案

## 3.1 平台概览

只显示可由真实接口计算的内容：

- 集群数量及 ACTIVE 数量；
- Node 总数；
- Gpu 总数；
- 独享池和共享池中的 Gpu 数量；
- 租户、项目、推理服务数量；
- 推理服务状态分布。

不显示 GPU 利用率、P99、TPS、成本、告警等当前没有真实接口的数据。

## 3.2 集群管理

### 列表

- 名称；
- Kubernetes 版本；
- Node 数量；
- Gpu 数量；
- 状态；
- 最近同步时间；
- 同步信息；
- 操作：详情、同步、删除。

### 新增集群

- 集群名称；
- 描述；
- kubeconfig 文件上传；
- 前端读取文本后提交，不展示和持久化 kubeconfig 内容。

### 集群详情

分为两个页签：

1. Node：名称、CPU Core、内存、Gpu 数量、Kubernetes 状态、Labels、Taints；
2. Gpu：编号、型号、显存、驱动、CUDA、状态、所属资源池、使用状态。

页面明确标识模拟 Gpu 数据，避免把 Docker Desktop 标签模拟误认为物理显卡。

## 3.3 资源池

只显示全局唯一的两个池：

- 独享池 `EXCLUSIVE`；
- 共享池 `SHARED`。

每个池显示 Gpu 数量、关联 Spec、状态。详情页显示池中的真实 Gpu 列表。

“加入 Gpu”使用级联选择：

```text
集群 → Node → 空闲 Gpu
```

提交到 `POST /api/v1/resource-pools/{id}/gpus`。已归池 Gpu 不再出现在候选列表。
不提供 Card、移出池和修改归属功能。

## 3.4 Spec

统一使用 `Spec`，界面不再出现 Flavor、规格模板、Card 或超分。

列表字段：

- Spec 名称；
- 展示名称；
- 类型：独享 / 共享；
- 资源池；
- Gpu 型号；
- Gpu 数量；
- CPU Core；
- 内存 GiB；
- 共享比例；
- 状态；
- 操作。

新增/编辑表单：

- 名称；
- 展示名称；
- 类型；
- 资源池；
- Gpu 型号，可选；
- Gpu 数量；
- CPU Core；
- 内存 GiB；
- 共享比例，仅共享类型显示，只允许 `1/8`、`1/4`、`1/2`；
- 描述。

## 3.5 租户

### 租户列表

- 名称；
- 状态；
- 已分配 Spec 数量；
- 项目数量；
- 创建时间；
- 操作：详情、编辑、删除。

### 租户详情

包含两个页签：

1. Spec 配额：Spec、总量、已使用、剩余；支持分配、调整、删除；
2. 项目：租户下的项目列表和创建入口。

配额数量的单位显示为“实例”，不显示为 Gpu 或 Node。

## 3.6 项目

项目始终从租户进入，并显示所属租户。

项目详情包含：

- 基本信息；
- 从租户继承的可用 Spec；
- 每个 Spec 的总量、已使用和剩余；
- 推理服务列表；
- 新建推理服务入口。

前端不再创建项目级二次配额，因为当前后端是租户 Spec 配额模型。

## 3.7 模型

模型页面用于登记模型元数据，不负责下载模型。

千问演示模型预置建议：

```text
名称：Qwen2.5-3B-Instruct
模型标识：Qwen/Qwen2.5-3B-Instruct
模型来源：without_weights
存储后端：huggingface 或 local-path
存储路径：
  联网环境：Qwen/Qwen2.5-3B-Instruct
  内网环境：/models/Qwen2.5-3B-Instruct
```

内网生产演示优先使用本地模型目录，避免 Pod 启动时访问公网。

## 3.8 新建推理服务

使用一个分步抽屉，避免在一张表单里暴露过多底层参数。

### 第一步：业务信息

- 所属租户，只读；
- 所属项目，只读；
- 服务名称；
- 模型：默认 `Qwen2.5-3B-Instruct`；
- 副本数：固定为 1。

### 第二步：算力

- 只列出项目实际可用的 Spec；
- 每项显示 CPU、内存、Gpu、共享比例、剩余配额；
- 剩余为 0 的 Spec 禁用，不阻塞页面其他操作。

### 第三步：运行配置

普通模式只显示：

- vLLM 镜像；
- 服务端口，默认 8000；
- 模型路径；
- 最大上下文长度，可选。

高级配置折叠显示：

- command；
- args；
- 环境变量。

建议生成的真实部署参数：

```json
{
  "name": "qwen25-3b-demo",
  "specName": "<用户选择的 Spec.name>",
  "replicas": 1,
  "image": "vllm/vllm-openai:<内网固定版本>",
  "port": 8000,
  "command": "vllm",
  "args": "serve /models/Qwen2.5-3B-Instruct --served-model-name Qwen2.5-3B-Instruct --host 0.0.0.0 --port 8000",
  "modelId": "<模型记录 ID>",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models/Qwen2.5-3B-Instruct",
  "modelName": "Qwen2.5-3B-Instruct"
}
```

镜像版本必须固定，不使用 `latest`。具体版本在接入内网镜像仓库时确定。

### 提交结果

提交成功后跳转到部署详情，轮询真实状态：

```text
SUBMITTED → PENDING → RUNNING
                    ↘ FAILED
```

轮询间隔建议 3 秒，进入终态后停止。页面离开时取消轮询。

## 3.9 推理服务列表

字段：

- 服务名称；
- 模型；
- 所属租户；
- 所属项目；
- Spec；
- 端口；
- 副本；
- 就绪副本；
- 状态；
- 创建时间；
- 操作：详情、对话、删除。

删除随机生成的 P99、TPS 和延迟。没有监控后端时不伪造指标。

当前后端没有全局部署列表接口。建议新增：

```http
GET /api/v1/deployments
```

支持按 `tenantId`、`projectId` 和 `status` 筛选。这样前端不需要遍历所有租户和项目。

## 3.10 推理服务详情

显示真实字段：

- 平台状态和就绪副本；
- 租户、项目、Spec、资源池；
- 模型、镜像、端口；
- K8s Deployment 和 Service 名称；
- 集群；
- ClusterIP 服务地址；
- 创建时间；
- 删除操作。

不展示随机监控曲线。后续接入 Prometheus 后再增加 TTFT、TPS、延迟和请求量。

状态为 RUNNING 时显示“测试对话”；其他状态按钮禁用，并给出原因。

## 3.11 OpenAI 兼容对话测试

浏览器不能访问 `*.svc.cluster.local`，因此必须由 ACMP 后端代理请求。

新增后端接口：

```http
POST /api/v1/projects/{projectId}/deployments/{deploymentId}/chat/completions
```

请求保持 OpenAI Chat Completions 的核心格式：

```json
{
  "messages": [
    {
      "role": "system",
      "content": "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."
    },
    {
      "role": "user",
      "content": "请用一句话介绍 ACMP。"
    }
  ],
  "temperature": 0.7,
  "top_p": 0.8,
  "max_tokens": 512,
  "stream": false
}
```

后端从部署记录获取 `serviceUrl`、端口和 `modelName`，拼接：

```text
{serviceUrl}/v1/chat/completions
```

后端负责填入 `model`，不接受前端传任意 URL，避免 SSRF。首版只支持非流式响应，
设置连接超时和读取超时，并原样返回 vLLM 的 OpenAI 兼容响应。

对话页面：

- 显示服务、模型和 RUNNING 状态；
- 保留 system、user、assistant 历史；
- Enter 发送，Shift+Enter 换行；
- 请求中禁止重复发送；
- 清空对话只清理浏览器内存；
- 展示 vLLM 返回的真实内容、finish reason 和 usage；
- 错误时展示后端返回的真实错误，不用模拟回答兜底。

首版不做流式输出、会话持久化、多轮服务端存储和 API Key 管理。

## 4. Docker Desktop Demo 与内网 GPU 演示

两种环境使用完全相同的前端和 OpenAI 兼容接口。

### 4.1 Docker Desktop

当前机器没有 CUDA，且 Kubernetes 可分配内存约 2 GiB，不能真实运行
Qwen2.5-3B-Instruct 的 BF16 vLLM 服务。

桌面演示使用一个 OpenAI 兼容的轻量测试服务，真实验证：

- ACMP 部署 API；
- Spec 和配额；
- Deployment、Pod、Service；
- 状态轮询；
- 后端代理；
- 对话请求和响应。

页面必须显示“本地协议模拟服务”，不冒充真实千问推理。

### 4.2 内网 GPU 集群

切换到内网集群后使用：

- 内网 vLLM 固定版本镜像；
- 已预下载到共享存储的 Qwen2.5-3B-Instruct；
- 真实独享或共享 Gpu Spec；
- 同一个 `/v1/chat/completions` 协议；
- 同一个前端对话页面。

## 5. 前端类型和 API 改造

删除旧类型和接口：

- Workspace；
- PoolCard；
- OVERSELL；
- PHYSICAL / VIRTUAL 的旧 Spec 字段；
- 项目二次配额；
- cluster capacity / scan / gpu-splits 旧接口；
- Mock 开关和所有 mock 数据依赖。

新增或对齐：

- `PhysicalCluster`；
- `ClusterNode`；
- `GpuDevice`；
- `ResourcePool`；
- `Spec`；
- `Tenant`；
- `TenantSpecQuota`；
- `Project`；
- `Model`；
- `ModelDeployment`；
- `ChatCompletionRequest / Response`。

所有状态值按后端大写枚举处理，例如 `ACTIVE`、`SUBMITTED`、`RUNNING`、`FAILED`。

## 6. 后端最小补充

为了让前端保持真实且简洁，建议只补两个接口：

1. `GET /api/v1/deployments`：全局推理服务列表；
2. `POST /api/v1/projects/{projectId}/deployments/{deploymentId}/chat/completions`：
   代理 OpenAI 兼容对话。

可选补充：

- 部署详情增加最近一次 Kubernetes 状态原因，例如 `Insufficient memory`；
- 创建部署响应补齐 `createdAt` 和 `updatedAt`。

不在本轮增加 Prometheus、日志平台、WebSocket、会话数据库、Ingress 和复杂网关。

## 7. 实施顺序

1. 清理导航、旧路由、Mock 开关和旧 TypeScript 类型；
2. 对齐真实 API 客户端；
3. 完成集群、Node、Gpu、资源池和 Spec；
4. 完成租户、配额和项目；
5. 完成模型、部署表单、列表、详情和状态轮询；
6. 补后端全局列表与对话代理；
7. 完成 OpenAI 兼容对话页面；
8. 为 Docker Desktop 部署协议模拟服务并做前后端联调；
9. 前端构建和后端编译通过；
10. 留出内网 GPU 环境的真实 Qwen2.5-3B-Instruct 验收步骤。

## 8. 需要确认的决策

建议确认以下方案后再开始改代码：

1. 本轮删除/隐藏训练、监控预警、集群负载、存储、创新实验室和算力大屏；
2. 千问模型确定为 `Qwen/Qwen2.5-3B-Instruct`；
3. Docker Desktop 明示为 OpenAI 协议模拟，内网 GPU 才是真实模型推理；
4. 首版对话使用非流式响应，后续再增加 SSE 流式输出；
5. 后端增加全局部署列表和安全的对话代理两个接口；
6. 前端彻底删除 Mock 开关，不保留真假数据混用模式。
