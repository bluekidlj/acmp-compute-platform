-- AI Compute Platform - H2 数据库表结构

-- ============================================
-- 物理集群（K8s 集群），标签和污点唯一存储点
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
    node_labels VARCHAR(1024),
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

-- vLLM 模型服务部署记录
CREATE TABLE IF NOT EXISTS model_deployment (
    id VARCHAR(36) PRIMARY KEY,
    resource_pool_id VARCHAR(36) NOT NULL,
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
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 训练任务记录
CREATE TABLE IF NOT EXISTS training_job_record (
    id VARCHAR(36) PRIMARY KEY,
    resource_pool_id VARCHAR(36) NOT NULL,
    k8s_job_name VARCHAR(255),
    job_name VARCHAR(255),
    status VARCHAR(32),
    created_by VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id)
);

-- 资源池凭证
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
    -- 配额（DB 备份 K8s ResourceQuota）
    gpu_slots INT NOT NULL DEFAULT 0,
    cpu_cores INT NOT NULL DEFAULT 0,
    memory_gib INT NOT NULL DEFAULT 0,
    max_pods INT DEFAULT 50,
    node_count INT DEFAULT 1,
    -- 划分维度（从父逻辑池继承）
    hardware_type VARCHAR(64) DEFAULT 'NVIDIA-GPU',
    gpu_type VARCHAR(64) DEFAULT 'NVIDIA',
    job_types VARCHAR(128) DEFAULT 'TRAINING,INFERENCE',
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

-- 工作空间配额：从所属逻辑池分配的资源上限
CREATE TABLE IF NOT EXISTS workspace_quota (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL UNIQUE,
    max_gpu_slots INT DEFAULT 0,
    max_cpu_cores INT DEFAULT 0,
    max_memory_gib INT DEFAULT 0,
    max_pods INT DEFAULT 10,
    max_hours INT DEFAULT 100,
    -- 当前已使用量（任务运行时扣减/恢复）
    used_gpu_slots INT DEFAULT 0,
    used_cpu_cores INT DEFAULT 0,
    used_memory_gib INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workspace_id) REFERENCES workspace(id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_resource_pool_dept ON resource_pool(department_code);
CREATE INDEX IF NOT EXISTS idx_resource_pool_hw ON resource_pool(hardware_type);
CREATE INDEX IF NOT EXISTS idx_credential_pool ON resource_pool_credential(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_user_resource_pool_user ON user_resource_pool(user_id);
CREATE INDEX IF NOT EXISTS idx_user_resource_pool_pool ON user_resource_pool(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_workspace_status ON workspace(status);
CREATE INDEX IF NOT EXISTS idx_workspace_pool ON workspace(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_workspace_quota_ws ON workspace_quota(workspace_id);
CREATE INDEX IF NOT EXISTS idx_rp_pc_pool ON resource_pool_physical_cluster(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_rp_pc_cluster ON resource_pool_physical_cluster(physical_cluster_id);

-- ============================================
-- 算力规格：预设的 K8s ResourceRequirements 模板，含 nodeSelector 和 tolerations
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
    -- ResourceQuota 中的资源键
    resource_quota_key VARCHAR(255),
    memory_gb INT,
    description VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 物理集群拥有的规格及数量
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
    total_quota DECIMAL(10,2) DEFAULT 0,
    allocated_quota DECIMAL(10,2) DEFAULT 0,
    PRIMARY KEY (resource_pool_id, spec_id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);

-- 工作空间 ↔ 逻辑资源池 绑定
CREATE TABLE IF NOT EXISTS workspace_resource_pool (
    workspace_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (workspace_id, resource_pool_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id)
);

-- 工作空间在逻辑池中的按规格配额（资源二次分配）
CREATE TABLE IF NOT EXISTS workspace_pool_spec_quota (
    workspace_id VARCHAR(36) NOT NULL,
    resource_pool_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36) NOT NULL,
    max_quota DECIMAL(10,2) DEFAULT 0,
    used_quota DECIMAL(10,2) DEFAULT 0,
    PRIMARY KEY (workspace_id, resource_pool_id, spec_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);
    resource_pool_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36) NOT NULL,
    total_quota INT DEFAULT 0,
    allocated_quota INT DEFAULT 0,
    PRIMARY KEY (resource_pool_id, spec_id),
    FOREIGN KEY (resource_pool_id) REFERENCES resource_pool(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
);

-- 工作空间按规格的配额（已废弃：工作空间不按规格，直接绑定逻辑池即可）
-- 规格仅用于划分逻辑子池，与工作空间无关

-- 工作空间按规格的配额（平台层追踪，备份 K8s ResourceQuota 计数）
CREATE TABLE IF NOT EXISTS workspace_spec_quota (
    workspace_id VARCHAR(36) NOT NULL,
    spec_id VARCHAR(36) NOT NULL,
    max_quota INT DEFAULT 0,
    used_quota INT DEFAULT 0,
    PRIMARY KEY (workspace_id, spec_id),
    FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    FOREIGN KEY (spec_id) REFERENCES compute_spec(id)
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

-- 初始规格数据
MERGE INTO compute_spec (id, name, display_name, gpu_brand, memory_gb) KEY(id)
VALUES ('spec-nvidia-a100-80g', 'nvidia-a100-80g', 'NVIDIA A100 80GB SXM', 'NVIDIA', 80);
MERGE INTO compute_spec (id, name, display_name, gpu_brand, memory_gb) KEY(id)
VALUES ('spec-nvidia-a100-40g', 'nvidia-a100-40g', 'NVIDIA A100 40GB PCIe', 'NVIDIA', 40);
MERGE INTO compute_spec (id, name, display_name, gpu_brand, memory_gb) KEY(id)
VALUES ('spec-nvidia-rtx4090-24g', 'nvidia-rtx4090-24g', 'NVIDIA RTX 4090 24GB', 'NVIDIA', 24);
MERGE INTO compute_spec (id, name, display_name, gpu_brand, memory_gb) KEY(id)
VALUES ('spec-hygon-dcu-32g', 'hygon-dcu-32g', 'Hygon DCU 32GB', 'HYGON', 32);
MERGE INTO compute_spec (id, name, display_name, gpu_brand, memory_gb) KEY(id)
VALUES ('spec-huawei-ascend-910b', 'huawei-ascend-910b', 'HUAWEI Ascend 910B 64GB', 'HUAWEI_ASCEND', 64);