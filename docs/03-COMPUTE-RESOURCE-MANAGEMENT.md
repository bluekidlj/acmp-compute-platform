# 算力资源管理

算力资源管理负责回答三个问题：集群里实际有什么 GPU、这些 GPU 以什么方式提供、业务应使用哪一种算力规格。

当前模块由集群管理、资源池和算力规格三部分组成。

## 1. 核心概念

### 1.1 物理集群

物理集群代表一个可通过 kubeconfig 访问的 Kubernetes 集群。ACMP 保存集群连接信息，并从 Kubernetes API 同步 Node、地址、状态、容量、标签和 GPU 资源。

Kubernetes 是集群运行事实来源，ACMP 数据库保存同步后的管理视图。

### 1.2 Node

Node 是当前资源管理的最小操作主体。一个 Node 可以有一张或多张同品牌、同型号 GPU。

平台采用整机入池规则：

- 用户选择一个 Node，而不是逐张选择 GPU；
- Node 上全部 GPU 一次性加入同一个资源池；
- 同一个 Node 不能同时属于独享池和共享池；
- 重新入池前先清理平台之前写入的调度标签和 HAMi 节点配置。

这一约束减少了逐卡维护和调度冲突，也符合 Kubernetes 按 Node 标签筛选主机的方式。

### 1.3 GPU 设备

GPU 设备是 Node 下的物理设备记录。平台同步品牌、型号、显存和可选的 Driver/CUDA 元数据。

设备详情分为两类：

- 调度必需信息：品牌、型号、设备数量、状态；
- 诊断辅助信息：显存、Driver、CUDA、UUID。

辅助信息依赖设备插件、GPU Feature Discovery 或 HAMi 注解。缺失时页面显示为空，但不会伪造数据。

### 1.4 资源池

当前保留两类资源池：

| 类型 | 含义 |
|---|---|
| 独享池 | Pod 使用整张物理 GPU |
| 共享池 | HAMi 将每张物理 GPU 按固定比例形成可调度份额 |

资源池不是 Kubernetes 中的新资源对象，而是 ACMP 对一组 Node、GPU、规格和调度标签的管理抽象。

### 1.5 算力规格

算力规格描述业务申请的一份算力套餐，主要包含：

- GPU 品牌和型号；
- 独享或共享类型；
- GPU 数量或共享比例；
- CPU 和内存；
- GPU 显存等辅助信息；
- 关联资源池和可用规格节点数。

规格节点数是可分配份额，不等同于 Kubernetes Node 数量。

例如一台 Node 有 4 张物理 GPU：

- 独享模式下，可形成 4 个整卡规格节点；
- 共享比例为 `1/4` 时，每张卡形成 4 份，共形成 16 个共享规格节点；
- Kubernetes 中仍然只有 1 个 Node。

## 2. 集群同步

注册集群后，平台通过 Kubernetes API 完成：

1. 读取真实 Node；
2. 同步 Node 状态、角色、IP、CPU、内存、标签和污点；
3. 从 Capacity、Allocatable、标签和 HAMi 注解识别 GPU；
4. 按 Node 保存 GPU 设备；
5. 保留仍然有效的资源池和规格关联。

未安装设备插件时，即使主机执行 `nvidia-smi` 正常，Kubernetes 也可能没有 GPU Capacity，平台因此无法可靠识别可调度 GPU。

代码入口：

- `src/main/java/com/acmp/compute/service/PhysicalClusterService.java`
- `src/main/java/com/acmp/compute/service/ClusterInventoryService.java`
- `src/main/java/com/acmp/compute/k8s/KubernetesClientManager.java`

## 3. 多品牌识别

平台允许一个集群存在多个品牌的 GPU Node，但约定单个 Node 只安装一种品牌设备。

识别信息来自 Kubernetes 标准资源、厂商设备资源符、标签和设备插件注解。平台将厂商差异归一为内部品牌枚举，并在资源池、规格和租户配额页面提供品牌筛选。

