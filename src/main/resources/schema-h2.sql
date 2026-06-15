-- ===================================================================
-- ACMP-Compute 1.0 数据库结构
-- 概念模型：
--   PhysicalCluster (物理 K8s 集群)
--      └── Workspace (租户 = 1 K8s Namespace)
--             ├── 3 个 ResourcePool (EXCLUSIVE / SHARED / OVERSELL)
--             │     └── N 个关联 ComputeSpec
--             └── N 个 Project (项目 = 配额真正持有者)
--                    ├── ProjectMember (项目成员，独立于 WS 成员)
--                    └── ProjectResourceQuota (从池 × 规格维度分配的配额)
--                           ↑
--                     ModelDeployment (推理服务) 实际扣减对象
-- ===================================================================

-- ─────────── 用户与组织 ───────────
CREATE TABLE IF NOT EXISTS organization (
    id           VARCHAR(64) PRIMARY KEY,
    code         VARCHAR(64) UNIQUE NOT NULL,
    name         VARCHAR(128) NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_user (
    id              VARCHAR(64) PRIMARY KEY,
    username        VARCHAR(64) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(128),
    email           VARCHAR(128),
    role            VARCHAR(32) NOT NULL,    -- PLATFORM_ADMIN / ORG_ADMIN / INFERENCE_USER
    organization_id VARCHAR(64),
    status          VARCHAR(20) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_app_user_org ON app_user(organization_id);

-- ─────────── 物理集群 ───────────
CREATE TABLE IF NOT EXISTS physical_cluster (
    id                              VARCHAR(64) PRIMARY KEY,
    name                            VARCHAR(128) NOT NULL,
    description                     VARCHAR(512),
    kubeconfig_base64_encrypted     CLOB NOT NULL,
    status                          VARCHAR(32) NOT NULL DEFAULT 'active',
    gpu_types                       VARCHAR(512),       -- CSV: NVIDIA,HYGON,HUAWEI_ASCEND
    hami_splits                     TEXT,               -- JSON 数组：HAMi 切分规格
    location                        VARCHAR(64),
    node_labels                     VARCHAR(1024),      -- JSON
    taints                          VARCHAR(2048),      -- JSON
    max_cpu_cores                   INT,
    max_memory_gib                  INT,
    created_at                      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ─────────── 算力规格（全局规格库）───────────
-- specType 决定 poolType 一一对应：
--   PHYSICAL  → EXCLUSIVE
--   VIRTUAL   → SHARED     （HAMi vGPU 切分）
--   OVERSELL  → OVERSELL   （1.0 暂未实现真实 K8s 提交）
CREATE TABLE IF NOT EXISTS compute_spec (
    id                    VARCHAR(64) PRIMARY KEY,
    name                  VARCHAR(128) UNIQUE NOT NULL,
    display_name          VARCHAR(128),
    gpu_brand             VARCHAR(32),            -- NVIDIA / HYGON / HUAWEI_ASCEND
    spec_type             VARCHAR(20) NOT NULL,    -- PHYSICAL / VIRTUAL / OVERSELL
    pool_type             VARCHAR(20) NOT NULL,    -- EXCLUSIVE / SHARED / OVERSELL (冗余)
    default_gpu_count     INT DEFAULT 1,
    default_gpumem_mb     INT,
    default_gpucores      INT,
    default_cpu_cores     INT DEFAULT 4,
    default_memory_gib    INT DEFAULT 16,
    node_selector         VARCHAR(512),           -- JSON
    tolerations           VARCHAR(1024),           -- JSON
    resource_quota_key    VARCHAR(128),
    memory_gb             INT,
    description           VARCHAR(512),
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ─────────── 工作空间 = 租户 ───────────
-- 1.0 每个 WS = 1 个 K8s Namespace + 1 个 Volcano Queue
-- primaryClusterId: 1.0 单集群下必有值
CREATE TABLE IF NOT EXISTS workspace (
    id                      VARCHAR(64) PRIMARY KEY,
    name                    VARCHAR(128) NOT NULL,
    description             VARCHAR(512),
    primary_cluster_id      VARCHAR(64) NOT NULL,
    namespace               VARCHAR(128) UNIQUE NOT NULL,
    service_account_name    VARCHAR(128),
    volcano_queue_name      VARCHAR(128),
    max_pods                INT DEFAULT 50,
    created_by              VARCHAR(64),
    status                  VARCHAR(20) DEFAULT 'active',
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workspace_cluster ON workspace(primary_cluster_id);

CREATE TABLE IF NOT EXISTS workspace_member (
    workspace_id    VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    PRIMARY KEY (workspace_id, user_id)
);

-- ─────────── 资源池（WS 私有三类池）───────────
-- 每 WS 每类池唯一，池被三个池类型预占
CREATE TABLE IF NOT EXISTS resource_pool (
    id                  VARCHAR(64) PRIMARY KEY,
    workspace_id        VARCHAR(64) NOT NULL,
    pool_type           VARCHAR(20) NOT NULL,    -- EXCLUSIVE / SHARED / OVERSELL
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512),
    primary_cluster_id  VARCHAR(64) NOT NULL,
    total_nodes         INT NOT NULL DEFAULT 0,   -- 池总容量（卡数 / vGPU 数 / 超分单元数）
    allocated_nodes     INT NOT NULL DEFAULT 0,   -- 已分配给各 Project 之和
    status              VARCHAR(20) DEFAULT 'active',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (workspace_id, pool_type)
);
CREATE INDEX IF NOT EXISTS idx_resource_pool_ws ON resource_pool(workspace_id);

-- 池 - 规格 多对多
CREATE TABLE IF NOT EXISTS resource_pool_spec (
    resource_pool_id    VARCHAR(64) NOT NULL,
    spec_id             VARCHAR(64) NOT NULL,
    PRIMARY KEY (resource_pool_id, spec_id)
);

-- ─────────── 项目（WS 内的子租户）───────────
CREATE TABLE IF NOT EXISTS project (
    id              VARCHAR(64) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    created_by      VARCHAR(64),
    status          VARCHAR(20) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (workspace_id, name)
);
CREATE INDEX IF NOT EXISTS idx_project_ws ON project(workspace_id);

CREATE TABLE IF NOT EXISTS project_member (
    project_id  VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (project_id, user_id)
);

-- 项目从池获得的配额（按 pool × spec 维度）
CREATE TABLE IF NOT EXISTS project_resource_quota (
    id                  VARCHAR(64) PRIMARY KEY,
    project_id          VARCHAR(64) NOT NULL,
    resource_pool_id    VARCHAR(64) NOT NULL,
    spec_id             VARCHAR(64) NOT NULL,
    total_nodes         INT NOT NULL DEFAULT 0,
    used_nodes          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, resource_pool_id, spec_id)
);
CREATE INDEX IF NOT EXISTS idx_prq_project ON project_resource_quota(project_id);

-- ─────────── 模型部署 ───────────
CREATE TABLE IF NOT EXISTS model_deployment (
    id                      VARCHAR(64) PRIMARY KEY,
    project_id              VARCHAR(64) NOT NULL,
    workspace_id            VARCHAR(64) NOT NULL,
    resource_pool_id        VARCHAR(64) NOT NULL,
    spec_id                 VARCHAR(64) NOT NULL,
    pool_type               VARCHAR(20) NOT NULL,        -- EXCLUSIVE / SHARED / OVERSELL
    name                    VARCHAR(255) NOT NULL,
    model_name              VARCHAR(255),
    model_source            VARCHAR(32),
    model_id_or_path        VARCHAR(512),
    vllm_image              VARCHAR(512),
    gpu_per_replica         INT DEFAULT 1,
    gpumem_mb               INT,
    gpucores                INT,
    replicas                INT DEFAULT 1,
    k8s_deployment_name     VARCHAR(255),
    k8s_service_name        VARCHAR(255),
    status                  VARCHAR(32) NOT NULL DEFAULT 'pending',
    service_url             VARCHAR(512),
    actual_cluster_id       VARCHAR(64),
    created_by              VARCHAR(64),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_md_project ON model_deployment(project_id);
CREATE INDEX IF NOT EXISTS idx_md_workspace ON model_deployment(workspace_id);

-- ─────────── 模型广场 ───────────
CREATE TABLE IF NOT EXISTS model_source (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(128) NOT NULL UNIQUE,
    display_name        VARCHAR(255),
    description         TEXT,
    model_source        VARCHAR(32) NOT NULL DEFAULT 'with_weights',
    storage_backend     VARCHAR(32) NOT NULL DEFAULT 'nfs',
    storage_path        VARCHAR(512) NOT NULL,
    file_size_mb        BIGINT,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ─────────── 训练任务记录（保留兼容，本轮不动）───────────
CREATE TABLE IF NOT EXISTS training_job_record (
    id                  VARCHAR(64) PRIMARY KEY,
    workspace_id        VARCHAR(64) NOT NULL,
    resource_pool_id    VARCHAR(64) NOT NULL,
    spec_id             VARCHAR(64),
    replicas            INT DEFAULT 1,
    k8s_job_name        VARCHAR(255),
    job_name            VARCHAR(255),
    status              VARCHAR(32),
    created_by          VARCHAR(64),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ===================================================================
-- 预置数据：7 条标准规格
-- ===================================================================
MERGE INTO compute_spec (id, name, display_name, gpu_brand, spec_type, pool_type,
    default_gpu_count, default_cpu_cores, default_memory_gib,
    node_selector, tolerations, resource_quota_key, memory_gb)
KEY(id) VALUES
('spec-exclusive-a100', 'exclusive-nvidia-a100-80g', 'NVIDIA A100 80GB (独占整卡)', 'NVIDIA',
 'PHYSICAL', 'EXCLUSIVE',
 1, 8, 32,
 '{"pool":"exclusive-nvidia-a100-80g"}',
 '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/exclusive-nvidia-a100-80g', 80),

('spec-exclusive-h100', 'exclusive-nvidia-h100-80g', 'NVIDIA H100 80GB (独占整卡)', 'NVIDIA',
 'PHYSICAL', 'EXCLUSIVE',
 1, 8, 32,
 '{"pool":"exclusive-nvidia-h100-80g"}',
 '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/exclusive-nvidia-h100-80g', 80),

('spec-exclusive-dcu', 'exclusive-hygon-dcu', 'Hygon DCU (独占整卡)', 'HYGON',
 'PHYSICAL', 'EXCLUSIVE',
 1, 8, 32,
 '{"pool":"exclusive-hygon-dcu"}',
 '[{"key":"amd.com/dcu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/exclusive-hygon-dcu', 32),

('spec-shared-a100-12', 'shared-hami-a100-1/2', 'A100 80GB 1/2 卡 (HAMi 切分)', 'NVIDIA',
 'VIRTUAL', 'SHARED',
 1, 4, 16,
 '{"pool":"shared-hami-a100-1/2"}',
 '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/shared-hami-a100-1/2', 40),

('spec-shared-a100-14', 'shared-hami-a100-1/4', 'A100 80GB 1/4 卡 (HAMi 切分)', 'NVIDIA',
 'VIRTUAL', 'SHARED',
 1, 2, 8,
 '{"pool":"shared-hami-a100-1/4"}',
 '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/shared-hami-a100-1/4', 20),

('spec-shared-a100-18', 'shared-hami-a100-1/8', 'A100 80GB 1/8 卡 (HAMi 切分)', 'NVIDIA',
 'VIRTUAL', 'SHARED',
 1, 1, 4,
 '{"pool":"shared-hami-a100-1/8"}',
 '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/shared-hami-a100-1/8', 10),

('spec-oversell-a100', 'oversell-a100-mig-1/2', 'A100 MIG 1/2 (超分占位)', 'NVIDIA',
 'OVERSELL', 'OVERSELL',
 1, 4, 16,
 '{"pool":"oversell-a100-mig-1/2"}',
 '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
 'platform.io/oversell-a100-mig-1/2', 40);
