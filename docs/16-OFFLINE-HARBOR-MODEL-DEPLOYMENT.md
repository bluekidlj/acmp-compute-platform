# 内网 Harbor、GPU 本地模型与推理服务离线部署方案

## 1. 文档目标

本文面向以下最小内网环境：

| 机器 | 主要职责 |
| --- | --- |
| Master | Kubernetes 控制面、ACMP、Harbor |
| GPU Worker | GPU 驱动、Kubernetes Worker、vLLM Pod、模型本地盘 |

目标是建立一条不依赖内网访问公网的推理部署链路：

```text
外网准备机
  ├─ 下载 Harbor 离线安装包
  ├─ 拉取并导出 vLLM 镜像
  └─ 下载模型权重并生成校验文件
             │
             │ 移动硬盘或受控摆渡介质
             ▼
内网
  ├─ Master：安装 Harbor，导入 vLLM 镜像
  ├─ GPU Worker：保存模型权重到本地 SSD/NVMe
  └─ ACMP：登记模型元数据并创建 vLLM Deployment
```

本文示例使用以下占位地址，实施时必须替换：

```text
Master IP       = 10.10.0.10
GPU Worker IP   = 10.10.0.20
Harbor 域名     = harbor.acmp.local
Harbor 项目     = ai-runtime
模型根目录      = /data/acmp/models
```

## 2. 总体设计

### 2.1 Harbor 只保存容器镜像

Harbor 保存：

- vLLM 运行时镜像；
- ACMP 后端和前端镜像；
- 其他推理辅助镜像。

示例：

```text
harbor.acmp.local/ai-runtime/vllm-openai:0.10.0
```

不要把模型权重打进 vLLM 镜像。否则每次更新模型都会重新构建、推送和拉取一个几十 GB
甚至数百 GB 的镜像，也无法让多个运行时镜像复用同一份权重。

### 2.2 模型权重保存在 GPU Worker 本地盘

两台机器且只有一个 GPU Worker 时，模型最终只会被 GPU Worker 读取。优先使用 GPU Worker
上的 SSD/NVMe：

```text
/data/acmp/models/
├─ Qwen2.5-7B-Instruct/
│  ├─ config.json
│  ├─ tokenizer.json
│  ├─ tokenizer_config.json
│  ├─ model-00001-of-00004.safetensors
│  ├─ model-00002-of-00004.safetensors
│  ├─ model-00003-of-00004.safetensors
│  ├─ model-00004-of-00004.safetensors
│  ├─ model.safetensors.index.json
│  └─ ACMP-MANIFEST.sha256
└─ DeepSeek-R1-Distill-Qwen-7B/
   └─ ...
```

当前 ACMP 通过 Kubernetes `hostPath` 将这个目录只读挂载到 vLLM 容器。以后增加多个
GPU Worker，再升级为 Local PersistentVolume、NFS/Ceph 或对象存储加节点本地缓存。

### 2.3 调度约束

仅请求 `nvidia.com/gpu` 通常能让 Pod 落到 GPU Worker，但模型目录同时具有节点局部性，
因此部署还应包含明确的节点标签：

```bash
kubectl label node <GPU_WORKER_NODE_NAME> acmp.ai/gpu-worker=true
kubectl label node <GPU_WORKER_NODE_NAME> acmp.ai/model-store=local
```

期望的 Pod 约束：

```yaml
spec:
  nodeSelector:
    acmp.ai/gpu-worker: "true"
    acmp.ai/model-store: "local"
```

如果未来不同 GPU Worker 保存不同模型，应为模型建立更细粒度的标签或 Local PV
`nodeAffinity`，不能假设每台 GPU Worker 都有全部模型。

## 3. ACMP 推理部署数据流

### 3.1 模型登记

模型市场只保存元数据，不上传模型文件。建议字段：

