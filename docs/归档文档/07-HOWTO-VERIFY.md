# 换个环境验证手册（How-To Verify in a Fresh Environment）

> 本文档面向**换了一台新机器**的验证者：拿到 acmp-compute 1.0 工程后，**从零开始**完整复现 1.0 的端到端验证。
> 本机 WSL2 + Docker 已实测 Docker Hub 拉不到镜像（代理 127.0.0.1:7897 不可用），故需换到**能直连 Docker Hub** 或**公司有镜像加速**的环境。
> 文档自包含，不依赖项目内其他文档。

---

## 一、目标环境最低要求

| 组件 | 版本 | 说明 |
|---|---|---|
| OS | Linux / macOS / WSL2 | 推荐 Ubuntu 22.04+ |
| Docker | ≥ 20.10 | 跑 kind 节点用 |
| 内存 | ≥ 4 GB | kind 1 节点要 ~2 GB |
| 磁盘 | ≥ 5 GB | kind 镜像 + 项目 |
| 网络 | 可访问 `registry-1.docker.io`（或公司镜像加速） | 拉 kindest/node 镜像 |
| JDK | 11+ | Maven 编译用 |
| Maven | 3.8+ | 构建 |
| Python3 | 3.6+ | verify.sh 解析 JSON |
| bash / curl / kubectl | - | 自动化脚本 |

> **可选**：把 `*.mirror.example.com` 配成公司镜像加速（`docker pull` 测试一次能拉即通）。

---

## 二、一键准备（从 0 到能跑 verify.sh）

### 2.1 准备工具

```bash
# 1. Docker（一般公司机器已装；个人机参考 https://docs.docker.com/engine/install/）
docker --version

# 2. kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
sudo mv kubectl /usr/local/bin/
kubectl version --client

# 3. kind
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.23.0/kind-linux-amd64
chmod +x ./kind
sudo mv ./kind /usr/local/bin/kind
kind version

# 4. JDK 11+（Ubuntu 例子）
sudo apt update && sudo apt install -y openjdk-17-jdk
java -version

# 5. Maven 3.8+（Ubuntu 例子）
sudo apt install -y maven
mvn -version

# 6. Python3 + curl（Ubuntu 自带；其他发行版自行装）
python3 --version
curl --version
```

> **WSL2 用户注意**：所有命令在 WSL2 内执行，Docker Desktop 要启用 WSL2 集成。

### 2.2 验证 Docker Hub 通

```bash
docker pull hello-world
docker run --rm hello-world
```

如果失败，**整个验证流程不可行**——先解决网络（公司代理 / 镜像加速）。

### 2.3 把工程拿到机器上

```bash
# 方式 A：git clone
git clone <你的 acmp-compute 仓库地址>
cd acmp-compute-platform/acmp-compute

# 方式 B：scp 拷贝
scp -r user@old-machine:/path/to/acmp-compute-platform .
cd acmp-compute-platform/acmp-compute

# 方式 C：直接复制项目目录到本机
# （略）
```

### 2.4 编译（确认本地能 build）

```bash
mvn clean package -DskipTests
# 期望最后输出: BUILD SUCCESS
# 产物: target/acmp-compute-1.0.0-SNAPSHOT.jar
```

**如果编译失败**：贴 mvn 输出给我，我修。

---

## 三、启动 kind + 注入测试数据

### 3.1 创建 kind 集群

```bash
kind create cluster --config scripts/kind-cluster.yaml
```

> **重要**：Windows / macOS 上 `docker` 是 Docker Desktop；Linux 上直接是 daemon。

预期输出末尾：
```
You have kind created a cluster with:
- 1 control-plane nodes
- 0 worker nodes
Set kubectl context to "kind-acmp-test"
```

### 3.2 校验 kubectl 能用

```bash
kubectl get nodes
# 期望 1 行 READY，NAME 类似 kind-control-plane
```

### 3.3 注入测试数据

