-- AI Compute Platform - H2 数据库表结构（v2.0）
-- 设计原则："物理属性归物理池，标准定义归规格，逻辑池只存关联关系"

-- ============================================
-- 物理集群（K8s 集群）：节点标签、污点的唯一存储点
-- ============================================
CREATE TABLE IF NOT EXISTS physical_cluster (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(512),
    kubeconfig_base64_encrypted CLOB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    total_gpu_slots INT,
    gpu_types VARCHAR(255) DEFAULT 'NVIDIA',
    location VARCHAR(64) DEFAULT 'default',
    -- 调度属性（唯一存储点，逻辑池不存）
    -- node_labels JSON: {"pool":"nvidia-gpu-pool"}
    node_labels VARCHAR(1024),
    -- taints JSON: [{"key":"nvidia.com/gpu","value":"present","effect":"NoSchedule"}]
    taints VARCHAR(2048),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 组织
CREATE TABLE IF NOT EXISTS organization (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 逻辑资源池：纯 DB 聚合容器，不存标签/污点/调度规则
-- ============================================
CREATE TABLE IF NOT EXISTS resource_pool (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(512),
    department_code VARCHAR(64) NOT NULL,
    department_name VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 逻辑资源池 ↔ 物理集群 多对多
CREATE TABLE IF NOT EXISTS resource_pool_physical_cluster (
    resource_pool_id VARCHAR(36) NOT NULL,
    physical_cluster_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (resource_pool_id, physical_cluster_id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (physical_cluster_id) REFERENCES physical_cluster(id)
);

-- 用户
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    organization_id VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id) REFERENCES organization(id)
);

-- 用户-资源池 多对多
CREATE TABLE IF NOT EXISTS user_resource_pool (
    user_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (user_id, resource_pool_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id)
);

-- ============================================
-- 算力规格：预设的 K8s ResourceRequirements 模板 + nodeSelector + tolerations
-- ============================================
CREATE TABLE IF NOT EXISTS compute_spec (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    gpu_brand VARCHAR(64) DEFAULT 'NVIDIA',
    -- 预设 ResourceRequirements
    default_gpu_count INT DEFAULT 1,
    default_gpumem_mb INT,
    default_gpucores INT,
    default_cpu_cores INT DEFAULT 4,
    default_memory_gib INT DEFAULT 16,
    -- 节点选择器 + 污点容忍（JSON）
    node_selector VARCHAR(512),
    tolerations VARCHAR(1024),
    -- ResourceQuota 中的资源键，默认 platform.io/{name}
    resource_quota_key VARCHAR(255),
    memory_gb INT,
    description VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 物理集群拥有的规格及数量（用于"按规格选目标集群"调度决策）
CREATE TABLE IF NOT EXISTS physical_cluster_spec (
    physical_cluster_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36) NOT NULL,
    total_count INT DEFAULT 0,
    PRIMARY KEY (physical_cluster_id, spec_id),
    FOREIGN KEY (physical_cluster_id) REFERENCES physical_cluster(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);

-- 逻辑池按规格的总配额（资源初次划分）
CREATE TABLE IF NOT EXISTS resource_pool_spec_quota (
    resource_pool_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36) NOT NULL,
    total_quota INT DEFAULT 0,
    allocated_quota INT DEFAULT 0,
    PRIMARY KEY (resource_pool_id, spec_id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);

-- ============================================
-- 工作空间 = K8s Namespace（100% 对应，用户唯一可见的资源边界）
-- ============================================
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(36) PRIMARY KEY,
    resource_pool_id VARCHAR(36) NOT NULL,
    -- K8s 资源名
    namespace VARCHAR(255) NOT NULL UNIQUE,
    service_account_name VARCHAR(255),
    volcano_queue_name VARCHAR(255),
    primary_cluster_id VARCHAR(36),
    -- 配额仅保留 maxPods 这类与规格无关的全局上限（gpu/cpu/mem 已迁移到 workspace_pool_spec_quota）
    max_pods INT DEFAULT 50,
    node_count INT DEFAULT 1,
    -- 描述
    name VARCHAR(255) NOT NULL,
    description VARCHAR(512),
    created_by VARCHAR(36),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 工作空间 ↔ 逻辑资源池 绑定（冗余表，与 workspace.resource_pool_id 等价，保留以兼容文档 v2.0）
CREATE TABLE IF NOT EXISTS workspace_resource_pool (
    workspace_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (workspace_id, resource_pool_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id)
);

-- 工作空间在逻辑池中的按规格配额（资源二次分配，双层配额核心）
CREATE TABLE IF NOT EXISTS workspace_pool_spec_quota (
    workspace_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36) NOT NULL,
    max_quota INT DEFAULT 0,
    used_quota INT DEFAULT 0,
    PRIMARY KEY (workspace_id, resource_pool_id, spec_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);

-- ============================================
-- vLLM 模型服务部署记录
-- ============================================
CREATE TABLE IF NOT EXISTS model_deployment (
    id VARCHAR(36) PRIMARY KEY,
    -- 真实的所属语义：workspace 是 K8s 边界，pool 是配额归属
    workspace_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36),
    name VARCHAR(255) NOT NULL,
    model_name VARCHAR(255),
    model_source VARCHAR(32) NOT NULL,
    model_id_or_path VARCHAR(512),
    vllm_image VARCHAR(512),
    gpu_per_replica INT DEFAULT 1,
    gpumem_mb INT,
    gpucores INT,
    replicas INT DEFAULT 1,
    k8s_deployment_name VARCHAR(255),
    k8s_service_name VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    service_url VARCHAR(512),
    created_by VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- ============================================
-- 训练任务记录
-- ============================================
CREATE TABLE IF NOT EXISTS training_job_record (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36),
    replicas INT DEFAULT 1,
    k8s_job_name VARCHAR(255),
    job_name VARCHAR(255),
    status VARCHAR(32),
    created_by VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);

-- 资源池凭证（兼容旧版，按 workspace 发放更合理）
CREATE TABLE IF NOT EXISTS resource_pool_credential (
    id VARCHAR(36) PRIMARY KEY,
    resource_pool_id VARCHAR(36) NOT NULL,
    username VARCHAR(128) NOT NULL,
    kubeconfig CLOB NOT NULL,
    expire_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id)
);

-- ============================================
-- 权限：工作空间成员（平台层权限记录）
-- 每个工作空间只有一个 SA，平台层代理校验用户-工作空间关系
-- ============================================
CREATE TABLE IF NOT EXISTS workspace_member (
    user_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (user_id, workspace_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_resource_pool_dept ON resource_pool(department_code);
CREATE INDEX IF NOT EXISTS idx_credential_pool ON resource_pool_credential(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_user_resource_pool_user ON user_resource_pool(user_id);
CREATE INDEX IF NOT EXISTS idx_user_resource_pool_pool ON user_resource_pool(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_workspace_status ON workspace(status);
CREATE INDEX IF NOT EXISTS idx_workspace_pool ON workspace(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_rp_pc_pool ON resource_pool_physical_cluster(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_rp_pc_cluster ON resource_pool_physical_cluster(physical_cluster_id);
CREATE INDEX IF NOT EXISTS idx_model_dep_ws ON model_deployment(workspace_id);
CREATE INDEX IF NOT EXISTS idx_model_dep_pool ON model_deployment(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_training_ws ON training_job_record(workspace_id);

-- 初始规格数据
MERGE INTO compute_spec (id, name, display_name, gpu_brand, default_gpu_count, default_cpu_cores, default_memory_gib, node_selector, tolerations, resource_quota_key, memory_gb) KEY(id)
VALUES ('spec-nvidia-a100-80g', 'nvidia-a100-80g', 'NVIDIA A100 80GB SXM', 'NVIDIA',
        1, 8, 32,
        '{"pool":"nvidia-gpu"}',
        '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
        'platform.io/nvidia-a100-80g', 80);

MERGE INTO compute_spec (id, name, display_name, gpu_brand, default_gpu_count, default_cpu_cores, default_memory_gib, node_selector, tolerations, resource_quota_key, memory_gb) KEY(id)
VALUES ('spec-nvidia-a100-40g', 'nvidia-a100-40g', 'NVIDIA A100 40GB PCIe', 'NVIDIA',
        1, 8, 32,
        '{"pool":"nvidia-gpu"}',
        '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
        'platform.io/nvidia-a100-40g', 40);

MERGE INTO compute_spec (id, name, display_name, gpu_brand, default_gpu_count, default_cpu_cores, default_memory_gib, node_selector, tolerations, resource_quota_key, memory_gb) KEY(id)
VALUES ('spec-nvidia-rtx4090-24g', 'nvidia-rtx4090-24g', 'NVIDIA RTX 4090 24GB', 'NVIDIA',
        1, 8, 32,
        '{"pool":"nvidia-gpu"}',
        '[{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}]',
        'platform.io/nvidia-rtx4090-24g', 24);

MERGE INTO compute_spec (id, name, display_name, gpu_brand, default_gpu_count, default_cpu_cores, default_memory_gib, node_selector, tolerations, resource_quota_key, memory_gb) KEY(id)
VALUES ('spec-hygon-dcu-32g', 'hygon-dcu-32g', 'Hygon DCU 32GB', 'HYGON',
        1, 8, 32,
        '{"pool":"hygon-dcu"}',
        '[{"key":"amd.com/dcu","operator":"Exists","effect":"NoSchedule"}]',
        'platform.io/hygon-dcu-32g', 32);

MERGE INTO compute_spec (id, name, display_name, gpu_brand, default_gpu_count, default_cpu_cores, default_memory_gib, node_selector, tolerations, resource_quota_key, memory_gb) KEY(id)
VALUES ('spec-huawei-ascend-910b', 'huawei-ascend-910b', 'HUAWEI Ascend 910B 64GB', 'HUAWEI_ASCEND',
        1, 8, 32,
        '{"pool":"huawei-ascend"}',
        '[{"key":"huawei.com/ascend910","operator":"Exists","effect":"NoSchedule"}]',
        'platform.io/huawei-ascend-910b', 64);