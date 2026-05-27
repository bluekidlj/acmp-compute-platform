# 节点纳管功能

## 1. 功能说明

当新主机加入集群后，平台提供 HTTP 接口扫描集群节点，展示节点的算力资源信息，供前端进行纳管操作。

## 2. API

### 2.1 扫描集群节点

```
GET /api/v1/physical-clusters/{clusterId}/nodes
```

**响应示例**：

```json
{
  "nodes": [
    {
      "name": "gpu-node-1",
      "status": "Ready",
      "gpuType": "A100-80GB-SXM",
      "nodeCount": 6,
      "nodeMemMb": 81920,
      "nodeCores": 100,
      "cpuCores": 64,
      "memoryGiB": 256,
      "poolLabels": ["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8"],
      "labelsJson": "{\"pool\":\"nvidia-a100-80g-1/4,nvidia-a100-80g-1/8\",\"nvidia.com/gpu-family\":\"A100-80GB-SXM\"}"
    },
    {
      "name": "dcu-node-1",
      "status": "Ready",
      "gpuType": "Hygon-DCU-32GB",
      "nodeCount": 4,
      "nodeMemMb": 32768,
      "nodeCores": 100,
      "cpuCores": 64,
      "memoryGiB": 256,
      "poolLabels": ["hygon-dcu-32g-1/4"],
      "labelsJson": "{\"pool\":\"hygon-dcu-32g-1/4\",\"amd.com/dcu-family\":\"Hygon-DCU-32GB\"}"
    }
  ],
  "poolLabels": ["nvidia-a100-80g-1/4", "nvidia-a100-80g-1/8", "hygon-dcu-32g-1/4"]
}
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| nodes | 节点列表 |
| nodes[].name | 节点名称 |
| nodes[].status | 节点状态（Ready/NotReady） |
| nodes[].gpuType | GPU 型号（从 nvidia.com/gpu-family 或 amd.com/dcu-family 获取） |
| nodes[].nodeCount | 可用节点数（vGPU 实例数，HAMi 切分后） |
| nodes[].nodeMemMb | 每节点显存 MB（HAMi 切分后） |
| nodes[].nodeCores | 每节点算力百分比（HAMi 切分后） |
| nodes[].cpuCores | CPU 核数 |
| nodes[].memoryGiB | 内存 GiB |
| nodes[].poolLabels | 节点支持的切分规格标签集（逗号分隔多规格） |
| nodes[].labelsJson | 节点全部标签 JSON |
| poolLabels | 集群中所有不重复的 pool 标签枚举（用于资源池创建时选择切分规格） |

## 3. 使用流程

```
1. 前端调用 GET /api/v1/physical-clusters/{clusterId}/nodes
2. 展示所有节点算力信息（nodeCount, poolLabels 等）
3. 用户选择 poolLabels 中的某种规格创建资源池
4. 平台自动生成 ComputeSpec（nodeSelector 匹配 poolLabel）
```

## 4. 相关文件

| 文件 | 说明 |
|------|------|
| `PhysicalClusterController.java` | 新增 `GET /{id}/nodes` |
| `PhysicalClusterService.java` | 新增 `scanNodes()` 方法 |
| `NodeInfoResponse.java` | 节点信息 DTO |