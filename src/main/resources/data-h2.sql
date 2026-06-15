-- 初始数据：默认组织与管理员（密码 admin123，BCrypt）
MERGE INTO organization (id, code, name) KEY(id)
VALUES ('org-default', 'DEFAULT', 'Default Org');

MERGE INTO app_user (id, username, password_hash, display_name, role, organization_id) KEY(id)
VALUES ('user-admin', 'admin', '$2a$10$tllIrxiBy4rhAf3Df1v2LO86yf6so9jZMAxbC16/uKAdOr0jPRTS6',
        'Platform Admin', 'PLATFORM_ADMIN', 'org-default');
