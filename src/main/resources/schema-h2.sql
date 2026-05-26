-- AI Compute Platform - H2 数据库表结构（Demo 使用）

-- 物理集群（K8s 集群）
CREATE TABLE IF NOT EXISTS physical_cluster (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(512),
    kubeconfig_base64_encrypted CLOB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    total_gpu_slots INT,
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

-- 逻辑资源池（Namespace + ResourceQuota + RBAC + Volcano Queue）
CREATE TABLE IF NOT EXISTS resource_pool (
    id VARCHAR(36) PRIMARY KEY,
    physical_cluster_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    department_code VARCHAR(64) NOT NULL,
    department_name VARCHAR(255),
    namespace VARCHAR(255) NOT NULL UNIQUE,
    service_account_name VARCHAR(255),
    gpu_slots INT NOT NULL,
    cpu_cores INT NOT NULL,
    memory_gib INT NOT NULL,
    max_pods INT DEFAULT 50,
    volcano_queue_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
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

-- 训练任务记录（可选，便于列表展示）
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

-- 资源池凭证（为部门用户发放的 K8s 访问凭证）
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

-- 索引：加速查询
CREATE INDEX IF NOT EXISTS idx_resource_pool_dept ON resource_pool(department_code);
CREATE INDEX IF NOT EXISTS idx_resource_pool_cluster ON resource_pool(physical_cluster_id);
CREATE INDEX IF NOT EXISTS idx_credential_pool ON resource_pool_credential(resource_pool_id);
CREATE INDEX IF NOT EXISTS idx_user_resource_pool_user ON user_resource_pool(user_id);
CREATE INDEX IF NOT EXISTS idx_user_resource_pool_pool ON user_resource_pool(resource_pool_id);