| 字段 | 示例 | 含义 |
| --- | --- | --- |
| `name` | `Qwen2.5-7B-Instruct` | 唯一名称，同时作为目录名 |
| `displayName` | `Qwen2.5 7B Instruct` | 页面展示名称 |
| `modelSource` | `with_weights` | 使用本地权重 |
| `storageBackend` | `local_hostpath` | GPU 节点本地路径 |
| `storagePath` | `/data/acmp/models/Qwen2.5-7B-Instruct` | GPU 主机模型完整绝对目录 |
| `fileSizeMb` | `15360` | 可选，用于容量检查 |
| `revision` | 完整 commit hash | 建议新增，锁定模型版本 |
| `checksum` | SHA-256 | 建议新增，验证文件完整性 |
| `status` | `READY` | 建议新增，仅 READY 可部署 |

当前代码使用 `nfs` 表示 NFS 或本地挂载，实际生成 Kubernetes `hostPath`：

```text
storageBackend = nfs
storagePath    = /data/acmp/models/Qwen2.5-7B-Instruct
name           = Qwen2.5-7B-Instruct
```

`storagePath` 必须直接填写 GPU 主机上的完整模型目录，后端不再追加模型名称。

### 3.2 部署请求

部署表单建议填写：

```text
模型             = Qwen2.5-7B-Instruct
vLLM 镜像        = harbor.acmp.local/ai-runtime/vllm-openai:0.10.0
GPU 主机模型路径 = /data/acmp/models/Qwen2.5-7B-Instruct（由模型登记信息只读带出）
容器内模型路径   = /models/Qwen2.5-7B-Instruct
服务端口         = 8000
副本数           = 1
```

ACMP 应生成类似资源：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm-qwen25-7b
spec:
  replicas: 1
  template:
    spec:
      nodeSelector:
        acmp.ai/gpu-worker: "true"
        acmp.ai/model-store: "local"
      imagePullSecrets:
        - name: harbor-pull
      containers:
        - name: vllm
          image: harbor.acmp.local/ai-runtime/vllm-openai:0.10.0
          imagePullPolicy: IfNotPresent
          args:
            - serve
            - /models/Qwen2.5-7B-Instruct
            - --served-model-name
            - Qwen2.5-7B-Instruct
            - --host
            - 0.0.0.0
            - --port
            - "8000"
          resources:
            requests:
              nvidia.com/gpu: "1"
            limits:
              nvidia.com/gpu: "1"
          volumeMounts:
            - name: model
              mountPath: /models/Qwen2.5-7B-Instruct
              readOnly: true
      volumes:
        - name: model
          hostPath:
            path: /data/acmp/models/Qwen2.5-7B-Instruct
            type: Directory
```

### 3.3 部署前检查

ACMP 后端提交 Deployment 前应检查：

1. 模型记录存在且状态为 `READY`；
2. 镜像必须属于允许的 Harbor 域名和项目；
3. 模型宿主机路径由后端直接读取模型登记记录，不能信任部署请求提交任意 `hostPath`；
4. 目标集群至少存在一个满足 GPU 和模型存储标签的 Ready 节点；
5. Tenant Namespace 中存在 Harbor 拉取 Secret；
6. 配额足够；
7. 模型名称、镜像 Tag、模型版本和 SHA-256 写入部署记录，便于审计。

## 4. 外网侧离线介质准备

### 4.1 选择并固定 Harbor 版本

本文以稳定版 `v2.15.1` 为示例。离线环境不要使用 `latest`，应固定版本并保存校验值。
实施前可在外网侧的
[Harbor Releases](https://github.com/goharbor/harbor/releases)
确认组织批准的稳定补丁版本。

下载离线包，而不是 online installer：

```bash
export HARBOR_VERSION=v2.15.1

curl -fLO \
  "https://github.com/goharbor/harbor/releases/download/${HARBOR_VERSION}/harbor-offline-installer-${HARBOR_VERSION}.tgz"

curl -fLO \
  "https://github.com/goharbor/harbor/releases/download/${HARBOR_VERSION}/harbor-offline-installer-${HARBOR_VERSION}.tgz.asc"
