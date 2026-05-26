# ACMP-Compute — 异构算力管理平台

[![Java](https://img.shields.io/badge/Java-11-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **AI Compute Platform** — 基于 K8s + HAMi + Volcano 的显卡资源管理与任务调度平台。

## ✨ 特性

- 🖥️ **多集群管理** — 注册多个 K8s 物理集群，kubeconfig AES-256 加密存储
- 📦 **资源池化** — Namespace + ResourceQuota + RBAC + Volcano Queue 实现部门级算力隔离
- 🚀 **vLLM 推理部署** — 一键部署 vLLM 模型推理服务，支持 hostPath 挂载本地模型权重
- 🏋️ **Volcano 训练** — 提交 VolcanoJob 分布式 GPU 训练，支持 gang scheduling
- 🔐 **多租户权限** — JWT 认证 + 四种角色（PLATFORM_ADMIN / ORG_ADMIN / TRAINING_USER / INFERENCE_USER）
- 🎫 **凭证发放** — 为部门用户生成限定 namespace 的 kubeconfig

## 🏗️ 架构

```
Platform → Physical Cluster (K8s) → Resource Pool (Namespace + Quota + Volcano Queue)
                                         ├── vLLM Deployment (推理服务)
                                         └── VolcanoJob (训练任务)
```

## 📋 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Java 11 |
| 框架 | Spring Boot 2.7.18 |
| K8s 客户端 | fabric8 Kubernetes Client 6.13.0 |
| 数据库 | H2（文件模式，可替换为 MySQL/PostgreSQL） |
| ORM | MyBatis 2.3.2 |
| 安全 | Spring Security + JWT (jjwt 0.11.5) |
| 模板 | Freemarker |
| 构建 | Maven 3.8+ |

## 🚀 快速开始

### 环境要求

- JDK 11+
- Maven 3.8+

### 本地运行

```bash
git clone <your-repo-url>
cd acmp-compute-platform/acmp-compute
mvn spring-boot:run
```

- 服务端口：**8080**
- H2 控制台：http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:file:./data/acmp`
  - 用户名：`sa`，密码：空
- 默认管理员：`admin` / `admin123`

### Docker 运行

```bash
docker build -t acmp-compute:latest .
docker run -d -p 8080:8080 \
  -e JWT_SECRET=your-secret \
  -e AES_KEY=acmp32byteskey!!!!!!!!!!!!!!!!! \
  --name acmp-compute \
  acmp-compute:latest
```

## 📡 API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/login` | 登录获取 JWT |
| POST | `/api/v1/physical-clusters` | 注册物理集群 |
| GET | `/api/v1/physical-clusters` | 列出物理集群 |
| GET | `/api/v1/physical-clusters/{id}/capacity` | 查询集群容量 |
| DELETE | `/api/v1/physical-clusters/{id}` | 删除物理集群 |
| POST | `/api/v1/resource-pools` | 创建逻辑资源池 |
| GET | `/api/v1/resource-pools` | 列出逻辑资源池 |
| PATCH | `/api/v1/resource-pools/{id}/capacity` | 修改资源池容量 |
| POST | `/api/v1/resource-pools/{poolId}/model-deployments` | 部署 vLLM 模型 |
| GET | `/api/v1/resource-pools/{poolId}/model-deployments` | 列出模型部署 |
| DELETE | `/api/v1/resource-pools/{poolId}/model-deployments/{id}` | 删除模型部署 |
| POST | `/api/v1/resource-pools/{poolId}/training-jobs` | 提交训练任务 |

> 详细请求/响应示例见 [docs/EXAMPLE-REQUEST.md](docs/EXAMPLE-REQUEST.md)

## 📚 文档

| 文档 | 说明 |
|------|------|
| [README_V1.md](docs/README_V1.md) | 📖 完整工程文档（架构、数据库、模块详解） |
| [DEPLOY.md](docs/DEPLOY.md) | 🐳 Docker 部署说明 |
| [EXAMPLE-REQUEST.md](docs/EXAMPLE-REQUEST.md) | 📨 HTTP 请求示例 + curl 命令 |
| [REQUEST-FLOW.md](docs/REQUEST-FLOW.md) | 🔄 请求处理流程图 |
| [MODEL-AND-IMAGES.md](docs/MODEL-AND-IMAGES.md) | 🧠 模型与镜像本地化 |
| [HAMI-VOLCANO.md](docs/HAMI-VOLCANO.md) | ⚡ HAMi 与 Volcano 定位 |

## 📁 项目结构

```
acmp-compute/                  ← Monorepo 根目录
├── src/                       ← 后端源码 (Spring Boot)
│   └── main/java/com/acmp/compute/
│       ├── config/            # Spring 配置
│       ├── controller/        # REST 控制器
│       ├── dto/               # 请求/响应 DTO
│       ├── entity/            # 数据实体
│       ├── exception/         # 异常处理
│       ├── k8s/               # K8s 客户端、模板、Builder
│       ├── mapper/            # MyBatis Mapper
│       ├── security/          # JWT 认证授权
│       └── service/           # 业务服务层
├── src/main/resources/
│   ├── k8s-templates/         # Freemarker YAML 模板
│   ├── mapper/                # MyBatis XML 映射
│   ├── schema-h2.sql          # 数据库 DDL
│   └── data-h2.sql            # 初始数据
├── frontend/                  # 前端源码 (待开发)
├── docs/                      # 项目文档
├── pom.xml                    # Maven 构建
└── Dockerfile
```

## 🔧 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `JWT_SECRET` | JWT 签名密钥 | 内置默认值（生产必改） |
| `AES_KEY` | kubeconfig 加密密钥（须 32 字节） | 内置默认值（生产必改） |

## 📄 License

[Apache 2.0](LICENSE)