```bash
# 拿节点名
NODE=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
echo "节点名: $NODE"

# 打 7 个 spec 对应 label
bash scripts/seed-labels.sh "$NODE"
# 期望末尾看到 6+ 个 pool=... 标签

# 打模拟 HAMi 注解
bash scripts/seed-hami-annotations.sh "$NODE"
# 期望末尾看到 nvidia.com/virtualization-group-* 注解

# 注入 nvidia.com/gpu allocatable（模拟 device plugin）
bash scripts/install-nvidia-plugin.sh "$NODE"
# 期望看到 nvidia.com/gpu: 1
```

**任何一步报错**：贴出来。

### 3.4 手动验证 K8s 端

```bash
kubectl get node "$NODE" --show-labels | tr ',' '\n' | grep pool
# 期望 6+ 行 pool=...

kubectl get node "$NODE" -o jsonpath='{.metadata.annotations}' | tr ',' '\n' | grep nvidia
# 期望 5+ 行 nvidia.com/...

kubectl get node "$NODE" -o jsonpath='{.status.allocatable}'
# 期望含 nvidia.com/gpu: 1
```

---

## 四、启动 acmp-compute

### 4.1 启动（后台跑）

```bash
cd <项目根目录>
nohup mvn spring-boot:run > /tmp/acmp.log 2>&1 &
# 记下 PID: echo $!
```

### 4.2 等启动

```bash
# 等待约 20-30 秒
for i in 1 2 3 4 5 6; do
  if curl -s http://localhost:8080/api/v1/auth/login \
       -X POST -H "Content-Type: application/json" \
       -d '{"username":"admin","password":"admin123"}' 2>/dev/null | grep -q token; then
    echo "✓ 启动成功"
    break
  fi
  echo "  等待中... ($i)"
  sleep 5
done
```

### 4.3 看启动日志

```bash
tail -50 /tmp/acmp.log
# 关键：看到 "Started AcmpComputeApplication" 算成功
# 看到 7 条预置规格 INSERT（看 schema-h2.sql 的 MERGE INTO compute_spec）
```

---

## 五、跑 verify.sh（全链路 Happy Path）

```bash
bash scripts/verify.sh
```

**期望**：14 步全部通过，最终打印：
```
════════════════════════════════════════
  验证结果：通过 N  /  失败 0
════════════════════════════════════════
✅ 全部通过
```

**任一失败**：
1. 把完整输出复制（`bash scripts/verify.sh 2>&1 | tee /tmp/verify.log`）
2. 看 `❌` 哪一步
3. 贴给我，我看代码修

### 常见 fail 与排查

| Fail | 原因 | 排查 |
|---|---|---|
| 预置规格数量 ≠ 7 | H2 库没重生 | 删除 `data/acmp.mv.db` 重启 |
| 注册集群 401 | token 没拿到 | 看 verify.sh 前 5 行 |
| 扫描返回 nodeCount=0 | 没跑 `install-nvidia-plugin.sh` | 补跑 |
| 创建 WS 后 K8s NS 不存在 | kind 节点不可达 | `kubectl get nodes` 验证 |
| PATCH 池后 K8s ResourceQuota 数 ≠ 1 | 老 bug 修了 | 应只剩 1 个；若 > 1 请贴日志 |
| 部署后 K8s Deployment 找不到 | `actual_cluster_id` 路径问题 | 看 `kubectl get deploy -A` |
| OVERSELL 部署后 K8s 出现 deployment | OVERSELL 跳 K8s 逻辑失效 | 贴日志 |

---

## 六、跑 verify-failures.sh（失败注入）

```bash
bash scripts/verify-failures.sh
```

**期望**：所有失败注入场景返回对应 HTTP 状态（见脚本表）。

---

## 七、查看对账报告

```bash
# 登录拿 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")

# 触发对账
curl -s "http://localhost:8080/api/v1/admin/audit/deployments" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**期望**：
```json
{
  "generatedAt": "2026-06-15T...",
  "totalDeployments": N,
  "orphanCount": 0,
  "quotaMismatchCount": 0,
  "orphanDeployments": [],
  "quotaMismatches": []
}
```

若 `orphanCount > 0` 或 `quotaMismatchCount > 0`：把 JSON 贴出来。

---

## 八、清理

```bash
# 停 acmp-compute
kill <PID>
# 或：pkill -f spring-boot:run

