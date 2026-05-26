# 资源池设计文档

## 1. 两级资源模型

```
PhysicalCluster（物理集群）── M2M ──> ResourcePool（逻辑资源池）── 1:N ──> Workspace（工作空间）
   真实 K8s 集群                      资源初次划分                      资源二次分配
   按 GPU 类型/地域划分                按硬件/性能/安全/地域              按项目/团队
```

| 层级 | 管理者 | 划分维度 | 示例 |
|------|--------|---------|------|
| 物理集群 | 平台管理员 | GPU 类型、地域 | 北京 NVIDIA A100 集群 |
| 逻辑资源池 | 平台管理员 | 硬件类型、性能、安全、地域 | A100-80G 训练池、V100 推理池 |
| 工作空间 | 部门管理员 | 项目、团队 | 大模型训练项目、推荐算法项目 |

---

## 2. 物理集群（PhysicalCluster）

### 数据表

| 字段 | 说明 |
|------|------|
| name | 集群名称 |
| kubeconfig_base64_encrypted | AES 加密的 kubeconfig |
| total_gpu_slots | 集群总 GPU 数 |
| gpu_types | GPU 品牌：NVIDIA / HYGON / NVIDIA,HYGON |
| **location** | 地域/机房：beijing / shanghai |

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/physical-clusters` | 注册（含 gpuTypes + location） |
| GET | `/api/v1/physical-clusters` | 列表 |
| GET | `/api/v1/physical-clusters/{id}/capacity` | 实时 GPU/CPU/Memory |
| DELETE | `/api/v1/physical-clusters/{id}` | 删除 |

---

## 3. 逻辑资源池（ResourcePool）

### 设计要点

- **可跨多个物理集群**：一个逻辑池可同时关联北京 NVIDIA 集群和上海 Hygon 集群
- **总配额由管理员设置**：`gpuSlots`、`cpuCores`、`memoryGiB` 是平台管理员给这个逻辑池的总容量
- **追踪工作空间分配**：`allocatedGpuSlots` 记录已分配给下属工作空间的累计值

### 核心认知：配额 = K8s ResourceQuota

**逻辑资源池的配额不是数据库里的一个数字，而是 K8s Namespace 中真实存在的 ResourceQuota 对象。**
`resource_pool.gpu_slots/cpu_cores/memory_gib` 只是 K8s ResourceQuota 的 **DB 备份镜像**。

### 数据表

| 字段 | 来源 | 说明 |
|------|------|------|
| gpu_slots / cpu_cores / memory_gib | **K8s ResourceQuota** | DB 备份镜像 |
| allocated_gpu_slots / cpu_cores / memory_gib | **平台层计算** | 分配给工作空间的累计（K8s 无此概念） |
| hardware_type | | A100-80G / V100 / H100 / CPU-ONLY |
| security_level | | NORMAL / CONFIDENTIAL |
| gpu_type | | NVIDIA / HYGON |
| job_types | | TRAINING / INFERENCE / TRAINING,INFERENCE |

### M2M 关联表

```
resource_pool_physical_cluster (resource_pool_id, physical_cluster_id)
```

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/resource-pools` | 创建（physicalClusterIds 列表） |
| GET | `/api/v1/resource-pools` | 列表 |
| GET | `/api/v1/resource-pools?physicalClusterId=xxx` | 按物理集群查逻辑池 |
| GET | `/api/v1/resource-pools/{id}` | 详情（含 allocated + available） |
| PATCH | `/api/v1/resource-pools/{id}/capacity` | **在线扩缩容**（同步更新 K8s） |

---

## 4. 在线扩缩容

```
PATCH /api/v1/resource-pools/{id}/capacity { "gpuSlots": 200 }

→ DB update gpu_slots=200
→ serverSideApply ResourceQuota（不重启 Pod） ✅
→ serverSideApply Volcano Queue
→ 在运行作业不受影响 ✅
```

---

## 5. 典型流程

```
1. 注册物理集群
   POST /api/v1/physical-clusters
   { "gpuTypes": "NVIDIA", "location": "beijing" }

2. 创建逻辑池（跨 2 个物理集群）
   POST /api/v1/resource-pools
   { "physicalClusterIds": ["bj-nvidia", "sh-nvidia"],
     "gpuSlots": 120, "hardwareType": "A100-80G" }

3. 在线扩容
   PATCH .../capacity { "gpuSlots": 200 }

4. 查看逻辑池详情
   GET .../{id} → allocatedGpuSlots: 30, availableGpuSlots: 170
```

---

## 6. 测试用例

| 场景 | 实现 |
|------|------|
| 物理池查询 | GET /api/v1/physical-clusters |
| 多硬件规格 | gpuTypes / location |
| 物理池→逻辑池 M2M | resource_pool_physical_cluster 表 |
| 逻辑池跨多物理集群 | physicalClusterIds 列表 |
| 作业类型控制 | jobTypes 字段 |
| 划分维度 | hardwareType / securityLevel |
| 在线扩缩容 | PATCH .../capacity → K8s serverSideApply |
| 配额分配追踪 | allocatedGpuSlots 字段 |
