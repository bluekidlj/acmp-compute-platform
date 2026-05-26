# 权限设计文档

## 1. 核心原则

> **平台层统一身份认证，K8s 层基于 RBAC 做细粒度权限控制，所有用户权限最终收敛到 ServiceAccount，所有资源边界收敛到 Namespace。**

Kubernetes 没有原生"用户"概念。算力平台的"系统用户/普通用户"是平台层身份概念，最终 1:1 映射到 K8s ServiceAccount，再通过 Role/ClusterRole 绑定权限。

---

## 2. 用户类型与权限边界

| 用户类型 | 核心职责 | 权限范围 | K8s 权限级别 | 资源可见范围 |
|---------|---------|---------|-------------|------------|
| **系统用户** (PLATFORM_ADMIN) | 平台运维、物理资源管理 | 集群级 | ClusterRole | 所有物理池、逻辑池、工作空间、节点 |
| **部门管理员** (ORG_ADMIN) | 部门配额分配、工作空间管理 | 部门级 | Role (所有部门Namespace) | 本部门所有工作空间 |
| **普通用户** (TRAINING_USER / INFERENCE_USER) | 提交任务、管理项目资源 | 工作空间级 | Role (单个Namespace) | 仅自己的工作空间 |

---

## 3. 平台用户 ↔ 工作空间 SA

### 核心理念：平台层代理，K8s 层单 SA

- **每个工作空间有且仅有一个 ServiceAccount**，用于该 Namespace 下的所有 K8s 操作
- **平台用户通过 JWT 认证**，平台层校验用户是否有该工作空间的权限
- 所有 K8s API 调用统一使用工作空间的 SA，由平台代理层转发
- **不创建 per-user SA**

### 权限校验流程

```
用户 zhangsan 提交 Pod 到 llm-training 工作空间：

① 平台层 JWT 认证 → 解析 UserPrincipal(id=zhangsan)
② 平台层权限校验 → workspace_member 表查 zhangsan ∈ llm-training
③ 平台代理层 → 使用 llm-training 的 SA 调用 K8s API 创建 Pod
④ K8s RBAC → 校验 SA 在 ns 内有创建 Pod 的权限 ✅
```

### workspace_member 表

| 字段 | 说明 |
|------|------|
| user_id | FK→users |
| workspace_id | FK→workspace |
| PK | (user_id, workspace_id) |

纯 DB 表，不创建任何 K8s 资源。用于平台层快速查询"某用户是否属于某工作空间"。

---

## 4. 系统用户（PLATFORM_ADMIN）权限

### K8s RBAC

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: compute-platform-admin
rules:
  - apiGroups: [""]
    resources: ["nodes", "namespaces", "persistentvolumes"]
    verbs: ["get","list","watch","create","update","patch","delete"]
  - apiGroups: ["","apps","batch","scheduling.volcano.sh"]
    resources: ["*"]
    verbs: ["*"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: compute-platform-admin-binding
subjects:
  - kind: ServiceAccount
    name: system-admin
    namespace: kube-system
roleRef:
  kind: ClusterRole
  name: compute-platform-admin
  apiGroup: rbac.authorization.k8s.io
```

### 平台层约束

- SA 必须放在 `kube-system` 命名空间下
- 操作必须记录审计日志
- 禁止系统用户直接创建业务 Pod（业务操作通过普通用户身份）

---

## 5. 普通用户权限（最小权限原则）

### 允许

| 资源 | 操作 |
|------|------|
| pods, pods/log, jobs, deployments, statefulsets | get, list, watch, create, update, patch, delete |
| persistentvolumeclaims, configmaps, secrets | get, list, watch, create, update, patch, delete |
| resourcequotas, limitranges | get, list, watch |

### 禁止

- 修改 Namespace 本身
- 修改 ResourceQuota
- 查看其他 Namespace 的任何资源
- 创建 ClusterRole / ClusterRoleBinding
- 修改 Node / PersistentVolume

### K8s RBAC

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: compute-platform-user
  namespace: {workspace-ns}
rules:
  - apiGroups: ["","apps","batch"]
    resources: ["pods","pods/log","jobs","deployments","statefulsets"]
    verbs: ["get","list","watch","create","update","patch","delete"]
  - apiGroups: [""]
    resources: ["persistentvolumeclaims","configmaps","secrets"]
    verbs: ["get","list","watch","create","update","patch","delete"]
  - apiGroups: [""]
    resources: ["resourcequotas","limitranges"]
    verbs: ["get","list","watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: {username}-binding
  namespace: {workspace-ns}
subjects:
  - kind: ServiceAccount
    name: {username}-{workspace-ns}
    namespace: {workspace-ns}
roleRef:
  kind: Role
  name: compute-platform-user
  apiGroup: rbac.authorization.k8s.io
```

---

## 6. 权限校验流程

```
用户 zhangsan 提交 Pod 到 llm-training 工作空间：

1. 平台层认证
   JWT Filter → parse token → UserPrincipal(id=zhangsan)

2. 平台层权限校验
   workspace_member 表 → 确认 zhangsan ∈ llm-training
   配额校验 → llm-training 剩余资源充足

3. 平台代理 → K8s
   使用 llm-training 的 SA Token 调用 K8s API
   K8s RBAC 校验：SA 在 ns 内有权创建 Pod ✅
```

**关键**：用户在 K8s 中没有独立身份。平台层通过 JWT 认证后，以工作空间 SA 的身份代理访问 K8s。这样 K8s RBAC 保持不变，平台层灵活管理用户-工作空间关系。

---

## 7. 与三层资源架构的关系

```
物理池层：仅系统用户可见
  ├── Node 管理、标签/污点 → ClusterRole: compute-platform-admin
  │
逻辑池层：系统用户 + 部门管理员可见
  ├── 配额分配、工作空间管理 → Role 跨部门 NS
  │
工作空间层：所属成员可见
  └── 任务提交、资源查看 → Role 单 NS: compute-platform-user
```

---

## 8. 代码清单

| 文件 | 说明 |
|------|------|
| `schema-h2.sql` | +workspace_member 表 |
| `WorkspaceMapper.java/xml` | +insertMember / deleteMember / findMemberIds |
| `WorkspaceService` | +addMember / removeMember / listMembers（纯 DB） |
| `WorkspaceController` | +成员管理 3 个 API |
| `docs/PERMISSION-DESIGN.md` | 本文档 |

> 注意：不再需要 per-user ServiceAccount。每个工作空间创建时已有一个 SA + Role + RoleBinding，平台层代理使用该 SA 操作 K8s。
