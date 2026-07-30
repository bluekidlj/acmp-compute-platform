# 1.0 验证报告

> 本文档记录 1.0 改动后的代码修复 + 验证步骤 + 测试用例 + 已知限制。
> 配套脚本在 `scripts/` 目录，文档在 `docs/` 目录。

## 一、本次改动清单（V1 修复）

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 1 | `ModelDeploymentService.java` | 部署时 `poolMapper.updateAllocated(poolId, poolAlloc)` 读出来又写回同一个值，**无效写**且会误导 | 删该行 |
| 2 | `KubernetesClientManager.java` | `createResourceQuotaBySpec` 用 `serverSideApply`，**多次 PATCH 同一池会创建多个 quota 对象** | 改 `createOrReplace` |
| 3 | `ModelDeploymentService.java` (getStatus/delete) | 用 `ws.getPrimaryClusterId()` 查询 K8s，**应优先用 `record.actualClusterId`** | 加 fallback：record 优先，否则用 ws 字段 |
| 4 | `ModelDeploymentService.java` (deploy) | K8s submit 成功后没显式持久化 `actual_cluster_id`（仅在 insert 时设了，**后续 OOM/重启后会丢**） | 提交成功后多调一次 `updateActualClusterId` |

## 二、明确不做的事（高并发相关）

> **1.0 算力管理平台是内部运维工具，并发上限个位数。** 任何"分布式锁 / 原子 SQL / 行级锁 / 乐观锁 / 悲观锁 / 实时一致性监控"的设计都是**过度工程**。本节明确写下，避免后续被误加回来。

| 不做 | 原因 |
|---|---|
| 原子 SQL 扣减配额 | 两人同时部署超扣 1~2 个，运维场景可接受 |
| `SELECT ... FOR UPDATE` 行锁 | 同上 |
| 分布式锁 | 内部工具，无多实例协调需求 |
| 实时一致性监控 | 5 分钟定时对账足够 |
| 对账后自动修复 | 改为人工判断（脚本给出 log.warn） |
| 多实例 scheduler 协调 | 单实例部署，对账任务串行跑 |

> 未来若真出现"用户拍桌子说配额被偷"，**在 `ProjectResourceQuotaMapper.deductUsedNodes` 改单条 SQL 影响行数判定即可，调用方零改动**。

## 三、测试环境

### 3.1 推荐：kind + 模拟 NVIDIA 设备

| 组件 | 作用 |
|---|---|
| `kind` (≥ 0.20) | 本地一键启 1 节点 K8s 集群 |
| `scripts/kind-cluster.yaml` | 1 control-plane 节点 + 关闭默认端口映射 |
| `scripts/seed-labels.sh` | 给节点打 7 个 spec 对应 label |
| `scripts/seed-hami-annotations.sh` | 模拟 HAMi `virtualization-group-*` 注解 |
| `scripts/install-nvidia-plugin.sh` | 用 `kubectl patch node status` 注入 `nvidia.com/gpu=1` allocatable |

> **无 GPU 的真实场景**：把 `install-nvidia-plugin.sh` 换成真实 NVIDIA device plugin DaemonSet 即可（生产环境已自带）。

### 3.2 不需要 kind 的简化方案

如无 kind 环境，可任选其一：
- **生产集群**：直接用真实 K8s，跳过脚本
- **Docker Desktop 自带 K8s**：把脚本里的 `kind` 换成 `kubectl config use-context docker-desktop`
- **远程 K8s**：把 kubeconfig 路径传给 `verify.sh`

## 四、自动化验证

### 4.1 一键运行

```bash
# 0. 启动 kind + 注入数据（首次）
kind create cluster --config scripts/kind-cluster.yaml
bash scripts/seed-labels.sh
bash scripts/seed-hami-annotations.sh
bash scripts/install-nvidia-plugin.sh

# 1. 启动 acmp-compute（新终端）
mvn spring-boot:run

# 2. 跑全链路验证
bash scripts/verify.sh

# 3. 跑失败注入
bash scripts/verify-failures.sh
```