# 删 kind 集群
kind delete cluster --name acmp-test

# 删 H2 数据（可选，下次启动重建）
rm -rf data/acmp.mv.db data/acmp.trace.db
```

---

## 九、把输出贴给我的格式

如果你遇到问题需要我修，**完整贴这些**：

```bash
# 1. 工具版本
echo "=== ENV ==="
docker --version
kind version
kubectl version --client
java -version
mvn -version
python3 --version

# 2. 启动日志
echo "=== ACMP LOG (last 100) ==="
tail -100 /tmp/acmp.log

# 3. 失败那一步的完整输出
echo "=== VERIFY OUTPUT ==="
bash scripts/verify.sh 2>&1

# 4. 失败时 K8s 端
echo "=== K8s STATE ==="
kubectl get all -A
kubectl get events -A --sort-by='.lastTimestamp' | tail -30
```

把以上 4 段一起发给我，我能精确定位问题。

---

## 十、验证项速查表（30 秒看完）

| 验证目标 | 命令 | 通过标志 |
|---|---|---|
| 编译通过 | `mvn clean package -DskipTests` | `BUILD SUCCESS` |
| kind 起得来 | `kind create cluster --config scripts/kind-cluster.yaml` | `Set kubectl context to ...` |
| 节点打 label | `bash scripts/seed-labels.sh $NODE` | 末尾 `pool=exclusive-...` 6+ 行 |
| 节点打 hami 注解 | `bash scripts/seed-hami-annotations.sh $NODE` | 5+ 行 `nvidia.com/virtualization-group-*` |
| 注入 GPU allocatable | `bash scripts/install-nvidia-plugin.sh $NODE` | 看到 `nvidia.com/gpu: 1` |
| 应用启动 | `nohup mvn spring-boot:run` + 等 30s | `curl /api/v1/auth/login` 返回 token |
| 7 条预置规格 | `GET /api/v1/specs` | 长度 = 7 |
| 全链路 happy | `bash scripts/verify.sh` | 退出码 0 |
| 失败注入 | `bash scripts/verify-failures.sh` | 退出码 0 |
| 对账 | `GET /api/v1/admin/audit/deployments` | orphanCount=0, quotaMismatchCount=0 |

---

## 十一、若有 WSL2 的特殊坑

### Docker Desktop WSL2 集成
```
Docker Desktop → Settings → Resources → WSL Integration → 启用
```
重启 Docker Desktop，再 `wsl -e docker ps` 看是否通。

### 端口冲突
acmp 用 8080。如果本机 Windows 也跑了别的占 8080，会冲突。`lsof -i :8080` 检查，或改 `application.yml`：
```yaml
server:
  port: 18080
```

### mvn 在 WSL2 内的性能
WSL2 上 mvn 比 Linux 慢，**首次启动 60-90 秒**属正常。日志看到 `BUILD SUCCESS` 前请耐心。

---

## 十二、文档关系

| 想了解什么 | 看 |
|---|---|
| 1.0 整体架构 | `docs/01-ARCHITECTURE.md` |
| 对象模型 | `docs/02-RESOURCE-MODEL.md` |
| API 完整列表 | `docs/03-API-REFERENCE.md` |
| 部署流程 | `docs/04-DEPLOYMENT-FLOW.md` |
| curl 示例 | `docs/05-EXAMPLE.md` |
| 验证报告（V1 修复 + 测试矩阵 + 已知限制） | `docs/06-VERIFICATION.md` |
| **本手册（换个环境验证）** | **`docs/07-HOWTO-VERIFY.md`**（你正在看） |
| Docker 部署 | `docs/DEPLOY.md` |

---

## 十三、开始干

照本文档 §二 → §三 → §四 → §五 → §六 → §七 顺序执行。

**遇到任何 fail 贴输出给我**。祝你一次跑通。🍀
