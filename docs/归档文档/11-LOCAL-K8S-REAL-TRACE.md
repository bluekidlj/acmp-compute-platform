# Windows 本地 Kubernetes 真实验证记录

验证时间：2026-07-25  
ACMP 地址：`http://127.0.0.1:8080`  
Kubernetes context：`docker-desktop`  
Kubernetes版本：`v1.31.14`

本文只记录实际执行过的请求与响应。JWT、kubeconfig、证书和私钥已脱敏。

## 1. Kubernetes 原始 Node 数据

实际查询结果：

```json
{
  "name": "desktop-control-plane",
  "cpu": "6",
  "memory": "1948796Ki",
  "gpu": "8",
  "gpuModel": "NVIDIA-A100-SXM4-80GB",
  "ready": "True"
}
```

其中 GPU 数量和型号是本地模拟资源，不代表主机存在真实 GPU。

## 2. Kubernetes对象生命周期

实际执行结果：

```text
namespace/acmp-test-lifecycle created
deployment.apps/acmp-test-inference created
service/acmp-test-inference-svc exposed
deployment "acmp-test-inference" successfully rolled out
```

实际对象：

```text
deployment.apps/acmp-test-inference   1/1   AVAILABLE=1
pod/acmp-test-inference-*             1/1   Running
service/acmp-test-inference-svc       ClusterIP   9000/TCP
```

实际校验输出：

```text
K8S_LIFECYCLE_OK readyReplicas=1 port=9000
```

实际删除结果：

```text
namespace "acmp-test-lifecycle" deleted
LIFECYCLE_DELETE_OK
```

## 3. 登录

请求：

```http
POST /api/v1/auth/login
Content-Type: application/json
```

```json
{
  "password": "admin123",
  "username": "admin"
}
```

响应：

```http
HTTP/1.1 200
```

```json
{
  "token": "<REDACTED>",
  "username": "admin",
  "role": "PLATFORM_ADMIN",
  "expiresInMs": 86400000
}
```

## 4. 注册前查询集群

请求：

```http
GET /api/v1/clusters
Authorization: Bearer <REDACTED>
```

响应：

```http
HTTP/1.1 200
```

```json
[]
```

## 5. 第一次注册失败

请求：

```http
POST /api/v1/clusters
Authorization: Bearer <REDACTED>
Content-Type: application/json
```

```json
{
  "name": "acmp-local-docker-desktop",
  "description": "Windows Docker Desktop 本地验证集群",
  "kubeconfig": "<REDACTED>"
}
```

响应：

```http
HTTP/1.1 400
```

```json
{
  "error": "kubeconfig 校验失败，无法连接集群"
}
```

后端实际日志原因：

```text
The field `runtimeHandlers` in the JSON string is not defined
in the `V1NodeStatus` properties.
```

根因是原 `client-java 20.0.1` 对应 Kubernetes 1.28，无法反序列化
Kubernetes 1.31 NodeStatus 新增的 `runtimeHandlers`。客户端升级为
`client-java 22.0.1` 后重放完全相同的请求。

## 6. 升级客户端后注册成功

请求：

```http
POST /api/v1/clusters
Authorization: Bearer <REDACTED>
Content-Type: application/json
```

```json
{
  "name": "acmp-local-docker-desktop",
  "description": "Windows Docker Desktop 本地验证集群",
  "kubeconfig": "<REDACTED>"
}
```

响应：

```http
HTTP/1.1 201
```

```json
{
  "id": "39d08039-fa14-4f1b-a4b5-3216609d19eb",
  "name": "acmp-local-docker-desktop",
  "description": "Windows Docker Desktop 本地验证集群",
  "status": "ACTIVE",
  "kubernetesVersion": null,
  "nodeCount": 1,
  "gpuCount": 8,
  "lastSyncAt": "2026-07-25T04:36:07.863079Z",
  "syncMessage": "同步成功: nodes=1, gpus=8",
  "createdAt": "2026-07-25T04:36:07.559383Z",
  "updatedAt": "2026-07-25T04:36:07.559383Z"
}
```

## 7. 查询同步后的Node

请求：

```http
GET /api/v1/clusters/39d08039-fa14-4f1b-a4b5-3216609d19eb/nodes
Authorization: Bearer <REDACTED>
```

响应：

```http
HTTP/1.1 200
```