`verify.sh` 14 步共 30+ 断言。任一失败返回非 0 退出码。

### 4.2 测试用例矩阵

#### Happy path（`verify.sh`）

| ID | 场景 | 期望 | 验证点 |
|---|---|---|---|
| H1 | 启动 acmp 初始化 | 7 条预置规格 | `GET /api/v1/specs` 返回 7 |
| H2 | 登录 | 200 + JWT | token 非空 |
| H3 | 注册 kind 集群 | 200 + clusterId | 客户端缓存命中 |
| H4 | 扫描集群 | 写入 gpuTypes / hamiSplits | DB 字段非空 |
| H5 | 查显卡 | A100 出现 | `GET /gpus` 含 `NVIDIA-A100` |
| H6 | 查切分 | nvidia-7b 出现 | `GET /gpu-splits` 含 |
| H7 | 创建工作空间 | 3 类池自动建 | WS 详情 pools 数组长度 = 3 |
| H8 | K8s Namespace 真实存在 | kubectl 看到 | `kubectl get ns` 命中 |
| H9 | PATCH 池容量 | K8s ResourceQuota 出现 | `kubectl get rq -n ws-xx` 数 = 1（**不重复**） |
| H10 | 创建项目 | 200 + projectId | DB 写入 |
| H11 | 分配项目配额 | prq.total=5, pool.allocated+=5 | DB 验证 |
| H12 | 部署 SHARED 规格 | K8s Deployment + Service 出现 | kubectl get deploy/svc 命中 |
| H13 | Pod limits 含 gpumem + platform.io | YAML 正确 | `kubectl get deploy -o jsonpath` |
| H14 | 项目 used=1 | 累加正确 | `GET /projects/{id}` |
| H15 | OVERSELL 部署 | K8s 无 Deployment | kubectl get deploy 数 = 0 |
| H16 | 删除部署 | K8s 删 + used-1 | 双向 |
| H17 | 删除工作空间 | NS + 3 池删 | kubectl + DB |

#### Failure path（`verify-failures.sh`）

| ID | 场景 | 期望 HTTP | 备注 |
|---|---|---|---|
| F1 | `replicas=2` | 400 | 1.0 限 1 |
| F2 | 错 `specName` | 400 | 规格不存在 |
| F3 | 池未关联该规格 | 400 | EXCLUSIVE 规格部署到 SHARED 池项目 |
| F4 | 配额打满 | 400 | 5/5 时第 6 个拒绝 |
| F5 | 错 kubeconfig 注册集群 | 400 | K8s 客户端校验失败 |
| F6 | 无 token | 401 | SecurityFilter 拦截 |
| F7 | 坏镜像部署 | running（K8s 异步） | K8s submit 成功，Pod 后续 ImagePullBackOff 异步 |
| F8 | OVERSELL 部署 | running + 无 K8s | 1.0 已知行为 |

> **F7 说明**：K8s 创建 Deployment 本身不会因为镜像错误而失败（image pull 是异步的），所以 service 收到 200 + status=running。运维通过 `kubectl get pods` 发现 ImagePullBackOff 后手动处理。1.0 接受此行为。

## 五、手工验证 step-by-step

如不能跑脚本，按以下步骤手动验证。

### Step 1：环境
```bash
# 准备 K8s 集群（任意方式）
# 给节点打 label
kubectl label node <node-name> pool=shared-hami-a100-1/4
kubectl label node <node-name> pool=exclusive-nvidia-a100-80g
# 给节点打 HAMi 注解
kubectl annotate node <node-name> nvidia.com/virtualization-group-7b=6000,16
kubectl annotate node <node-name> nvidia.com/gpu-memory=81920
kubectl annotate node <node-name> nvidia.com/gpu.product=NVIDIA-A100-SXM4-80GB
# 模拟 nvidia.com/gpu allocatable
kubectl patch node <node-name> --subresource=status --type=json \
  -p '[{"op":"add","path":"/status/allocatable/nvidia.com~1gpu","value":"1"}]'
```

