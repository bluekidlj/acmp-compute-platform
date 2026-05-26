-- AI Compute Platform - H2 数据库表结构

-- ============================================
-- 物理集群（K8s 集群），按 GPU 类型/地域划分
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
-- 逻辑资源池：资源的初次划分（按硬件/性能/安全/地域）
-- 可跨多个物理集群（M2M via resource_pool_physical_cluster）
-- ============================================
CREATE TABLE IF NOT EXISTS resource_pool (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(512),
    department_code VARCHAR(64) NOT NULL,
    department_name VARCHAR(255),
    namespace VARCHAR(255) NOT NULL UNIQUE,
    service_account_name VARCHAR(255),
    -- 总配额（平台管理员设置）
    gpu_slots INT NOT NULL,
    cpu_cores INT NOT NULL,
    memory_gib INT NOT NULL,
    max_pods INT DEFAULT 50,
    node_count INT DEFAULT 1,
    -- 已分配给各工作空间的累计值
    allocated_gpu_slots INT DEFAULT 0,
    allocated_cpu_cores INT DEFAULT 0,
    allocated_memory_gib INT DEFAULT 0,
    -- 划分维度
    hardware_type VARCHAR(64) DEFAULT 'NVIDIA-GPU',
    security_level VARCHAR(32) DEFAULT 'NORMAL',
    -- 作业类型控制
    gpu_type VARCHAR(64) DEFAULT 'NVIDIA',
    job_types VARCHAR(128) DEFAULT 'TRAINING,INFERENCE',
    volcano_queue_name VARCHAR(255) NOT NULL,
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
-- 工作空间：资源的二次分配（按项目/团队/用户）
-- 每个工作空间属于一个逻辑资源池（N:1）
-- ============================================
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(36) PRIMARY KEY,
    resource_pool_id VARCHAR(36) NOT NULL,
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