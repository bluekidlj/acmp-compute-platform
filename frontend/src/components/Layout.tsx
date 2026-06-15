import React from 'react';
import { Layout, Menu, Button, Dropdown, Space, Typography, theme, Tag } from 'antd';
import {
  DashboardOutlined,
  CloudServerOutlined,
  SettingOutlined,
  AppstoreOutlined,
  HddOutlined,
  ThunderboltOutlined,
  RocketOutlined,
  UserOutlined,
  LogoutOutlined,
  ExperimentOutlined,
  PartitionOutlined,
  MessageOutlined,
  CloudOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { ROLE_LABELS } from '../types';
import { USE_MOCK } from '../mock';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { username, role, isAdmin, logout } = useAuth();
  const { token: themeToken } = theme.useToken();

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: '概览' },
    { key: '/physical-clusters', icon: <CloudServerOutlined />, label: '物理集群' },
    { key: '/specs', icon: <SettingOutlined />, label: '算力规格' },
    { key: '/resource-pools', icon: <AppstoreOutlined />, label: '资源池' },
    { key: '/workspaces', icon: <HddOutlined />, label: '工作空间' },
    { key: '/deployments', icon: <RocketOutlined />, label: '部署服务' },
    { key: '/inference-chat', icon: <MessageOutlined />, label: '推理对话' },
    { key: '/models', icon: <CloudOutlined />, label: '模型广场' },
    { key: '/hami-gpu-configs', icon: <PartitionOutlined />, label: 'GPU 切分配置' },
  ];

  const currentPath = '/' + (location.pathname.split('/')[1] || '');

  const userMenuItems = [
    {
      key: 'info',
      label: (
        <div style={{ padding: '4px 0' }}>
          <div><strong>{username}</strong></div>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {role ? ROLE_LABELS[role] : ''}
          </Text>
        </div>
      ),
      disabled: true,
    },
    { type: 'divider' as const },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      danger: true,
    },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        breakpoint="lg"
        collapsedWidth="64"
        style={{ background: themeToken.colorBgContainer }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderBottom: `1px solid ${themeToken.colorBorderSecondary}`,
          }}
        >
          <ThunderboltOutlined style={{ fontSize: 28, color: themeToken.colorPrimary }} />
          <span
            style={{
              marginLeft: 12,
              fontSize: 18,
              fontWeight: 700,
              color: themeToken.colorText,
              whiteSpace: 'nowrap',
            }}
          >
            ACMP
          </span>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[currentPath]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ borderRight: 0, marginTop: 8 }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: themeToken.colorBgContainer,
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            borderBottom: `1px solid ${themeToken.colorBorderSecondary}`,
          }}
        >
          {USE_MOCK && (
            <Tag icon={<ExperimentOutlined />} color="orange" style={{ marginRight: 'auto' }}>
              Mock 模式 — 数据均为模拟
            </Tag>
          )}
          <Dropdown
            menu={{
              items: userMenuItems,
              onClick: ({ key }) => {
                if (key === 'logout') {
                  logout();
                  navigate('/login');
                }
              },
            }}
            placement="bottomRight"
          >
            <Button type="text" icon={<UserOutlined />} style={{ height: 40 }}>
              {username}
            </Button>
          </Dropdown>
        </Header>
        <Content style={{ margin: 16 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default AppLayout;