### Step 2：启动应用
```bash
mvn spring-boot:run
# 启动后访问 http://localhost:8080
# H2 控制台: http://localhost:8080/h2-console
#   JDBC URL: jdbc:h2:file:./data/acmp
#   用户 sa，密码空
```

### Step 3：登录拿 token
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
echo $TOKEN
```

### Step 4：注册集群
```bash
# 拿到 kubeconfig 内容（明文）
KUBECONFIG_CONTENT=$(kubectl config view --raw | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)))")

curl -X POST http://localhost:8080/api/v1/clusters \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"test-cluster\",
    \"kubeconfigBase64\": $KUBECONFIG_CONTENT,
    \"gpuTypes\": \"NVIDIA\",
    \"location\": \"test\"
  }"
# 记下返回的 id → $CLUSTER_ID
```

### Step 5：扫描 + 查显卡
```bash
curl -X POST "http://localhost:8080/api/v1/clusters/$CLUSTER_ID/scan" \
  -H "Authorization: Bearer $TOKEN"

curl "http://localhost:8080/api/v1/clusters/$CLUSTER_ID/gpus" \
  -H "Authorization: Bearer $TOKEN"
# 应看到 A100

curl "http://localhost:8080/api/v1/clusters/$CLUSTER_ID/gpu-splits" \
  -H "Authorization: Bearer $TOKEN"
# 应看到 nvidia-7b
```

### Step 6：创建工作空间
```bash
curl -X POST http://localhost:8080/api/v1/workspaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"ws-test\",
    \"clusterId\": \"$CLUSTER_ID\",
    \"maxPods\": 50
  }"
# 记下 id, namespace, pools[*].id → $WS_ID, $POOL_EXCLUSIVE, $POOL_SHARED, $POOL_OVERSELL

# 校验 K8s
kubectl get namespace $WS_ID-namespace
# 应存在
```

### Step 7：修改池容量
```bash
# 先取 shared 规格 ID
SPEC_SHARED=$(curl "http://localhost:8080/api/v1/specs?poolType=SHARED" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(next(s['id'] for s in d if s['name']=='shared-hami-a100-1/4'))")

curl -X PATCH "http://localhost:8080/api/v1/pools/$POOL_SHARED" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"totalNodes\": 10,
    \"specs\": [\"$SPEC_SHARED\"]
  }"

# 校验 K8s ResourceQuota 数 = 1（不重复）
kubectl get resourcequota -n $WS_ID-namespace --no-headers | wc -l
# 应 = 1
```

### Step 8：项目 + 配额 + 部署
```bash
PROJ_ID=$(curl -X POST "http://localhost:8080/api/v1/workspaces/$WS_ID/projects" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"proj-test"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

curl -X POST "http://localhost:8080/api/v1/projects/$PROJ_ID/quotas" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"poolId\": \"$POOL_SHARED\",
    \"specId\": \"$SPEC_SHARED\",
    \"totalNodes\": 5
  }"

DEP_ID=$(curl -X POST "http://localhost:8080/api/v1/projects/$PROJ_ID/deployments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen3-svc",
    "specName": "shared-hami-a100-1/4",
    "replicas": 1,
    "image": "vllm/vllm-openai:latest",
    "modelSource": "with_weights",
    "modelIdOrPath": "/models"
  }' | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

# 校验 K8s Deployment
kubectl get deploy -n $WS_ID-namespace | grep qwen3
```

### Step 9：审计
```bash
curl "http://localhost:8080/api/v1/admin/audit/deployments" \
  -H "Authorization: Bearer $TOKEN"
# 应无孤儿 / 无偏差
```

### Step 10：清理
```bash
curl -X DELETE "http://localhost:8080/api/v1/projects/$PROJ_ID/deployments/$DEP_ID" \
  -H "Authorization: Bearer $TOKEN"
curl -X DELETE "http://localhost:8080/api/v1/workspaces/$WS_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## 六、关键校验点（真实 K8s API 行为）

