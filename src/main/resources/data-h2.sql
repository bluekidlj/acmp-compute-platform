-- 初始数据：默认组织与管理员（密码 admin123，BCrypt）
MERGE INTO organization (id, name) KEY(id) VALUES ('org-default', 'Default Org');

MERGE INTO users (id, username, password_hash, role, organization_id) KEY(id)
VALUES ('user-admin', 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'PLATFORM_ADMIN', 'org-default');
