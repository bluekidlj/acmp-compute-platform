# 工作空间（Workspace）设计文档

## 1. 模型定位

ACMP 的资源管理体系是**两级分配**：

```
资源初次划分：PhysicalCluster ──M2M──> ResourcePool（逻辑资源池）
        ↓                              ↑
   平台管理员分配                      按硬件/性能/安全/地域划分
        ↓                              ↑
资源二次分配：ResourcePool ──1:N──> Workspace（工作空间）
                                       ↑
                                  按项目/团队/用户划分
```

- **逻辑资源池**：平台管理员将物理集群总容量划分给逻辑池。一个逻辑池可跨多个物理集群（如同时包含 A100 和 H100）。
- **工作空间**：部门管理员将逻辑池配额分配给各项目工作空间。**一个工作空间只能属于一个逻辑池**。

---

## 2. 数据模型

```
resource_pool (逻辑池)  1 ──── N  workspace
workspace               1 ──── 1  workspace_quota
physical_cluster        N ──── M  resource_pool (via resource_pool_physical_cluster)
```

### workspace 表

| 字段 | 说明 |
|------|------|
| id | UUID PK |
| **resource_pool_id** | 所属逻辑池（N:1，必填） |
| name | 工作空间名称 |
| description | 描述 |
| created_by | 创建者 FK→users |
| status | active / archived |

### workspace_quota 表

| 字段 | 说明 |
|------|------|
| max_gpu_slots | GPU 上限（从父池分配） |
| max_cpu_cores | CPU 上限 |
| max_memory_gib | 内存上限 |
| max_pods | Pod 上限 |
| max_hours | 时长上限（小时） |
| **used_gpu_slots** | 当前已使用（运行时扣减） |
| **used_cpu_cores** | 当前已使用 |
| **used_memory_gib** | 当前已使用 |

---

## 3. 配额管理

### 两级配额体系

```
逻辑池 A（总 120 GPU）
  ├─ 工作空间 1：30 GPU（allocated=30, used=4, available=26）
  ├─ 工作空间 2：20 GPU（allocated=50, used=0, available=20）
  └─ 剩余可分配：70 GPU
```

### 配额生命周期

```
创建 WS（申请 30 GPU）→ 校验 逻辑池.allocated+30 ≤ 逻辑池.total → OK → allocated+=30
提交任务（需 4 GPU）  → 校验 used+4 ≤ max → OK → used+=4
任务结束              → used-=4
删除 WS              → allocated-=30（释放回逻辑池）
```

### 防护规则

| 操作 | 校验 |
|------|------|
| 创建 WS / 扩配额 | `逻辑池.allocated + delta ≤ 逻辑池.total` |
| 提交任务 | `工作空间.used + need ≤ 工作空间.max` |
| 缩配额 | 不受限（释放回父池） |

---

## 4. API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/workspaces` | 创建（含 resourcePoolId + initialGpuSlots） |
| PUT | `/api/v1/workspaces/{id}` | 修改名称/描述 |
| DELETE | `/api/v1/workspaces/{id}` | 删除（自动释放配额回父池） |
| GET | `/api/v1/workspaces` | 列表 |
| GET | `/api/v1/workspaces/{id}` | 详情（resourcePoolName + quota + used/available） |
| PUT | `/api/v1/workspaces/{id}/quota` | 设置/更新配额（校验父池剩余） |
| GET | `/api/v1/workspaces/{id}/quota` | 查询配额 + 用量 |

### 请求示例

```json
POST /api/v1/workspaces
{
  "name": "大模型训练项目",
  "description": "Qwen3 微调",
  "resourcePoolId": "pool-ai-train",
  "initialGpuSlots": 30,
  "initialCpuCores": 120,
  "initialMemoryGib": 512
}
```

### 响应示例

```json
{
  "id": "ws-abc",
  "name": "大模型训练项目",
  "resourcePoolId": "pool-ai-train",
  "resourcePoolName": "AI训练池-A100-80G",
  "quota": {
    "maxGpuSlots": 30, "usedGpuSlots": 4, "availableGpuSlots": 26,
    "maxCpuCores": 120, "usedCpuCores": 16, "availableCpuCores": 104,
    "maxMemoryGib": 512, "usedMemoryGib": 64, "availableMemoryGib": 448
  }
}
```

---

## 5. 典型流程

```
1. 管理员创建逻辑池
   POST /api/v1/resource-pools
   { "gpuSlots": 120, "hardwareType": "A100-80G", "physicalClusterIds": [...] }

2. 创建工作空间
   POST /api/v1/workspaces
   { "resourcePoolId": "...", "initialGpuSlots": 30 }

3. 成员查看可用配额
   GET /api/v1/workspaces/{id} → availableGpuSlots: 30

4. 提交任务 → 扣减 used
5. 任务结束 → 恢复 used
```

---

## 6. 测试用例

| 场景 | 实现 |
|------|------|
| 创建/修改/删除工作空间 | CRUD API |
| 工作空间属于一个逻辑池 | resourcePoolId（N:1） |
| 配额-资源度量 | maxGpuSlots/Cores/Mem + used + available |
| 配额-时长度量 | maxHours |
| 配额不足拒绝任务 | deductQuota() 校验 |
| 任务结束释放 | restoreQuota() |
| 扩配额不超过父池 | validateAndUpdateParentAllocation() |
