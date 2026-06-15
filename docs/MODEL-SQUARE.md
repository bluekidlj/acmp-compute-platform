# 模型广场操作手册

## 概述

模型广场用于统一管理推理服务可用的模型文件。底层存储支持多后端（当前实现 NFS），部署推理服务时可以从中选择模型，自动填充模型路径。

## 概念说明

| 概念 | 说明 |
|------|------|
| 存储后端 | 模型文件的存储方式，当前支持 NFS，未来可扩展 Ceph/OSS 等 |
| 存储根路径 | 运维人员在所有 K8s 节点挂载的 NFS 路径前缀，如 `/mnt/nfs/models` |
| 模型标识 | 模型文件夹名称，唯一标识，如 `qwen3-7b` |
| 完整路径 | `存储根路径` + `/` + `模型标识`，如 `/mnt/nfs/models/qwen3-7b` |

## 模型字段说明

| 字段 | 说明 | 示例 |
|------|------|------|
| 模型标识 | 模型文件夹名称，唯一 | `qwen3-7b` |
| 展示名称 | 前端展示用名称 | `Qwen3-7B-Instruct` |
| 模型来源 | 内置权重 / 外部挂载 | `with_weights` |
| 存储后端 | 存储类型，当前固定 NFS | `nfs` |
| 存储根路径 | 所有节点挂载的 NFS 路径前缀 | `/mnt/nfs/models` |
| 文件大小 (MB) | 模型文件总大小（可选） | `14000000` |
| 描述 | 模型描述信息（可选） | - |

---

## 第一部分：运维操作手册

作为平台运维人员，你需要负责准备 NFS 存储、在模型广场登记模型，供部署人员使用。

### Step 1 — 在所有 K8s Node 上挂载 NFS

NFS 服务器上的目录需要挂载到每一台 K8s Node 的相同路径。以下操作在**每一台 K8s Node** 上执行：

```bash
# 1. 安装 NFS 客户端
sudo apt-get install -y nfs-common

# 2. 创建挂载点目录
sudo mkdir -p /mnt/nfs/models

# 3. 挂载 NFS 服务器（请替换为实际的 NFS 服务器 IP 和路径）
sudo mount -t nfs 192.168.1.100:/data/models /mnt/nfs/models

# 4. 验证挂载成功
ls /mnt/nfs/models

# 5. 设置开机自动挂载（编辑 /etc/fstab 添加以下行）
192.168.1.100:/data/models /mnt/nfs/models nfs defaults 0 0
```

> **注意**：`/mnt/nfs/models` 这个路径在整个平台中必须保持一致，所有 Node 都要挂载到相同位置。

### Step 2 — 将模型文件上传到 NFS 服务器

```bash
# 在 NFS 服务器上（或任意能访问 NFS 的节点）
# 创建模型目录
mkdir -p /data/models/qwen3-7b

# 上传模型文件（示例：使用 scp 或 rsync）
scp -r ./qwen3-7b-model-files/* user@192.168.1.100:/data/models/qwen3-7b/

# 验证文件存在
ls /data/models/qwen3-7b/
# 预期内容：config.json, model.safetensors, tokenizer/...
```

### Step 3 — 在模型广场登记模型

1. 登录 ACMP Web UI → 进入 **模型广场** 菜单
2. 点击右上角 **注册模型** 按钮
3. 填写表单：

   | 字段 | 填写内容 | 说明 |
   |------|----------|------|
   | 模型标识 | `qwen3-7b` | 唯一，对应文件夹名称 |
   | 展示名称 | `Qwen3-7B-Instruct` | 前端展示 |
   | 模型来源 | 内置权重 | 模型文件在 NFS 上 |
   | 存储后端 | NFS | 当前仅支持 NFS |
   | 存储根路径 | `/mnt/nfs/models` | 运维在所有节点挂载的路径前缀 |
   | 文件大小 (MB) | `14000000` | 可选 |
   | 描述 | 通义千问 7B 指令微调版本 | 可选 |

4. 点击 **创建**

平台自动生成完整路径：`/mnt/nfs/models/qwen3-7b`

### Step 4 — 告知部署人员可用的模型

登记完成后，部署人员可以在 **部署服务** → **部署推理服务** 页面的"从模型广场选择"下拉框中看到该模型。

---

## 第二部分：底层原理（Pod 挂载模型文件的完整链路）

作为运维人员，你不需要写代码，但理解底层链路有助于排查问题。

### 完整链路图