```

Harbor 官方说明中，offline installer 已包含 Harbor 所需的预构建镜像，适用于目标主机无法
联网的场景。安装包本身不包含 Docker Engine 和 Docker Compose。

生成介质校验值：

```bash
sha256sum "harbor-offline-installer-${HARBOR_VERSION}.tgz" \
  > "harbor-offline-installer-${HARBOR_VERSION}.tgz.sha256"
```

如需验证官方 GPG 签名，应在外网侧完成公钥获取和签名校验，并将验证记录一起归档。

### 4.2 准备 vLLM 镜像

在与 GPU Worker 相同 CPU 架构的外网 Linux 机器上：

```bash
docker pull vllm/vllm-openai:0.10.0
docker image inspect vllm/vllm-openai:0.10.0 \
  --format '{{index .RepoDigests 0}}'

docker save \
  --output vllm-openai-0.10.0.tar \
  vllm/vllm-openai:0.10.0

sha256sum vllm-openai-0.10.0.tar \
  > vllm-openai-0.10.0.tar.sha256
```

同时记录：

- 原始镜像名；
- RepoDigest；
- 导出文件 SHA-256；
- vLLM 版本；
- CUDA 版本；
- 支持的 GPU 架构。

### 4.3 准备模型

示例：

```bash
python3 -m pip install -U huggingface_hub

hf download Qwen/Qwen2.5-7B-Instruct \
  --revision <FULL_COMMIT_HASH> \
  --local-dir ./Qwen2.5-7B-Instruct
```

模型必须锁定完整 revision，避免外网重新准备介质时同名模型发生变化。

生成文件清单：

```bash
cd Qwen2.5-7B-Instruct
find . -type f ! -name ACMP-MANIFEST.sha256 -print0 \
  | sort -z \
  | xargs -0 sha256sum \
  > ACMP-MANIFEST.sha256
cd ..
```

模型权重通常已经是高密度二进制数据，额外压缩收益有限。可直接打包，便于摆渡：

```bash
tar -cf Qwen2.5-7B-Instruct.tar Qwen2.5-7B-Instruct
sha256sum Qwen2.5-7B-Instruct.tar \
  > Qwen2.5-7B-Instruct.tar.sha256
```

### 4.4 离线介质目录

建议形成不可变交付目录：

```text
acmp-offline-bundle-2026-07/
├─ harbor/
│  ├─ harbor-offline-installer-v2.15.1.tgz
│  ├─ harbor-offline-installer-v2.15.1.tgz.asc
│  └─ harbor-offline-installer-v2.15.1.tgz.sha256
├─ images/
│  ├─ vllm-openai-0.10.0.tar
│  └─ vllm-openai-0.10.0.tar.sha256
├─ models/
│  ├─ Qwen2.5-7B-Instruct.tar
│  └─ Qwen2.5-7B-Instruct.tar.sha256
└─ MANIFEST.txt
```

进入内网后先校验，校验失败时禁止安装或导入：

```bash
sha256sum -c harbor/harbor-offline-installer-v2.15.1.tgz.sha256
sha256sum -c images/vllm-openai-0.10.0.tar.sha256
sha256sum -c models/Qwen2.5-7B-Instruct.tar.sha256
```

## 5. 内网离线安装 Harbor

### 5.1 安装位置

在只有两台机器的情况下，建议 Harbor 通过 Docker Compose 安装在 Master 上、Kubernetes
集群之外。这样可以避免“Kubernetes 拉取镜像依赖 Harbor，而 Harbor 自身又依赖
Kubernetes 拉取镜像”的启动环。

建议准备独立数据盘：

```text
/opt/harbor               安装和配置目录
/srv/harbor/data          Harbor 持久数据
/srv/harbor/cert          TLS 证书
/srv/harbor/backup        配置和数据库备份
```

Harbor 官方最低要求为 2 CPU、4 GB 内存、40 GB 磁盘；推荐 4 CPU、8 GB 内存、160 GB
磁盘。vLLM 镜像较大，数据盘应按镜像版本保留数量额外规划。

### 5.2 前置软件

Harbor 离线安装包不包含以下软件，必须提前准备与内网 Linux 发行版完全匹配的离线软件包：

- Docker Engine 20.10 以上；
- Docker Compose 插件 2.3 以上；
- OpenSSL；
- `tar`；
- `sha256sum`。

安装后检查：

```bash
docker version
docker compose version
openssl version
```

不要从其他 Linux 发行版直接复制 Docker RPM/DEB。应在与 Master 相同版本、相同 CPU
架构的外网镜像机上下载完整依赖集合，再通过介质安装。

### 5.3 配置域名解析

在内网 DNS 添加：

```text
harbor.acmp.local -> 10.10.0.10
```

没有内网 DNS 时，在 Master、GPU Worker 和运维机的 `/etc/hosts` 添加：

```text
10.10.0.10 harbor.acmp.local
```

Harbor `hostname` 不能使用 `localhost`、`127.0.0.1` 或 `0.0.0.0`。

### 5.4 创建内网 CA 和 Harbor 证书

生产环境优先使用组织内部 CA。以下命令仅用于没有内部 PKI 时创建专用离线 CA。
CA 私钥不得复制到 GPU Worker。

```bash
mkdir -p /root/harbor-pki
cd /root/harbor-pki

