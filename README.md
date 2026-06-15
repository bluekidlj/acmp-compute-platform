# ACMP-Compute — 异构算力管理平台（1.0）

[![Java](https://img.shields.io/badge/Java-11-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **1.0 核心模型：集群 → 工作空间（租户）→ 三类资源池（独占/共享/超分）→ 项目 → 推理服务**

## ✨ 1.0 特性

- 🖥️ **多物理集群管理** — 注册多个 K8s 物理集群，kubeconfig AES-256 加密存储
- 🏢 **工作空间（租户）** — 每个 WS = 1 个 K8s Namespace + 1 个 Volcano Queue + **3 个自动建池**（EXCLUSIVE 独占整卡 / SHARED HAMi 切分 / OVERSELL 超分占位）
- 📦 **项目** — 子租户，**配额真正拥有者**，从 WS 三类池中按规格分配节点
- 📋 **算力规格** — 全局规格库 7 条预置（PHYSICAL / VIRTUAL / OVERSELL），按 `specType→poolType` 路由
- 🚀 **vLLM 推理部署** — 一键部署 vLLM，按 spec 自动路由到 Project 拥有的同类型池
- 🔐 **JWT 认证 + RBAC** — PLATFORM_ADMIN / ORG_ADMIN / INFERENCE_USER
- 🖼️ **显卡管理** — 扫集群节点、按型号聚合、解析 HAMi 切分规格

## 🏗️ 架构

```
PhysicalCluster (K8s 集群)
   └── Workspace (租户)
          ├── ResourcePool (EXCLUSIVE)   ← 整卡独占
          ├── ResourcePool (SHARED)      ← HAMi vGPU 切分
          ├── ResourcePool (OVERSELL)    ← 超分占位
          └── Project (配额真正拥有者)
                 └── ModelDeployment (按 spec 路由到对应池)
```

## 📋 技术栈

| 层次 | 技术 |
|---|---|
| 语言 | Java 11 |
| 框架 | Spring Boot 2.7.18 |
| K8s 客户端 | fabric8 Kubernetes Client 6.13.0 |
| 数据库 | H2（文件模式，可替换为 MySQL/PostgreSQL） |
| ORM | MyBatis 2.3.2 |
| 安全 | Spring Security + JWT (jjwt 0.11.5) |
| 构建 | Maven 3.8+ |

## 🚀 快速开始

```bash
mvn spring-boot:run
```

- 端口：**8080**
- H2 控制台：http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:file:./data/acmp`
  - 用户名：`sa`，密码：空
- 默认管理员：`admin` / `admin123`

Docker：

```bash
docker build -t acmp-compute:latest .
docker run -d -p 8080:8080 \
  -e JWT_SECRET=your-secret \
  -e AES_KEY=acmp32byteskey!!!!!!!!!!!!!!!!!! \
  --name acmp-compute \
  acmp-compute:latest
```

## 📚 文档

| 文档 | 说明 |
|---|---|
| [docs/01-ARCHITECTURE.md](docs/01-ARCHITECTURE.md) | 1.0 整体架构 |
| [docs/02-RESOURCE-MODEL.md](docs/02-RESOURCE-MODEL.md) | 对象模型与字段 |
| [docs/03-API-REFERENCE.md](docs/03-API-REFERENCE.md) | 完整 API 参考 |
| [docs/04-DEPLOYMENT-FLOW.md](docs/04-DEPLOYMENT-FLOW.md) | 部署推理服务全流程 |
| [docs/05-EXAMPLE.md](docs/05-EXAMPLE.md) | curl 调用示例 |
| [docs/06-VERIFICATION.md](docs/06-VERIFICATION.md) | 验证报告（修复清单 + 测试用例 + 手工 step） |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Docker 部署说明 |

## 🧪 验证

```bash
# kind 环境一键验证
kind create cluster --config scripts/kind-cluster.yaml
bash scripts/seed-labels.sh
bash scripts/seed-hami-annotations.sh
bash scripts/install-nvidia-plugin.sh
mvn spring-boot:run
bash scripts/verify.sh
bash scripts/verify-failures.sh
```

详细测试用例与手工 step 见 [docs/06-VERIFICATION.md](docs/06-VERIFICATION.md)。

## 📁 项目结构

```
acmp-compute/
├── src/main/java/com/acmp/compute/
│   ├── AcmpComputeApplication.java
│   ├── config/            # SecurityConfig
│   ├── controller/        # REST 控制器
│   ├── dto/               # 请求/响应 DTO
│   ├── entity/            # 数据实体
│   ├── exception/         # 全局异常
│   ├── k8s/               # Kubernetes 客户端 + 资源构建
│   ├── mapper/            # MyBatis Mapper 接口
│   ├── security/          # JWT
│   ├── service/           # 业务服务层
│   └── util/              # 工具
├── src/main/resources/
│   ├── mapper/            # MyBatis XML
│   ├── schema-h2.sql      # 1.0 表结构 + 7 条预置规格
│   ├── data-h2.sql        # 默认管理员
│   └── application.yml
└── docs/                  # 1.0 设计文档
```

## 🔧 环境变量

| 变量 | 说明 | 默认值 |
|---|---|---|
| `JWT_SECRET` | JWT 签名密钥 | 内置默认值（生产必改） |
| `AES_KEY` | kubeconfig 加密密钥（须 32 字节） | 内置默认值（生产必改） |

## 📄 License

[Apache 2.0](LICENSE)
