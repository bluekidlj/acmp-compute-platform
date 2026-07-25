import { useEffect, useMemo, useState } from 'react';
import {
  AppstoreOutlined,
  ApiOutlined,
  ClusterOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  ExperimentOutlined,
  LogoutOutlined,
  ProjectOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Avatar, Breadcrumb, Button, Dropdown, Layout, Menu, Select, Space } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/real';
import { useAuth } from '../contexts/AuthContext';
import { useCluster } from '../contexts/ClusterContext';
import type { PhysicalCluster } from '../types';
import { ROLE_LABELS } from '../types';

const { Header, Sider, Content } = Layout;

export default function RealLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { username, role, logout } = useAuth();
  const { clusterId, setClusterId } = useCluster();
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [collapsed, setCollapsed] = useState(false);

  useEffect(function loadClusters() {
    api.clusters()
      .then(function handleClusters(items) {
        setClusters(items);
        if (!clusterId && items.length > 0) {
          setClusterId(items[0].id);
        }
      })
      .catch(function ignoreHeaderFailure() {
        setClusters([]);
      });
  }, [clusterId, setClusterId]);

  useEffect(function watchWidth() {
    function updateCollapsed() {
      setCollapsed(window.innerWidth < 1100);
    }
    updateCollapsed();
    window.addEventListener('resize', updateCollapsed);
    return function cleanup() {
      window.removeEventListener('resize', updateCollapsed);
    };
  }, []);

  const items = useMemo(function buildMenu() {
    return [
      {
        key: '/',
        icon: <AppstoreOutlined />,
        label: '平台概览',
      },
      {
        key: 'resources',
        icon: <ClusterOutlined />,
        label: '算力资源',
        children: [
          { key: '/clusters', icon: <ClusterOutlined />, label: '集群管理' },
          { key: '/resource-pools', icon: <ApiOutlined />, label: '资源池' },
          { key: '/specs', icon: <SettingOutlined />, label: '算力规格' },
        ],
      },
      {
        key: 'business',
        icon: <ProjectOutlined />,
        label: '业务管理',
        children: [
          { key: '/tenants', icon: <TeamOutlined />, label: '租户' },
          { key: '/projects', icon: <ProjectOutlined />, label: '项目' },
          { key: '/models', icon: <DatabaseOutlined />, label: '模型广场' },
          { key: '/deployments', icon: <DeploymentUnitOutlined />, label: '推理服务' },
        ],
      },
      {
        key: '/innovation-lab',
        icon: <ExperimentOutlined />,
        label: '创新实验室',
      },
    ];
  }, []);

  function handleMenuClick(info: { key: string }) {
    navigate(info.key);
  }

  function handleLogout() {
    logout();
    navigate('/login');
  }

  const selectedKey = location.pathname.startsWith('/clusters')
    ? '/clusters'
    : location.pathname.startsWith('/resource-pools')
      ? '/resource-pools'
      : location.pathname.startsWith('/specs')
        ? '/specs'
        : location.pathname.startsWith('/tenants')
          ? '/tenants'
          : location.pathname.startsWith('/projects')
            ? '/projects'
            : location.pathname.startsWith('/models')
              ? '/models'
              : location.pathname.startsWith('/deployments')
                ? '/deployments'
                : location.pathname.startsWith('/innovation-lab')
                  ? '/innovation-lab'
                : '/';

  const currentPageName = selectedKey === '/'
    ? '平台概览'
    : selectedKey === '/clusters'
      ? '集群管理'
      : selectedKey === '/resource-pools'
        ? '资源池'
        : selectedKey === '/specs'
          ? '算力规格'
          : selectedKey === '/tenants'
            ? '租户'
            : selectedKey === '/projects'
              ? '项目'
              : selectedKey === '/models'
                ? '模型广场'
                : selectedKey === '/deployments'
                  ? '推理服务'
                  : '创新实验室';

  return (
    <Layout className="app-shell">
      <Sider
        width={232}
        collapsedWidth={72}
        collapsed={collapsed}
        collapsible
        theme="dark"
        className="app-sider"
        onCollapse={setCollapsed}
      >
        <div className="brand">
          <div className="brand-mark">
            <img src="/acmp-logo.png" alt="ACMP" />
          </div>
          {!collapsed && (
            <div>
              <div className="brand-name">ACMP</div>
              <div className="brand-subtitle">算力管理平台</div>
            </div>
          )}
        </div>
        <Menu
          mode="inline"
          theme="dark"
          selectedKeys={[selectedKey]}
          defaultOpenKeys={['resources', 'business']}
          items={items}
          onClick={handleMenuClick}
          className="app-menu"
        />
      </Sider>

      <Layout>
        <Header className="app-header">
          <Breadcrumb
            items={[
              { title: '异构算力统一管理' },
              { title: currentPageName },
            ]}
          />
          <Space size={16}>
            <Select
              value={clusterId || undefined}
              placeholder="选择 Kubernetes 集群"
              className="cluster-select"
              onChange={setClusterId}
              options={clusters.map(function toOption(cluster) {
                return {
                  value: cluster.id,
                  label: cluster.name,
                };
              })}
            />
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'profile',
                    disabled: true,
                    label: role ? ROLE_LABELS[role] : '用户',
                  },
                  { type: 'divider' },
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    danger: true,
                    onClick: handleLogout,
                  },
                ],
              }}
            >
              <Button type="text" className="user-button">
                <Avatar size="small" icon={<UserOutlined />} />
                {username}
              </Button>
            </Dropdown>
          </Space>
        </Header>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