openssl genrsa -out acmp-root-ca.key 4096

openssl req -x509 -new -nodes -sha512 -days 3650 \
  -subj "/C=CN/ST=Internal/L=Internal/O=ACMP/OU=Platform/CN=ACMP Root CA" \
  -key acmp-root-ca.key \
  -out acmp-root-ca.crt

openssl genrsa -out harbor.acmp.local.key 4096

openssl req -sha512 -new \
  -subj "/C=CN/ST=Internal/L=Internal/O=ACMP/OU=Platform/CN=harbor.acmp.local" \
  -key harbor.acmp.local.key \
  -out harbor.acmp.local.csr
```

创建 `v3.ext`：

```ini
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = harbor.acmp.local
IP.1 = 10.10.0.10
```

签发证书：

```bash
openssl x509 -req -sha512 -days 825 \
  -extfile v3.ext \
  -CA acmp-root-ca.crt \
  -CAkey acmp-root-ca.key \
  -CAcreateserial \
  -in harbor.acmp.local.csr \
  -out harbor.acmp.local.crt

sudo mkdir -p /srv/harbor/cert
sudo cp harbor.acmp.local.crt /srv/harbor/cert/
sudo cp harbor.acmp.local.key /srv/harbor/cert/
sudo chmod 600 /srv/harbor/cert/harbor.acmp.local.key
```

### 5.5 解压和配置 Harbor

```bash
sudo mkdir -p /opt/harbor /srv/harbor/data
sudo tar -xzf harbor-offline-installer-v2.15.1.tgz \
  -C /opt/harbor \
  --strip-components=1

cd /opt/harbor
sudo cp harbor.yml.tmpl harbor.yml
```

修改 `/opt/harbor/harbor.yml` 的关键项：

```yaml
hostname: harbor.acmp.local

http:
  port: 80

https:
  port: 443
  certificate: /srv/harbor/cert/harbor.acmp.local.crt
  private_key: /srv/harbor/cert/harbor.acmp.local.key

harbor_admin_password: <首次安装专用强密码>

database:
  password: <数据库专用强密码>

data_volume: /srv/harbor/data
```

密码不要提交到 Git，也不要沿用示例默认值。

### 5.6 执行离线安装

```bash
cd /opt/harbor
sudo ./install.sh
```

初次安装建议先不启用 Trivy。Harbor 镜像可以离线安装，但 Trivy 漏洞数据库仍需要设计独立
的离线更新流程，否则扫描结果会过期。

检查：

```bash
cd /opt/harbor
sudo docker compose ps
curl --cacert /root/harbor-pki/acmp-root-ca.crt \
  https://harbor.acmp.local/api/v2.0/systeminfo