| 行为 | 期望 | 验证命令 |
|---|---|---|
| Deployment 有 nodeSelector | spec.nodeSelector 注入 | `kubectl get deploy -o jsonpath='{.spec.template.spec.nodeSelector}'` |
| Deployment 有 tolerations | spec.tolerations 注入 | `kubectl get deploy -o jsonpath='{.spec.template.spec.tolerations}'` |
| Pod limits 含 nvidia.com/gpumem | SHARED 规格时存在 | `kubectl get deploy -o jsonpath='{.spec.template.spec.containers[0].resources.limits}'` |
| Pod limits 含 platform.io/{spec} | 触发 K8s ResourceQuota 累计 | 同上 |
| Pod limits 含 nvidia.com/gpu=1 | EXCLUSIVE 规格时存在 | 同上 |
| ResourceQuota hard = platform.io/{spec} = N | 用户 PATCH 容量时同步 | `kubectl get rq -o yaml` |
| Service ClusterIP 存在 | 8000 端口 | `kubectl get svc` |
| Namespace 含 SA + Role + RoleBinding | 自动建 | `kubectl get sa,role,rolebinding -n $WS` |
| Namespace 含 Volcano Queue | 自动建 | `kubectl get queue` |

## 七、已知限制（1.0）

| 限制 | 说明 | 后续 |
|---|---|---|
| hostPath 模型挂载 | 1 副本 OK；多副本（>1）时其他节点拿不到模型 | 切 PVC / NFS |
| OVERSELL 仅记账 | 不真实分配物理资源 | 接入 MPS / MIG |
| replicas 严格 = 1 | 多副本不暴露 | 后续放开 |
| 配额超扣窗口 | 两人同时部署可能超扣 1~2 | 单条 SQL 影响行数判定 |
| Scheduler 跑在单实例 | 多实例会重复对账 | 暂不解决 |
| 启动期无对账 | DB 与 K8s 不一致时无人知晓 | 5min scheduled 对账 |
| K8s 失败回滚不删 deployment 行 | status=failed 保留 | 设计选择 |
| 模型广场缺 CRUD 完整测试 | 1.0 仅要求 CRUD 可用 | 后续补 |

## 八、对账任务行为

```
启动后 1 分钟首次跑，之后每 5 分钟跑一次：
  ① 遍历所有 model_deployment
  ② 跳过 status != 'running'
  ③ 跳过 OVERSELL 池
  ④ 跳过 actualClusterId 为空
  ⑤ 调 K8s 查该 Deployment
     ├─ K8s 上不存在 → 标记孤儿（log.warn）
     └─ 存在 → 比对 quota.used vs deployment.replicas
                ├─ 相等 → 正常
                └─ 不等 → 标记偏差（log.warn）
```

不修数据，不发告警，仅 log.warn。运维通过日志监控发现后人工处理。

## 九、文件清单（本次改动）

### 新增
- `scripts/kind-cluster.yaml`
- `scripts/seed-labels.sh`
- `scripts/seed-hami-annotations.sh`
- `scripts/install-nvidia-plugin.sh`
- `scripts/verify.sh`
- `scripts/verify-failures.sh`
- `docs/06-VERIFICATION.md`（本文档）

### 修改
- `src/main/java/com/acmp/compute/service/ModelDeploymentService.java`（V1 修复 1/3/4）
- `src/main/java/com/acmp/compute/k8s/KubernetesClientManager.java`（V1 修复 2 + getDeployment）
- `src/main/java/com/acmp/compute/AcmpComputeApplication.java`（@EnableScheduling）

### 新增（V4）
- `src/main/java/com/acmp/compute/dto/AuditReport.java`
- `src/main/java/com/acmp/compute/service/AuditService.java`
- `src/main/java/com/acmp/compute/scheduler/QuotaReconcileScheduler.java`
- `src/main/java/com/acmp/compute/controller/AuditController.java`
- `src/main/java/com/acmp/compute/mapper/ModelDeploymentMapper.java`（+findAll）
- `src/main/resources/mapper/ModelDeploymentMapper.xml`（+findAll SQL）
