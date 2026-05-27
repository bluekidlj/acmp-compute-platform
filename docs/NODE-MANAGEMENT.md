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
[
  {
    "name": "gpu-node-1",
    "status": "Ready",
    "gpuType": "A100-80GB-SXM",
    "gpuCount": 6,
    "gpuMemMb": 81920,
    "gpuCores": 100,
    "cpuCores": 64,
    "memoryGiB": 256,
    "poolLabel": "nvidia-a100-80g-1/4",
    "labelsJson": "{\"pool\":\"nvidia-a100-80g-1/4\",\"nvidia.com/gpu-family\":\"A100-80GB-SXM\"}"
  },
  {
    "name": "dcu-node-1",
    "status": "Ready",
    "gpuType": "Hygon-DCU-32GB",
    "gpuCount": 4,
    "gpuMemMb": 32768,
    "gpuCores": 100,
    "cpuCores": 64,
    "memoryGiB": 256,
    "poolLabel": "hygon-dcu-32g-1/4",
    "labelsJson": "{\"pool\":\"hygon-dcu-32g-1/4\",\"amd.com/dcu-family\":\"Hygon-DCU-32GB\"}"
  }
]
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| name | 节点名称 |
| status | 节点状态（Ready/NotReady） |
| gpuType | GPU 型号（从 nvidia.com/gpu-family 或 amd.com/dcu-family 获取） |
| gpuCount | GPU 卡数（nvidia.com/gpu allocatable） |
| gpuMemMb | GPU 显存 MB（HAMi 切分后） |
| gpuCores | GPU 算力百分比（HAMi 切分后） |
| cpuCores | CPU 核数 |
| memoryGiB | 内存 GiB |
| poolLabel | 节点标签 pool 的值 |
| labelsJson | 节点全部标签 JSON |

## 3. 使用流程

```
1. 前端调用 GET /api/v1/physical-clusters/{clusterId}/nodes
2. 展示所有节点算力信息
3. 用户选择节点进行纳管（关联到资源池）
```

## 4. 相关文件

| 文件 | 说明 |
|------|------|
| `PhysicalClusterController.java` | 新增 `GET /{id}/nodes` |
| `PhysicalClusterService.java` | 新增 `scanNodes()` 方法 |
| `NodeInfoResponse.java` | 节点信息 DTO |