# ACMP 异构计算平台 — 前端工程

基于 **React 18 + TypeScript + Ant Design 5 + React Router 6 + Axios** 构建。

## 项目结构

```
frontend/
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
├── docs/
│   └── API-REFERENCE.md          # 后端 API 契约文档
└── src/
    ├── main.tsx                   # 入口
    ├── App.tsx                    # 路由 + 全局配置
    ├── api/                       # API 层
    │   ├── client.ts              # axios 实例（JWT 拦截器）
    │   ├── auth.ts                # 登录
    │   ├── physicalClusters.ts    # 物理集群
    │   ├── specs.ts               # 算力规格
    │   ├── resourcePools.ts       # 逻辑资源池
    │   ├── workspaces.ts          # 工作空间 + 成员管理 + 凭证
    │   ├── modelDeployments.ts    # 模型推理部署
    │   └── trainingJobs.ts        # 训练任务
    ├── types/
    │   └── index.ts               # 全部 TypeScript 类型定义
    ├── contexts/
    │   └── AuthContext.tsx         # 认证上下文（JWT 存储 + 登录/退出）
    ├── components/
    │   ├── Layout.tsx              # 主布局（侧栏 + 顶栏）
    │   └── ProtectedRoute.tsx      # 路由守卫
    ├── pages/
    │   ├── Login.tsx               # 登录页
    │   ├── Dashboard.tsx           # 平台概览
    │   ├── PhysicalClusters.tsx    # 物理集群管理
    │   ├── Specs.tsx               # 算力规格管理
    │   ├── ResourcePools.tsx       # 逻辑资源池列表
    │   ├── ResourcePoolDetail.tsx  # 资源池详情（配额）
    │   ├── Workspaces.tsx          # 工作空间列表
    │   └── WorkspaceDetail.tsx     # 工作空间详情（概览/成员/部署/训练/凭证）
    └── styles/
        └── global.css
```

## 页面路由

| 路由 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/` | 平台概览 | 已认证 |
| `/physical-clusters` | 物理集群管理 | 已认证（管理员可注册/删除） |
| `/specs` | 算力规格管理 | 已认证（管理员可创建） |
| `/resource-pools` | 逻辑资源池列表 | 已认证（管理员可创建） |
| `/resource-pools/:id` | 资源池详情 | 已认证 |
| `/workspaces` | 工作空间列表 | 已认证（管理员可创建） |
| `/workspaces/:id` | 工作空间详情（含 Tab） | 已认证 |

## 工作空间详情 Tab

| Tab | 内容 |
|---|---|
| 概览 | 基本信息 + 规格配额表 + 编辑/删除操作 |
| 成员 | 成员列表 + 添加/移除成员 |
| 推理服务 | Deployment 列表 + 部署（选规格→自动注入调度约束/双层配额校验）/删除 |
| 训练任务 | 提交 VolcanoJob 训练任务 |
| 凭证 | （仅管理员）签发 kubeconfig |

## 用户角色

| 角色 | 标签 | 可见页面 |
|---|---|---|
| `PLATFORM_ADMIN` | 系统管理员 | 全部（含物理集群注册、资源池创建、凭证签发） |
| `ORG_ADMIN` | 部门管理员 | 概览、资源池、工作空间（含创建/成员管理） |
| `TRAINING_USER` | 训练用户 | 概览、工作空间（含训练任务提交） |
| `INFERENCE_USER` | 推理用户 | 概览、工作空间（含推理服务部署） |

## 启动

```bash
cd frontend
npm install
npm run dev
```

开发服务器运行在 `http://localhost:3000`，API 请求自动代理到 `http://localhost:8080`。

## 构建

```bash
npm run build
```

产物输出到 `dist/`，可部署到 Nginx。

## 快速开始

```bash
cd frontend
npm install
npm run dev
```
