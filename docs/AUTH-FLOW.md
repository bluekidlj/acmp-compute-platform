# 全链路验证：普通用户从登录到部署推理服务

## 1. 完整时序

```
用户 zhangsan (TRAINING_USER)
  │
  ├─ ① POST /api/v1/auth/login { username:"zhangsan", password:"xxx" }
  │     → UserService.loadUserByUsername("zhangsan")
  │     → UserMapper.findByUsername → User entity
  │     → UserMapper.findResourcePoolIdsByUserId → ["pool-ai"]
  │     → JwtTokenProvider.generateToken(userId, username, "TRAINING_USER", ["pool-ai"])
  │     → 返回 { token: "eyJ...", role: "TRAINING_USER", expiresInMs: 86400000 }
  │
  ├─ ② 管理员已将 zhangsan 加入 llm-training 工作空间
  │     POST /api/v1/workspaces/ws-llm/members { userId: "zhangsan" }
  │     → WorkspaceService.addMember("ws-llm", "zhangsan")
  │     → workspace_member INSERT (user_id="zhangsan", workspace_id="ws-llm")
  │     → 纯 DB 操作，不创建 K8s SA
  │
  ├─ ③ 用户携带 token 部署推理服务
  │     POST /api/v1/workspaces/ws-llm/model-deployments
  │     Header: Authorization: Bearer eyJ...
  │     Body: { name:"qwen3-svc", vllmImage:"vllm/vllm-openai:latest", gpuPerReplica:2, replicas:1 }
  │
  ├─ ④ JwtAuthenticationFilter.doFilterInternal()
  │     → 从 Header 提取 token
  │     → jwtTokenProvider.parseToken(token) → Claims
  │     → 构造 UserPrincipal(id="zhangsan", role="TRAINING_USER", resourcePoolIds=["pool-ai"])
  │     → SecurityContextHolder.setAuthentication(auth)
  │
  ├─ ⑤ ModelDeploymentController.deploy("ws-llm", request)
  │     → @PreAuthorize 无（该接口公开给认证用户，权限由 Service 校验）
  │     → modelDeploymentService.deploy("ws-llm", request)
  │
  ├─ ⑥ ModelDeploymentService.deploy()
  │     → ensureCanAccessWorkspace("ws-llm")
  │     │   → workspaceMapper.findMemberIds("ws-llm") → ["zhangsan", "lisi"]
  │     │   → contains(currentUser().getId()) → ✅
  │     → workspaceMapper.findById("ws-llm") → Workspace entity
  │     │   → namespace: "ws-llm-a1b2", primaryClusterId: "cluster-bj"
  │     │   → gpuType: NVIDIA, volcanoQueueName: "queue-ws-llm-a1b2"
  │     → modelDeploymentMapper.insert(record) → status="pending"
  │     → K8sResourceBuilder.buildVllmDeploymentAndService(
  │         deploymentName="vllm-qwen3-svc", serviceName="vllm-qwen3-svc-svc",
  │         namespace="ws-llm-a1b2", image="vllm/vllm-openai:latest", ...)
  │     → clientManager.createVllmDeploymentAndService("cluster-bj", "ws-llm-a1b2", yaml)
  │     │   → getClient("cluster-bj") → KubernetesClient
  │     │   → client.load(yaml).inNamespace("ws-llm-a1b2").serverSideApply()
  │     │   → K8s: 在 ws-llm-a1b2 命名空间创建 Deployment + Service ✅
  │     → record.setStatus("running"), record.setServiceUrl(...)
  │     → modelDeploymentMapper.update(record)
  │
  └─ ⑦ 响应
        → 201 { id:"dep-xxx", status:"running",
                serviceUrl:"http://vllm-qwen3-svc-svc.ws-llm-a1b2.svc.cluster.local:8000" }
```

---

## 2. 权限校验链

```
层1: JWT 认证（JwtAuthenticationFilter）
  └─ token 有效 → UserPrincipal 入 SecurityContext

层2: 平台层权限（ModelDeploymentService.ensureCanAccessWorkspace）
  └─ workspace_member 表查询 → zhangsan ∈ llm-training ✅

层3: K8s RBAC（KubernetesClientManager 使用平台 SA 代理调用）
  └─ workspace 的 SA "sa-ws-llm-a1b2" 在 ns 内有创建 Deployment 权限 ✅
```

---

## 3. 关键 API 变更

| 旧路径 | 新路径 | 说明 |
|--------|--------|------|
| `POST /api/v1/resource-pools/{poolId}/model-deployments` | `POST /api/v1/workspaces/{workspaceId}/model-deployments` | 部署现在面向工作空间 |
| `POST /api/v1/resource-pools/{poolId}/training-jobs` | `POST /api/v1/workspaces/{workspaceId}/training-jobs` | 训练任务面向工作空间 |
| `GET /api/v1/resource-pools/{poolId}/model-deployments` | `GET /api/v1/workspaces/{workspaceId}/model-deployments` | 列表查询 |

---

## 4. 代码改动

| 文件 | 改动 |
|------|------|
| `ModelDeploymentService.java` | `ResourcePoolMapper` → `WorkspaceMapper`；`deploy(workspaceId)`；权限校验改为 workspace 成员检查 |
| `TrainingJobService.java` | `ResourcePoolMapper` → `WorkspaceMapper`；`submit(workspaceId)`；权限校验改为 workspace 成员检查 |
| `ModelDeploymentController.java` | 路径改为 `/api/v1/workspaces/{workspaceId}/model-deployments` |
| `TrainingJobController.java` | 路径改为 `/api/v1/workspaces/{workspaceId}/training-jobs` |

---

## 5. 验证清单

| 步骤 | 状态 |
|------|------|
| 用户登录获取 JWT | ✅ UserService.login() |
| JWT Filter 解析 token | ✅ JwtAuthenticationFilter |
| 用户加入工作空间 | ✅ WorkspaceService.addMember() → DB |
| 用户携带 token 访问 API | ✅ Authorization header |
| 平台校验 workspace 成员 | ✅ ensureCanAccessWorkspace() → workspace_member |
| K8s 部署到正确 namespace | ✅ workspace.namespace + workspace.primaryClusterId |
| K8s RBAC 校验 | ✅ workspace SA + Role + RoleBinding |
