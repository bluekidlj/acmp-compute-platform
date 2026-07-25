-- ===================================================================
-- ACMP-Compute 1.0 数据库结构
-- 概念模型：
--   PhysicalCluster -> ClusterNode -> GpuDevice
--   ResourcePool -> ComputeSpec
--   Tenant
--      ├── TenantSpecQuota
--      └── Project
--             ├── ProjectMember
--             └── ModelDeployment
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
    kubernetes_version              VARCHAR(64),
    node_count                      INT DEFAULT 0,
    gpu_count                       INT DEFAULT 0,
    last_sync_at                    TIMESTAMP,
    sync_message                    VARCHAR(1024),
    created_at                      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cluster_node (
    id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    cpu_cores INT DEFAULT 0,
    memory_bytes BIGINT DEFAULT 0,
    gpu_count INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'UNKNOWN',
    labels_json CLOB,
    taints_json CLOB,
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (cluster_id, name)
);
CREATE INDEX IF NOT EXISTS idx_cluster_node_cluster ON cluster_node(cluster_id);

CREATE TABLE IF NOT EXISTS gpu_device (
    id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(128) NOT NULL,
    gpu_index INT NOT NULL,
    uuid VARCHAR(128),
    gpu_model VARCHAR(128),
    memory_mb BIGINT,
    driver_version VARCHAR(64),
    cuda_version VARCHAR(64),
    status VARCHAR(32) DEFAULT 'UNKNOWN',
    resource_pool_id VARCHAR(64),
    compute_spec_id VARCHAR(64),
    usage_status VARCHAR(32) DEFAULT 'IDLE',
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (cluster_id, node_name, gpu_index)
);
CREATE INDEX IF NOT EXISTS idx_gpu_device_cluster ON gpu_device(cluster_id);
CREATE INDEX IF NOT EXISTS idx_gpu_device_node ON gpu_device(node_id);
CREATE INDEX IF NOT EXISTS idx_gpu_device_pool ON gpu_device(resource_pool_id);
ALTER TABLE gpu_device ADD COLUMN IF NOT EXISTS compute_spec_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_gpu_device_spec ON gpu_device(compute_spec_id);

-- ─────────── 算力规格 ───────────
CREATE TABLE IF NOT EXISTS compute_spec (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(128) UNIQUE NOT NULL,
    display_name        VARCHAR(128),
    gpu_brand           VARCHAR(32),
    spec_type           VARCHAR(20) NOT NULL,
    resource_pool_id    VARCHAR(64) NOT NULL,
    gpu_model           VARCHAR(128),
    gpu_count           INT NOT NULL DEFAULT 1,
    cpu_cores           INT NOT NULL DEFAULT 4,
    memory_gib          INT NOT NULL DEFAULT 16,
    gpu_share           VARCHAR(8),
    description         VARCHAR(512),
    status              VARCHAR(20) DEFAULT 'active',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tenant (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) UNIQUE NOT NULL,
    description VARCHAR(512),
    created_by VARCHAR(64),
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tenant_member (
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);
CREATE TABLE IF NOT EXISTS tenant_spec_quota (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    spec_id VARCHAR(64) NOT NULL,
    total INT NOT NULL DEFAULT 0,
    used INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, spec_id)
);
CREATE INDEX IF NOT EXISTS idx_tenant_spec_quota_tenant ON tenant_spec_quota(tenant_id);

-- ─────────── 平台固定双资源池 ───────────
CREATE TABLE IF NOT EXISTS resource_pool (
    id                  VARCHAR(64) PRIMARY KEY,
    pool_type           VARCHAR(20) UNIQUE NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512),
    status              VARCHAR(20) DEFAULT 'active',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ─────────── 租户项目 ───────────
CREATE TABLE IF NOT EXISTS project (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    created_by      VARCHAR(64),
    status          VARCHAR(20) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, name)
);
ALTER TABLE project ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_project_tenant ON project(tenant_id);

CREATE TABLE IF NOT EXISTS project_member (
    project_id  VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (project_id, user_id)
);

-- ─────────── 模型部署 ───────────
CREATE TABLE IF NOT EXISTS model_deployment (
    id                      VARCHAR(64) PRIMARY KEY,
    project_id              VARCHAR(64) NOT NULL,
    tenant_id               VARCHAR(64) NOT NULL,
    resource_pool_id        VARCHAR(64) NOT NULL,
    spec_id                 VARCHAR(64) NOT NULL,
    name                    VARCHAR(255) NOT NULL,
    model_name              VARCHAR(255),
    model_source            VARCHAR(32),
    model_id_or_path        VARCHAR(512),
    vllm_image              VARCHAR(512),
    port                    INT NOT NULL DEFAULT 8000,
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
ALTER TABLE model_deployment ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE model_deployment ADD COLUMN IF NOT EXISTS model_id VARCHAR(64);
ALTER TABLE model_deployment ADD COLUMN IF NOT EXISTS failure_message VARCHAR(1024);
CREATE INDEX IF NOT EXISTS idx_md_project ON model_deployment(project_id);
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
