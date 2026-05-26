# ACMP-Compute HTTP API 参考文档（v2.0）

> 前端 API 层严格按后端契约文档实现，详见 `../../docs/API-REFERENCE.md`。
>
> 本文档列出前端各 API 模块调用的接口速查。

---

## 1. 认证模块 `api/auth.ts`

| 方法 | HTTP | 路径 |
|---|---|---|
| `authApi.login` | `POST` | `/api/v1/auth/login` |

## 2. 物理集群 `api/physicalClusters.ts`

| 方法 | HTTP | 路径 | 权限 |
|---|---|---|---|
| `list` | `GET` | `/api/v1/physical-clusters` | PLATFORM_ADMIN |
| `create` | `POST` | `/api/v1/admin/physical-clusters` | PLATFORM_ADMIN |
| `capacity` | `GET` | `/api/v1/physical-clusters/{id}/capacity` | 已认证 |
| `delete` | `DELETE` | `/api/v1/physical-clusters/{id}` | PLATFORM_ADMIN |

## 3. 算力规格 `api/specs.ts`

| 方法 | HTTP | 路径 | 权限 |
|---|---|---|---|
| `list` | `GET` | `/api/v1/specs` | 已认证 |
| `get` | `GET` | `/api/v1/specs/{id}` | 已认证 |
| `create` | `POST` | `/api/v1/specs` | PLATFORM_ADMIN |
| `delete` | `DELETE` | `/api/v1/specs/{id}` | PLATFORM_ADMIN |

## 4. 逻辑资源池 `api/resourcePools.ts`

| 方法 | HTTP | 路径 | 权限 |
|---|---|---|---|
| `list` | `GET` | `/api/v1/resource-pools` | PLATFORM_ADMIN / ORG_ADMIN |
| `get` | `GET` | `/api/v1/resource-pools/{id}` | 已认证 |
| `create` | `POST` | `/api/v1/admin/resource-pools` | PLATFORM_ADMIN |

## 5. 工作空间 `api/workspaces.ts`

| 方法 | HTTP | 路径 | 权限 |
|---|---|---|---|
| `list` | `GET` | `/api/v1/workspaces` | 已认证 |
| `get` | `GET` | `/api/v1/workspaces/{id}` | 已认证 |
| `create` | `POST` | `/api/v1/workspaces` | PLATFORM_ADMIN / ORG_ADMIN |
| `update` | `PUT` | `/api/v1/workspaces/{id}` | PLATFORM_ADMIN / ORG_ADMIN |
| `delete` | `DELETE` | `/api/v1/workspaces/{id}` | PLATFORM_ADMIN / ORG_ADMIN |
| `members` | `GET` | `/api/v1/workspaces/{id}/members` | 已认证 |
| `addMember` | `POST` | `/api/v1/workspaces/{id}/members` | PLATFORM_ADMIN / ORG_ADMIN |
| `removeMember` | `DELETE` | `/api/v1/workspaces/{id}/members/{userId}` | PLATFORM_ADMIN / ORG_ADMIN |
| `issueCredential` | `POST` | `/api/v1/admin/workspaces/{id}/issue-credential` | PLATFORM_ADMIN |

## 6. 模型推理部署 `api/modelDeployments.ts`

| 方法 | HTTP | 路径 | 权限 |
|---|---|---|---|
| `deploy` | `POST` | `/api/v1/resource-pools/{poolId}/workspaces/{wsId}/model-deployments` | 工作空间成员 |
| `list` | `GET` | `/api/v1/workspaces/{wsId}/model-deployments` | 工作空间成员 |
| `get` | `GET` | `/api/v1/workspaces/{wsId}/model-deployments/{id}` | 工作空间成员 |
| `delete` | `DELETE` | `/api/v1/workspaces/{wsId}/model-deployments/{id}` | 工作空间成员 |

## 7. 训练任务 `api/trainingJobs.ts`

| 方法 | HTTP | 路径 | 权限 |
|---|---|---|---|
| `submit` | `POST` | `/api/v1/workspaces/{wsId}/training-jobs` | 工作空间成员 |

---

## 8. 前端类型定义 `types/index.ts`

所有请求/响应的 TypeScript 类型均定义于此，严格对齐后端 DTO：

- `LoginRequest` / `LoginResponse` — 含 `UserRole` 联合类型
- `PhysicalCluster` / `PhysicalClusterCreateRequest` / `PhysicalClusterCapacity`
- `ComputeSpec` / `SpecCreateRequest` — 含 `GpuBrand` 枚举
- `ResourcePool` / `ResourcePoolCreateRequest` / `SpecQuota`
- `Workspace` / `WorkspaceCreateRequest` / `WorkspaceUpdateRequest` / `WorkspaceSpecQuota`
- `AddMemberRequest` / `IssueCredentialRequest` / `IssueCredentialResponse`
- `VllmDeployRequest` / `ModelDeployment`
- `TrainingJobRequest` / `TrainingJobResponse`
- `ApiError` — 统一错误响应结构
