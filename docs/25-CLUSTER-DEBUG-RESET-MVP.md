# 集群调试重置 MVP

## 1. 使用目的

该功能只用于当前单管理员 Demo 环境。在反复演示 Node 入池、算力规格生成和推理服务部署后，可一次清理平台生成的集群资源关系，再从已登记的 kubeconfig 重新发现真实 Node 和 GPU。

## 2. 执行前提

- 只能由平台管理员执行。
- `model_deployment` 中不能存在任何推理服务记录，包括 `PENDING`、`SUBMITTED`、`RUNNING` 和 `FAILED`。
- 如有推理服务，先在“推理服务”列表点击“删除”。删除接口会尝试清理对应的 Kubernetes Deployment 和 Service；如果问题集群的 API 已不可达，则记录警告日志并继续释放配额、删除平台记录。
- 前端要求输入 `RESET`，后端也会校验该确认文本。

## 3. 清理与保留范围

### 清理内容

1. Kubernetes Node 上由 ACMP 管理的四个调度标签：
   - `acmp.ai/pool-type`
   - `acmp.ai/compute-spec`
   - `acmp.ai/gpu-brand`
   - `acmp.ai/gpu-model`
2. 租户算力规格配额。
3. 算力规格。
4. 数据库中的 GPU 和 Node 库存。
5. 集群的 Kubernetes 版本、节点数、GPU 数和上次同步结果。

### 保留内容

- 平台用户。
- 租户和项目。
- 项目成员关系 `project_member`。
- 模型系列和已登记模型。
- 独享池、共享池。
- 物理集群记录及其 kubeconfig。

平台不维护租户成员关系，遗留的 `tenant_member` 表在应用启动执行 H2 Schema 时会被删除。项目权限仍使用 `project_member`，不在本次移除范围内。

## 4. 后端接口

```http
POST /api/v1/clusters/reset-all
Authorization: Bearer <PLATFORM_ADMIN_TOKEN>
Content-Type: application/json

{
  "confirmation": "RESET"
}
```

响应示例：

```json
{
  "success": true,
  "clearedQuotaCount": 2,
  "clearedSpecCount": 2,
  "clusters": [
    {
      "clusterId": "cluster-id",
      "clusterName": "acmp-local",
      "success": true,
      "clearedNodeLabelCount": 2,
      "message": "标签已清理并重新同步完成"
    }
  ]
}
```

## 5. 执行顺序

1. 检查推理服务表必须为空。
2. 清除全部租户规格配额和算力规格。
3. 按集群读取 kubeconfig，并清除真实 Node 上的 ACMP 标签。
4. 清除该集群的 Node/GPU 库存。
5. 调用现有集群同步流程，重新读取 Kubernetes 版本、Node 和 GPU。
6. 返回每个集群的处理结果。

该 Demo 流程不实现数据库与 Kubernetes 的分布式事务。每个关键阶段都会打印集群 ID、集群名称、处理阶段和错误原因；单个集群失败后继续处理其他集群，便于离线环境排查。

## 6. 页面操作

入口：`算力资源 → 集群管理 → 重置全部集群`。

确认弹窗会明确展示清理和保留范围。输入 `RESET` 后才可提交。执行完成后页面重新加载集群列表；若部分集群失败，应结合后端日志中的“集群重置阶段”和“K8S Node 标签清理”定位问题。
