# HAMi 节点级切分配置 MVP

补充知识：
- hami分配的vgpu节点指的是个数，代表一个个体
- 只有配置了gpumem和gpucore之后，才相当于给这个个体赋予了可以使用的资源，才能够被调度
- 在hami的configmap中，节点级别的配置，大于全局级别的配置


## 目标

平台不自行伪造 GPU，也不把一个 Kubernetes Node 拆成多个 Node。每台 Node 上的全部物理 GPU 使用同一个 HAMi 切分比例，由 HAMi device-plugin 在原 Node 上报可调度的 vGPU 资源。

例如一台 Node 有 4 张 GPU，选择 `1/4`：

- 物理 GPU 数量仍为 4；
- 每张卡切成 4 份；
- 预期可调度份额为 `4 × 4 = 16`；
- Kubernetes Node 数量仍为 1。

## HAMi 配置入口

MVP 只使用 HAMi 的节点级 ConfigMap：

```text
ConfigMap: hami-device-plugin
Namespace: hami-system
Data: config.json
```

平台在 JSON 的 `nodeconfig` 数组中维护节点配置：

```json
{
  "nodeconfig": [
    {
      "name": "gpu-worker-01",
      "devicesplitcount": 4,
      "devicememoryscaling": 0.25,
      "devicecorescaling": 0.25
    }
  ]
}
```

平台只替换同名节点配置，保留其他节点。当前 MVP 支持 `1/2`、`1/4`、`1/8`、`1/10`，分别转换为 `devicesplitcount=2/4/8/10`，显存和核心比例转换为 `1 / splitCount`。

## 加入共享池

1. 校验 Node 是 READY，且全部 GPU 未入池。
2. 清理旧 ACMP 标签。
3. 通过 Kubernetes API 读取 `hami-device-plugin` ConfigMap。
4. 删除目标 Node 的旧 `nodeconfig`，写入新切分配置。
5. 更新 ConfigMap。
6. 删除目标 Node 上的 HAMi device-plugin Pod，由 DaemonSet 自动重建。
7. 写入 `acmp.ai/pool-type`、`acmp.ai/gpu-sharing`、`acmp.ai/compute-spec` 等 ACMP 标签。
8. 重新同步节点资源，以 HAMi 实际上报的 vGPU 数量为准。

## 退出资源池或删除规格

删除目标 Node 的 `nodeconfig`，刷新该 Node 的 HAMi device-plugin，再清理 ACMP 标签和数据库关联。删除配置后，节点恢复使用全局整卡配置。

## 代码位置

- `KubernetesClientManager.applyHamiNodeSharing`：写入节点级 HAMi 配置。
- `KubernetesClientManager.removeHamiNodeSharing`：清理节点级 HAMi 配置。
- `ResourcePoolService.joinNode`：共享池加入时应用配置，独享池加入时清理旧配置。
- `ComputeSpecService.delete`：删除规格时清理 HAMi 配置和 ACMP 标签。

## 运行前提

正式环境约定 GPU 节点安装并运行 HAMi，Namespace 固定为 `hami-system`，ConfigMap 名称为 `hami-device-plugin`。为了支持开发测试，平台通过读取该 ConfigMap 判断 HAMi 是否安装：

- 加入独享池：已安装 HAMi 时清理节点配置；未安装时跳过 HAMi 操作，仍可正常入池。
- 加入共享池：已安装 HAMi 时修改节点配置；未安装时拒绝操作，并提示节点不能进行共享切分。
- 删除规格：已安装 HAMi 时清理节点配置；未安装时跳过 HAMi 清理，只清理 ACMP 标签和数据库关系。

如果读取 ConfigMap 返回非 404 的 Kubernetes API 错误，平台会直接返回检测失败，避免在权限或连接异常时继续修改资源池。