```json
[
  {
    "id": "a315f663-9d9f-3e2e-b4c0-419e9a3c20cd",
    "clusterId": "39d08039-fa14-4f1b-a4b5-3216609d19eb",
    "name": "desktop-control-plane",
    "cpuCores": 6,
    "memoryBytes": 1995567104,
    "gpuCount": 8,
    "status": "READY",
    "labelsJson": "{\"beta.kubernetes.io/arch\":\"amd64\",\"beta.kubernetes.io/os\":\"linux\",\"kubernetes.io/arch\":\"amd64\",\"kubernetes.io/hostname\":\"desktop-control-plane\",\"kubernetes.io/os\":\"linux\",\"node-role.kubernetes.io/control-plane\":\"\",\"nvidia.com/gpu.product\":\"NVIDIA-A100-SXM4-80GB\"}",
    "taintsJson": "[]",
    "lastSyncAt": "2026-07-25T04:36:07.559383Z",
    "createdAt": "2026-07-25T04:36:07.559383Z",
    "updatedAt": "2026-07-25T04:36:07.559383Z"
  }
]
```

## 8. 查询同步后的GPU

请求：

```http
GET /api/v1/clusters/39d08039-fa14-4f1b-a4b5-3216609d19eb/gpus
Authorization: Bearer <REDACTED>
```

响应：

```http
HTTP/1.1 200
```

```json
[
  {
    "id": "c702426f-1122-38dd-b2c4-a382a6fc4309",
    "gpuIndex": 0,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "a7146551-0ac4-36a5-bf21-0f2391e7dde6",
    "gpuIndex": 1,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "e5851040-cf57-392b-8c46-167bfea5ec8f",
    "gpuIndex": 2,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "b86502f2-33b5-323f-95d0-8afb1fc998f8",
    "gpuIndex": 3,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "60ca8037-2167-344a-9db2-3e0a0e2743c7",
    "gpuIndex": 4,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "c1e18adc-88cd-3c81-b9e7-02bfc09a1aed",
    "gpuIndex": 5,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "6eab545f-1598-3a4f-a1d7-355fca5699e3",
    "gpuIndex": 6,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  },
  {
    "id": "e8a5a6e5-8140-33bc-aef0-fc04004beb79",
    "gpuIndex": 7,
    "gpuModel": "NVIDIA-A100-SXM4-80GB",
    "memoryMb": null,
    "driverVersion": null,
    "cudaVersion": null,
    "status": "READY",
    "resourcePoolId": null,
    "usageStatus": "IDLE"
  }
]
```

上面的代码块是便于阅读的字段视图。未经删减的真实响应保存在：

[`docs/traces/2026-07-25-gpu-list-response.json`](traces/2026-07-25-gpu-list-response.json)

## 9. 显式重新同步

请求：

```http
POST /api/v1/clusters/39d08039-fa14-4f1b-a4b5-3216609d19eb/sync
Authorization: Bearer <REDACTED>
```

请求体为空。

响应：

```http
HTTP/1.1 200
```

```json
{
  "id": "39d08039-fa14-4f1b-a4b5-3216609d19eb",
  "name": "acmp-local-docker-desktop",
  "description": "Windows Docker Desktop 本地验证集群",
  "status": "ACTIVE",
  "kubernetesVersion": null,
  "nodeCount": 1,
  "gpuCount": 8,
  "lastSyncAt": "2026-07-25T04:36:42.183979Z",
  "syncMessage": "同步成功: nodes=1, gpus=8",
  "createdAt": "2026-07-25T04:36:07.559383Z",
  "updatedAt": "2026-07-25T04:36:42.137733Z"
}
```

## 10. 通过平台 API 创建模拟推理服务

本次使用一个轻量 HTTP 镜像代替 vLLM。这样可以在本机没有显卡的情况下，
验证 ACMP 从 API、规格和配额到 Kubernetes Deployment、Service、Pod 和访问端口的完整编排链路。

请求：

```http
POST /api/v1/projects/bd96843b-120d-4389-86c0-03f87bcc9a8b/deployments
Authorization: Bearer <REDACTED>
Content-Type: application/json
```

```json
{
  "name": "mock-inference-http-9200",
  "specName": "gpu-shared-local-quarter",
  "replicas": 1,
  "image": "hashicorp/http-echo:1.0.0",
  "port": 9200,
  "command": "/http-echo",
  "args": "-listen=:9200 -text=ACMP_LOCAL_INFERENCE_OK",
  "modelIdOrPath": "/models/mock",
  "modelName": "mock-http-model"
}
```

响应：

```http
HTTP/1.1 201
```

```json
{
  "id": "e4bb387c-088f-4169-ae1a-56f8c6c61b58",
  "projectId": "bd96843b-120d-4389-86c0-03f87bcc9a8b",
  "tenantId": "6b5951fc-b58e-4caf-ad04-af1ea47b01e1",
  "resourcePoolId": "pool-shared",
  "specId": "2ca19bbf-5931-41b0-a388-3607f2f5104d",
  "name": "mock-inference-http-9200",
  "modelName": "mock-http-model",
  "modelSource": null,
  "modelIdOrPath": "/models/mock",
  "vllmImage": "hashicorp/http-echo:1.0.0",
  "port": 9200,
  "replicas": 1,
  "k8sDeploymentName": "vllm-mock-inference-http-9200-e4bb38",
  "k8sServiceName": "vllm-mock-inference-http-9200-e4bb38-svc",
  "status": "SUBMITTED",
  "serviceUrl": "http://vllm-mock-inference-http-9200-e4bb38-svc.tenant-6b5951fc.svc.cluster.local:9200",
  "readyReplicas": null,
  "actualClusterId": "39d08039-fa14-4f1b-a4b5-3216609d19eb",
  "createdBy": "user-admin",
  "createdAt": null,
  "updatedAt": null
}
```