```

浏览器访问：

```text
https://harbor.acmp.local
```

### 5.7 Harbor 生命周期

```bash
cd /opt/harbor

# 停止
sudo docker compose stop

# 启动
sudo docker compose start

# 查看状态
sudo docker compose ps

# 查看日志
sudo docker compose logs --tail=200
```

升级前必须备份 `/opt/harbor/harbor.yml`、证书和 `/srv/harbor/data`，并严格按照目标版本的
Harbor Upgrade 文档执行，不能直接覆盖安装目录。

## 6. 将 vLLM 镜像导入 Harbor

### 6.1 信任 Harbor CA

在执行 `docker push` 的内网机器上：

```bash
sudo mkdir -p /etc/docker/certs.d/harbor.acmp.local
sudo cp acmp-root-ca.crt \
  /etc/docker/certs.d/harbor.acmp.local/ca.crt
sudo systemctl restart docker
```

### 6.2 创建 Harbor 项目

在 Harbor 页面创建项目：

```text
项目名称：ai-runtime
访问级别：Private
```

建议再创建只具备 Pull 权限的 Robot Account，供 Kubernetes 使用。管理员账号只用于维护，
不要写入 Kubernetes Secret。

### 6.3 导入、标记和推送

```bash
sha256sum -c vllm-openai-0.10.0.tar.sha256

docker load -i vllm-openai-0.10.0.tar

docker tag \
  vllm/vllm-openai:0.10.0 \
  harbor.acmp.local/ai-runtime/vllm-openai:0.10.0

docker login harbor.acmp.local

docker push \
  harbor.acmp.local/ai-runtime/vllm-openai:0.10.0
```

验证：

```bash
docker pull harbor.acmp.local/ai-runtime/vllm-openai:0.10.0
```

生产部署建议最终记录并使用 Harbor 中的镜像 Digest，而不是只依赖可变 Tag。

## 7. 配置 GPU Worker 拉取 Harbor 镜像

### 7.1 配置 containerd 信任 CA

先确认版本：

```bash
containerd --version
```

创建 Registry Host 配置：

```bash
sudo mkdir -p /etc/containerd/certs.d/harbor.acmp.local
sudo cp acmp-root-ca.crt \
  /etc/containerd/certs.d/harbor.acmp.local/ca.crt
```

创建 `/etc/containerd/certs.d/harbor.acmp.local/hosts.toml`：

```toml
server = "https://harbor.acmp.local"

[host."https://harbor.acmp.local"]
  capabilities = ["pull", "resolve"]
  ca = "/etc/containerd/certs.d/harbor.acmp.local/ca.crt"
```

containerd 1.x 的 `/etc/containerd/config.toml` 应包含：

```toml
version = 2