多品牌支持的重点不是让一个 Pod 同时请求多个品牌，而是：

- 库存展示能够分类；
- Node 入池时记录品牌；
- 规格按品牌形成；
- 租户配额按品牌和规格分配；
- 部署时映射到对应的 Kubernetes 设备资源符。

## 4. 加入独享池

独享入池流程：

1. 校验 Node 为可用状态且未被其他池占用；
2. 清理旧 ACMP 调度标签；
3. 若检测到 HAMi，清理该 Node 的旧共享配置；
4. 根据品牌、型号和资源参数查找可复用规格；
5. 没有匹配规格时创建新规格；
6. 将 Node 和全部 GPU 关联到独享池与规格；
7. 写入 Kubernetes Node 调度标签；
8. 重新同步并展示结果。

开发测试集群未安装 HAMi 时，独享流程仍可执行；真实共享流程不能跳过 HAMi。

## 5. 加入共享池

共享入池的用户输入是 Node 和切分比例。比例作用于该 Node 上的全部 GPU。

例如选择 `1/4`：

- 平台在 `hami-system/hami-device-plugin` 中写入该 Node 的节点级配置；
- HAMi device-plugin 重新读取配置并上报可调度共享资源；
- 每张物理卡形成 4 个份额；
- 规格中的显存和核心资源按四分之一表达；
- 平台记录总规格节点数和当前可用数。

平台不自行创建虚拟 GPU 设备，也不把一个 Kubernetes Node 拆成多个虚拟 Node。共享资源的实际可调度性以 HAMi 上报为准。

代码入口：

- `src/main/java/com/acmp/compute/service/ResourcePoolService.java`
- `src/main/java/com/acmp/compute/k8s/KubernetesClientManager.java`
- `src/main/java/com/acmp/compute/k8s/KubernetesSchedulingLabels.java`

## 6. 调度标签

Node 入池后，平台写入一组 ACMP 管理标签，主要表达：

```text
acmp.ai/pool-type
acmp.ai/compute-spec
acmp.ai/gpu-brand
acmp.ai/gpu-model
acmp.ai/gpu-sharing
```

推理服务的 Pod 使用相同标签作为 Node Selector，从而被调度到池类型和规格匹配的 Node。

标签只用于调度和平台管理，不替代 Kubernetes 的真实设备资源请求。Pod 仍需请求 `nvidia.com/gpu` 或 HAMi 对应资源符。

## 7. 规格复用

多个 Node 的资源条件一致时，应复用同一个算力规格，而不是为每张 GPU 或每台机器创建重复规格。

匹配维度主要包括：

- 品牌；
- 型号；
- 独享/共享类型；
- 共享比例；
- CPU、内存和 GPU 资源参数；
- 所属资源池。

规格列表显示聚合后的总规格节点数和可用规格节点数。设备列表仍保留来源 Node，便于追溯实际库存。

## 8. 删除规格与重新入池

删除规格表示平台不再管理该规格对应的 Node 资源。平台应同步清理：

- 数据库中的规格与设备关联；
- Node 上的 ACMP 调度标签；
- 共享节点的 HAMi 节点级配置；
- 由该规格形成的资源池统计。

当一个新加入的 Node 已带有历史 ACMP 标签时，平台先清理旧标签，再写入本次选择，避免遗留标签影响调度。

若规格仍被租户配额或推理服务使用，应先解除业务占用，防止已有部署失去调度依据。

删除逻辑入口：

- `src/main/java/com/acmp/compute/service/ComputeSpecService.java`

## 9. 模块边界

算力资源模块负责库存、池化、规格化和调度标记，不负责：

- 直接修改 GPU 固件或驱动；
- 自行实现 vGPU 切分；
- 代替 Kubernetes Scheduler；
- 根据实时利用率自动迁移工作负载；
- 将监控指标反写为资源事实。

这些边界让平台保持为清晰的管理控制面。