## 11. 查询平台部署状态

请求：

```http
GET /api/v1/projects/bd96843b-120d-4389-86c0-03f87bcc9a8b/deployments/e4bb387c-088f-4169-ae1a-56f8c6c61b58
Authorization: Bearer <REDACTED>
```

实际响应中的关键字段：

```json
{
  "id": "e4bb387c-088f-4169-ae1a-56f8c6c61b58",
  "name": "mock-inference-http-9200",
  "port": 9200,
  "replicas": 1,
  "status": "RUNNING",
  "readyReplicas": 1,
  "serviceUrl": "http://vllm-mock-inference-http-9200-e4bb38-svc.tenant-6b5951fc.svc.cluster.local:9200",
  "actualClusterId": "39d08039-fa14-4f1b-a4b5-3216609d19eb",
  "createdAt": "2026-07-25T04:45:33.905382Z",
  "updatedAt": "2026-07-25T04:45:33.905382Z"
}
```

## 12. Kubernetes 实际生成结果

Pod 实际状态：

```text
NAME                                                    READY   STATUS    RESTARTS
vllm-mock-inference-http-9200-e4bb38-57dbc84945-jb98b   1/1     Running   0
```

Deployment 容器关键字段：

```yaml
image: hashicorp/http-echo:1.0.0
command:
  - /http-echo
args:
  - -listen=:9200
  - -text=ACMP_LOCAL_INFERENCE_OK
ports:
  - name: http
    containerPort: 9200
resources:
  requests:
    cpu: "1"
    memory: 1Gi
    nvidia.com/gpu: "1"
    nvidia.com/gpucores: "25"
    nvidia.com/gpumem-percentage: "25"
  limits:
    cpu: "1"
    memory: 1Gi
    nvidia.com/gpu: "1"
    nvidia.com/gpucores: "25"
    nvidia.com/gpumem-percentage: "25"
```

Service 实际端口：

```yaml
type: ClusterIP
clusterIP: 10.96.227.144
ports:
  - name: http
    port: 9200
    targetPort: 9200
```

这证明端口来自部署请求，并没有被固定为 8000。`1/4` 共享规格也正确转换为
HAMi 的 `25` 百分比资源值。

## 13. 真实 HTTP 请求与响应

使用 `kubectl port-forward` 将 ClusterIP Service 的 9200 端口临时映射到本机 19200：

```text
Forwarding from 127.0.0.1:19200 -> 9200
Forwarding from [::1]:19200 -> 9200
```

请求：

```http
GET http://127.0.0.1:19200/
```

响应：

```http
HTTP/1.1 200
Content-Type: text/plain; charset=utf-8

ACMP_LOCAL_INFERENCE_OK
```

## 14. 调度失败路径

在已有一个 1 GiB 实例运行时，又通过平台创建了一个使用 1 GiB 内存的 9100 端口实例。
Docker Desktop 节点可分配内存不足，Pod 保持 Pending，调度器实际事件为：

```text
0/1 nodes are available: 1 Insufficient memory.
```

平台没有把这个未就绪实例误报为 RUNNING。测试后通过平台删除两个临时部署，
Kubernetes 中对应的 Deployment、Pod 和 Service 均被删除，规格配额得到释放。

## 15. 当前结论

- Docker Desktop Kubernetes 对象生命周期通过；
- ACMP 登录和真实 kubeconfig 连接通过；
- Node CPU、内存、Ready 状态和标签同步通过；
- 模拟 GPU 数量和型号同步通过；
- ACMP 推理部署 API 到 Deployment、Pod、Service 的编排链路通过；
- 自定义 9200 容器端口、Service 端口和服务 URL 均正确；
- `1/4` 共享 Gpu 规格正确生成 HAMi 的 25% 资源参数；
- 服务经 Kubernetes Service 真实访问返回 HTTP 200；
- 内存不足时 Pod 保持 Pending，平台没有误报运行成功；
- 本次验证的是模拟推理 HTTP 服务，不代表真实 vLLM、CUDA、HAMi 和物理 GPU 推理已通过；
- HAMi、CUDA 和真实模型推理仍需在内网 GPU 集群执行后续验收。