[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
```

containerd 2.x 应使用：

```toml
version = 3

[plugins."io.containerd.cri.v1.images".registry]
  config_path = "/etc/containerd/certs.d"
```

修改主配置后执行：

```bash
sudo systemctl restart containerd
sudo systemctl status containerd --no-pager
```

测试证书和网络：

```bash
curl --cacert /etc/containerd/certs.d/harbor.acmp.local/ca.crt \
  https://harbor.acmp.local/v2/
```

返回 `401 Unauthorized` 代表 TLS、DNS 和 Registry API 已经连通，只是尚未携带凭据。

### 7.2 配置 Kubernetes 拉取凭据

Harbor Private 项目需要 Secret。每个动态 Tenant Namespace 都必须有该 Secret：

```bash
kubectl -n <TENANT_NAMESPACE> create secret docker-registry harbor-pull \
  --docker-server=harbor.acmp.local \
  --docker-username='<HARBOR_ROBOT_USERNAME>' \
  --docker-password='<HARBOR_ROBOT_SECRET>'
```

Deployment PodSpec 必须包含：

```yaml
imagePullSecrets:
  - name: harbor-pull
```

Kubernetes 官方推荐通过 `imagePullSecrets` 将私有仓库凭据传给 CRI。不要把 Robot 密码写进
containerd 明文配置或 ACMP 前端请求。

ACMP 会动态创建 Tenant Namespace，因此正确做法是由平台在 Namespace 创建后同步创建或
复制 `harbor-pull`，并在 `K8sResourceBuilder` 生成的 PodSpec 中引用它。

## 8. 将模型导入 GPU Worker

### 8.1 创建目录

在 GPU Worker 上：

```bash
sudo mkdir -p /data/acmp/models
sudo chown root:root /data/acmp/models
sudo chmod 755 /data/acmp/models
```

### 8.2 校验和解包

```bash
sha256sum -c Qwen2.5-7B-Instruct.tar.sha256

sudo tar -xf Qwen2.5-7B-Instruct.tar \
  -C /data/acmp/models

cd /data/acmp/models/Qwen2.5-7B-Instruct
sha256sum -c ACMP-MANIFEST.sha256

sudo chown -R root:root /data/acmp/models/Qwen2.5-7B-Instruct
sudo chmod -R a=rX /data/acmp/models/Qwen2.5-7B-Instruct
```

检查必须文件：

```bash
test -f config.json
test -f tokenizer_config.json
find . -maxdepth 1 -name '*.safetensors' -print
```

不要把模型放在 Master 后再让 vLLM 从 Master 的普通系统盘跨网络加载。只有一个 GPU
Worker 时，本地 SSD/NVMe 更简单，启动性能和故障边界也更清晰。

### 8.3 节点标记

在具有集群管理权限的机器上：

```bash
kubectl get nodes -o wide
kubectl label node <GPU_WORKER_NODE_NAME> acmp.ai/gpu-worker=true --overwrite
kubectl label node <GPU_WORKER_NODE_NAME> acmp.ai/model-store=local --overwrite
```

## 9. 在 ACMP 中登记和部署

### 9.1 登记模型

进入“模型广场 -> 登记模型”：

```text
模型唯一名称：Qwen2.5-7B-Instruct
展示名称：Qwen2.5 7B Instruct
模型来源：带权重
存储后端：NFS / 本地挂载
模型存储路径：/data/acmp/models
```

登记动作只写入数据库，不上传模型。

当前“精选模型 -> 登记到平台”的预填路径包含模型名，而后端还会再次追加模型名，可能形成：

```text
/models/Qwen3-8B/Qwen3-8B
```

修复前应通过“登记模型”手动填写根目录。

### 9.2 部署推理服务

```text
服务名称：qwen25-7b-demo
模型：Qwen2.5 7B Instruct
算力规格：选择已有 GPU 配额的规格
节点数：1
vLLM 镜像：harbor.acmp.local/ai-runtime/vllm-openai:0.10.0
容器内模型路径：/models/Qwen2.5-7B-Instruct
端口：8000
```

### 9.3 验证

```bash
kubectl -n <TENANT_NAMESPACE> get pod -o wide
kubectl -n <TENANT_NAMESPACE> describe pod <POD_NAME>
kubectl -n <TENANT_NAMESPACE> logs -f <POD_NAME> -c vllm
```

确认：

- Pod 被调度到 GPU Worker；
- `Image` 来自 `harbor.acmp.local`；
- 没有 `ErrImagePull`、`ImagePullBackOff`；
- 没有 `hostPath type check failed`；
- vLLM 日志中的模型路径为容器内路径；
- 模型加载完成后 Service 有 Ready Endpoint。

## 10. ACMP 当前需要补齐的代码点

### P0：部署可用性

1. 修正精选模型登记路径，前端只提交存储根目录；
2. `storageBackend` 增加 `local_hostpath`，避免把 `hostPath` 错称为 NFS；
3. PodSpec 增加 GPU/模型节点 `nodeSelector`；
4. PodSpec 增加 `imagePullSecrets: harbor-pull`；
5. ACMP 创建 Tenant Namespace 后自动配置 Harbor Pull Secret；
6. 部署前校验镜像仓库域名，只允许批准的 Harbor；
7. 宿主机路径只能从后端模型记录生成，禁止前端直接提交任意路径。

### P1：模型治理

1. 模型增加 `revision`、`checksum`、`status`；
2. 增加模型导入校验脚本或节点 Agent；
3. 部署记录保存镜像 Digest 和模型 revision；
4. 增加模型磁盘容量检查；
5. UI 区分“已登记”“文件校验中”“READY”“损坏/缺失”。

### P2：多 GPU Worker

1. 从 `hostPath` 升级为 Local PV；
2. PV 使用 `nodeAffinity` 表达模型所在节点；
3. 引入模型缓存控制器，将对象存储/NFS 模型预热到 GPU Worker；
4. 调度时同时考虑 GPU、模型缓存命中和磁盘容量。

## 11. 故障排查

### 11.1 `x509: certificate signed by unknown authority`

检查：

- GPU Worker 是否安装正确 CA；
- `hosts.toml` 目录是否与 Harbor 域名完全一致；
- Harbor 证书 SAN 是否包含域名；
- containerd `config_path` 是否正确。

不要用 `skip_verify=true` 作为生产修复。

### 11.2 `ImagePullBackOff`

```bash
kubectl -n <TENANT_NAMESPACE> describe pod <POD_NAME>
kubectl -n <TENANT_NAMESPACE> get secret harbor-pull
```

检查 Robot Account、Secret Namespace、镜像路径和 Tag。

### 11.3 `hostPath type check failed`

说明 Pod 所在节点不存在登记目录。检查：

```bash
kubectl get pod <POD_NAME> -n <TENANT_NAMESPACE> -o wide
ls -ld /data/acmp/models/Qwen2.5-7B-Instruct
```

确认 Pod 位于 GPU Worker，并且目录名与模型 `name` 完全一致。

### 11.4 vLLM 报模型文件缺失

```bash
kubectl -n <TENANT_NAMESPACE> exec <POD_NAME> -- \
  ls -lah /models/Qwen2.5-7B-Instruct
```

重新执行 `ACMP-MANIFEST.sha256` 校验，确认 tokenizer、config 和所有权重分片齐全。

### 11.5 Harbor 磁盘不足

```bash
df -h /srv/harbor/data
docker system df
```

先制定 Tag 保留策略，再执行 Harbor Garbage Collection。不要直接删除
`/srv/harbor/data/registry` 中的文件。

## 12. 备份边界

必须备份：

- `/opt/harbor/harbor.yml`；
- Harbor TLS 证书；
- `/srv/harbor/data`；
- Harbor Robot Account 的安全托管记录；
- ACMP 数据库；
- 模型 `ACMP-MANIFEST.sha256` 和离线来源记录。

模型权重可以从受控离线介质重新导入，但仍建议至少保留一份独立备份。Harbor 备份不能替代
模型备份，因为模型不存放在 Harbor 中。

## 13. 官方参考

- [Harbor 安装前置条件](https://goharbor.io/docs/edge/install-config/installation-prereqs/)
- [Harbor Offline Installer](https://goharbor.io/docs/main/install-config/download-installer/)
- [Harbor `harbor.yml` 配置](https://goharbor.io/docs/main/install-config/configure-yml-file/)
- [Harbor HTTPS 配置](https://goharbor.io/docs/main/install-config/configure-https/)
- [containerd Registry Hosts 配置](https://github.com/containerd/containerd/blob/main/docs/hosts.md)
- [Kubernetes 私有仓库 ImagePullSecrets](https://kubernetes.io/docs/tasks/configure-pod-container/pull-image-private-registry/)
- [Kubernetes Volumes](https://kubernetes.io/docs/concepts/storage/volumes/)
- [Hugging Face 模型下载](https://huggingface.co/docs/huggingface_hub/en/guides/download)
