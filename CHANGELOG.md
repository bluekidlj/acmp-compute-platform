# Changelog

所有项目的**显著改动**记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/)。

---

## [Unreleased] - 异构算力资源池

### Added
- `pool_card` 表（卡 ↔ 池 + 切分粒度）
- 3 个端点：`POST/DELETE/GET /api/v1/pools/{id}/cards`
- `PoolCard` entity/mapper/service/controller
- `K8sResourceBuilder.buildVllmDeployment` 加 `preferredNodes` 参数 → 生成 `nodeAffinity`
- 部署失败回滚 `prq.used`（保证 DB ↔ K8s 一致）
- 删部署回滚 `prq.used`
- 详见 [docs/08-HETEROGENEOUS-POOL.md](docs/08-HETEROGENEOUS-POOL.md)

### Changed
- `ModelDeploymentService.deploy` 加 `preferredNodes`（从 `pool_card.node_name` 聚合）
- `ProjectQuotaService.allocate` 池容量校验改用 `pool_card.slots` 累加
- `ResourcePool.totalNodes` 由 `pool_card` 自动 sum
- `ModelDeployment` 加 `poolCardId` + `resourceKey` 字段

### Deprecated
- `ResourcePoolUpdateRequest.totalNodes` 字段（保留不报错，不再生效）

### Removed
- 无

### Fixed
- 1.0 同构池的"按品牌配额无法独立计量"问题

### Security
- 无

---

## [1.0.0] - 2026-06-XX

### Added - 同构资源池初始版本
- 7 条预置 ComputeSpec（3 EXCLUSIVE + 3 SHARED + 1 OVERSELL）
- K8s 资源落地：NS / SA / Role / RB / Deployment / Service / ResourceQuota
- 三层配额：pool.total / prq.total / prq.used
- vLLM 一键部署 + 模型广场 CRUD
- io.kubernetes:client-java 20.0.0（替代 fabric8）
- 详见 [docs/01-07](docs/)
