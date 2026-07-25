# Kubernetes 真实 Node 列表 MVP

## 1. 目标

集群详情通过“Node 列表”和“拓扑图”两个 Tab 展示 Kubernetes API 实际返回的 Node。管理员点击 Node 详情后，再查看该节点的 GPU 设备列表。

本次遵守 `rule.md` 的最小原型原则：

- 拓扑只绘制真实 Master、Worker 和它们的管理层级，不创建虚拟节点。
- 不引入额外图表依赖。
- 不实现物理网络发现。
- 只展示管理员判断节点状态所需的核心属性。

## 2. 页面层级

```text
集群列表
  -> 集群详情（Node 列表 / 拓扑图）
      -> Node 详情（节点信息和该节点 GPU 列表）
```

路由：

- `/clusters`
- `/clusters/:clusterId`
- `/clusters/:clusterId/nodes/:nodeId`

## 3. 集群列表

展示集群名称、Kubernetes 版本、节点数、GPU 设备数、状态、最近同步时间和操作。删除“同步信息”列，同步失败通过页面消息提示。

Kubernetes 版本在库存同步时通过 Kubernetes Version API 获取，保存到已有 `physical_cluster.kubernetes_version` 字段。

## 4. Node 列表

数据完全来自 `GET /api/v1/clusters/{clusterId}/nodes`。API 返回几台 Node，列表就展示几条记录。

列表字段：

- 节点名称
- 节点角色：Master、Worker 或 GPU Worker
- Internal IP
- 状态
- CPU Core
- 内存
- GPU 设备数
- 节点详情入口

Master 根据真实 Labels `node-role.kubernetes.io/control-plane` 或 `node-role.kubernetes.io/master` 识别；其他节点显示为 Worker，GPU 数量大于零时显示为 GPU Worker。

## 5. 拓扑图

拓扑图与列表使用相同的 Node 数据。Master 排列在上方，Worker 排列在下方；只有同时存在 Master 和 Worker 时才显示管理层级连线。服务器卡片展示节点名称、Internal IP、状态和 GPU 数量，点击后进入同一个 Node 详情页。

## 6. Node 详情

Node 详情展示节点名称、Internal IP、节点角色、运行状态、CPU、内存、GPU 总数、最近同步时间，以及仅属于当前 Node 的非 OFFLINE GPU 设备列表。Labels 和 Taints 默认只展示数量，管理员按需展开后以标签形式查看。

GPU 列表展示 GPU 编号、品牌、型号、显存、Driver、CUDA、状态、资源池、算力规格和使用状态。

接口：

```http
GET /api/v1/clusters/{clusterId}/nodes/{nodeId}/gpus
```

## 7. 最小数据改造

仅增加 `cluster_node.internal_ip`。Internal IP 来自 `V1Node.status.addresses` 中 `type=InternalIP` 的地址。

不增加节点角色字段，角色从已有 `labels_json` 判断；不增加 GPU 汇总字段，使用现有 `gpu_device` 数据。

## 8. 验收

1. 集群列表显示真实 Kubernetes 版本。
2. 集群详情只显示 Kubernetes 实际 Node。
3. 默认 Node 列表显示名称、角色、IP、状态、CPU、内存和 GPU 数量。
4. 拓扑 Tab 只展示真实 Master 和 Worker。
5. 从列表或拓扑点击详情均进入 Node 详情。
6. Node 详情只显示属于当前 Node 的 GPU。
