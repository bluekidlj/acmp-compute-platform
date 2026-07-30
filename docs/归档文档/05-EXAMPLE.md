# 1.0 API 调用示例

## 1. 启动

```bash
# 本地
mvn spring-boot:run

# 或 Docker
docker build -t acmp-compute:latest .
docker run -d -p 8080:8080 \
  -e JWT_SECRET=your-secret \
  -e AES_KEY=acmp32byteskey!!!!!!!!!!!!!!!!!! \
  acmp-compute:latest
```

## 2. 登录

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

响应：
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "admin",
  "role": "PLATFORM_ADMIN",
  "expiresInMs": 86400000
}
```

```bash
export TOKEN="<粘贴上一步的 token>"
```

## 3. 注册物理集群

```bash
curl -s -X POST http://localhost:8080/api/v1/clusters \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "bj-k8s-01",
    "kubeconfigBase64": "<粘贴 kubeconfig 文件内容或 Base64>",
    "gpuTypes": "NVIDIA",
    "location": "beijing"
  }'
```

记下返回的 `id`（下称 `$CLUSTER_ID`）。

## 4. 扫描集群（可选，推荐）

```bash
curl -s -X POST "http://localhost:8080/api/v1/clusters/$CLUSTER_ID/scan" \
  -H "Authorization: Bearer $TOKEN"
```

返回 `ScanResult`：
```json
{
  "scannedAt": "...",
  "nodeCount": 4,
  "gpuModelCount": 1,
  "splitCount": 5,
  "maxCpuCores": 96,
  "maxMemoryGib": 1024,
  "gpuTypes": ["NVIDIA-A100-SXM4-80GB"],
  "splits": [{ "poolLabel": "nvidia-7b", "memMb": 6000, "coresPct": 16, ... }]
}
```

## 5. 查看显卡

```bash
curl -s "http://localhost:8080/api/v1/clusters/$CLUSTER_ID/gpus" \
  -H "Authorization: Bearer $TOKEN"
```

## 6. 创建工作空间（自动建 3 类池）

```bash
curl -s -X POST http://localhost:8080/api/v1/workspaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ai-rd",
    "description": "AI 算法部",
    "clusterId": "'$CLUSTER_ID'",
    "maxPods": 50
  }'
```

记下返回的 `id`（下称 `$WS_ID`）和 `pools` 数组中三类池的 ID（`$POOL_EXCLUSIVE` / `$POOL_SHARED` / `$POOL_OVERSELL`）。

## 7. 修改池容量 + 关联规格

先把规格 ID 查出：
```bash
curl -s "http://localhost:8080/api/v1/specs?poolType=SHARED" \
  -H "Authorization: Bearer $TOKEN"
```

记下 `shared-hami-a100-1/4` 的 `id`（下称 `$SPEC_SHARED`）。

修改共享池：
```bash
curl -s -X PATCH "http://localhost:8080/api/v1/pools/$POOL_SHARED" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "totalNodes": 10,
    "specs": ["'$SPEC_SHARED'"]
  }'
```

## 8. 创建项目

```bash
curl -s -X POST "http://localhost:8080/api/v1/workspaces/$WS_ID/projects" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "llm-team",
    "description": "LLM 算法组"
  }'
```

记下返回 `id`（下称 `$PROJECT_ID`）。

## 9. 分配项目配额

```bash
curl -s -X POST "http://localhost:8080/api/v1/projects/$PROJECT_ID/quotas" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "'$POOL_SHARED'",
    "specId": "'$SPEC_SHARED'",
    "totalNodes": 5
  }'
```

## 10. 部署推理服务

```bash
curl -s -X POST "http://localhost:8080/api/v1/projects/$PROJECT_ID/deployments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen3-svc",
    "specName": "shared-hami-a100-1/4",
    "replicas": 1,
    "image": "vllm/vllm-openai:latest",
    "envVars": { "MODEL_NAME": "Qwen3-14B" },
    "command": "python",
    "args": "-m vllm.entrypoints.openai.api_server --model /models/Qwen3 --host 0.0.0.0 --port 8000",
    "modelSource": "with_weights",
    "modelIdOrPath": "/models",
    "modelName": "Qwen3-14B"
  }'
```

响应：
```json
{
  "id": "uuid",
  "projectId": "$PROJECT_ID",
  "workspaceId": "$WS_ID",
  "resourcePoolId": "$POOL_SHARED",
  "specId": "$SPEC_SHARED",
  "poolType": "SHARED",
  "name": "qwen3-svc",
  "status": "running",
  "serviceUrl": "http://vllm-qwen3-svc-svc.ws-ai-rd-xxxxxxxx.svc.cluster.local:8000",
  "replicas": 1,
  "actualClusterId": "$CLUSTER_ID"
}
```

## 11. 查询部署状态

```bash
curl -s "http://localhost:8080/api/v1/projects/$PROJECT_ID/deployments" \
  -H "Authorization: Bearer $TOKEN"
```

## 12. 删除部署

```bash
curl -s -X DELETE "http://localhost:8080/api/v1/projects/$PROJECT_ID/deployments/$DEPLOYMENT_ID" \
  -H "Authorization: Bearer $TOKEN"
```