```
用户点击"部署推理服务"
  │
  ▼
ACMP API 收到请求（ModelDeploymentRequest）
  │
  ▼
ModelDeploymentService.deploy()
  │
  ├─ 验证配额、选定目标物理集群
  │
  ├─ 根据 modelId 查模型元数据：
  │     modelService.getById(modelId)
  │       → Model { storageBackend="nfs", storagePath="/mnt/nfs/models", name="qwen3-7b" }
  │     完整路径 = storagePath + "/" + name = "/mnt/nfs/models/qwen3-7b"
  │
  ▼
K8sResourceBuilder.buildVllmDeploymentAndService()
  │
  ├─ 构建 Deployment YAML
  ├─ 关键配置：
  │     volumes:
  │     - name: model-data
  │       hostPath:
  │         path: /mnt/nfs/models/qwen3-7b   ← Node 上的 NFS 挂载路径
  │         type: Directory
  │     volumeMounts:
  │     - name: model-data
  │       mountPath: /models                  ← 容器内访问路径
  │     env:
  │     - name: VLLM_MODEL
  │       value: /models                      ← vLLM 读取的模型路径
  │
  ▼
YAML 提交到 K8s Master
  │
  ▼
K8s Scheduler 将 Pod 调度到某个 Node（如 node-2）
  │
  ▼
node-2 上的 kubelet 启动容器
  │
  ▼
容器内 /models 目录
      = node-2 上 hostPath 映射的 /mnt/nfs/models/qwen3-7b
      = NFS 服务器上的 /data/models/qwen3-7b
      （NFS 共享，所有 Node 访问同一份数据）
  │
  ▼
vLLM 进程读取 /models 下的文件：
      /models/config.json
      /models/model.safetensors
      /models/tokenizer/...
  │
  ▼
vLLM 启动完成，监听 0.0.0.0:8000
  │
  ▼
提供服务：/v1/chat/completions, /v1/models 等 API
```

### 关键说明

1. **运维的职责**：确保所有 K8s Node 都把同一个 NFS 路径挂载到相同位置（`/mnt/nfs/models`）。这是 K8s 集群层面的操作，ACMP 平台不管理挂载本身。

2. **HostPath volume**：ACMP 使用 `hostPath` 类型 volume 而不是 K8s 原生的 `nfs` volume。这意味着 NFS 挂载必须在 Node 层面完成（通过 mount 命令），Pod 只是引用 Node 上已存在的路径。

3. **所有节点一致性**：因为所有 Node 挂载同一个 NFS 服务器，无论 Pod 被调度到哪个节点，都能访问到相同的模型文件。

4. **modelSource 含义**：
   - `with_weights`：模型权重在 NFS 上，通过 hostPath 挂载到容器内访问
   - `without_weights`：模型权重在容器镜像内（NFS 用于其他用途，如配置文件）

5. **为什么用 hostPath 而不用 nfs volume 类型**：当前实现使用 hostPath 是因为平台不管理 NFS 挂载流程，假设运维已经在 Node 层面完成挂载。hostPath 更简单直接。

---

## 第三部分：操作流程汇总

```
运维准备阶段：
  1. 准备 NFS 服务器，存储模型文件
  2. 在所有 K8s Node 上挂载 NFS 到 /mnt/nfs/models
  3. 将模型文件上传到 NFS 服务器 /data/models/{model-name}/
  4. 登录 ACMP Web UI → 模型广场 → 注册模型

部署人员使用阶段：
  1. 登录 ACMP Web UI → 部署服务
  2. 点击"部署推理服务"
  3. 在"从模型广场选择"下拉框选择模型 → 自动填充模型路径
  4. 填写 GPU/CPU/内存配置 → 部署

平台内部处理：
  1. 根据 modelId 查到模型元数据（storagePath）
  2. 计算完整路径 = storagePath + "/" + name
  3. 构建 K8s Deployment：hostPath 指向 Node 上的 NFS 路径，mount 到容器内 /models
  4. Pod 调度到任意 Node，都能访问相同的 NFS 模型文件
  5. vLLM 读取 /models 下的权重文件启动服务
```

---

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/models` | 获取模型列表 |
| GET | `/api/v1/models/{id}` | 获取模型详情 |
| POST | `/api/v1/models` | 创建模型 |
| PUT | `/api/v1/models/{id}` | 更新模型 |
| DELETE | `/api/v1/models/{id}` | 删除模型 |

---

## 数据模型

```java
Model {
  id: String (UUID)
  name: String（模型唯一名称，如 qwen3-7b）
  displayName: String（展示名称）
  description: String（描述）
  modelSource: String（with_weights / without_weights）
  storageBackend: String（存储后端，当前固定 nfs）
  storagePath: String（存储根路径前缀，如 /mnt/nfs/models）
  fileSizeMb: Long（文件大小 MB）
  createdAt: Instant
  updatedAt: Instant
}
```

完整 NFS 路径 = `storagePath` + `/` + `name`，如 `/mnt/nfs/models/qwen3-7b`