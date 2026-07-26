import { useState, useEffect } from 'react';
import { Layout, Menu, Button, Dropdown, Space, theme, Select } from 'antd';
import {
  DashboardOutlined,
  AppstoreOutlined,
  ClusterOutlined,
  RocketOutlined,
  CloudServerOutlined,
  MonitorOutlined,
  AlertOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
  ExperimentOutlined,
  DatabaseOutlined,
  HddOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  BulbOutlined,
  ApiOutlined,
  FundOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useCluster } from '../contexts/ClusterContext';
import { ROLE_LABELS } from '../types';
import { PSBC_GREEN, PSBC_COLORS } from '../theme';

const { Header, Sider, Content } = Layout;

const AppLayout: React.FC = () => {
  const nav = useNavigate();
  const loc = useLocation();
  const { username, role, logout } = useAuth();
  const { clusterId, clusterName, setClusterId } = useCluster();
  const { token } = theme.useToken();

  // 3 大模块
  const items = [
    {
      key: 'smart-ops',
      icon: <AppstoreOutlined />,
      label: '智算运营',
      children: [
        { key: '/', icon: <DashboardOutlined />, label: '平台概览' },
        { key: '/screen', icon: <MonitorOutlined />, label: '算力大屏' },
        { key: '/inference', icon: <RocketOutlined />, label: '推理服务' },
        { key: '/projects', icon: <HddOutlined />, label: '项目' },
        {
          key: 'resources',
          icon: <ClusterOutlined />,
          label: '资源管理',
          children: [
            { key: '/resources/specs', label: '算力规格' },
            { key: '/resources/pools', label: '物理资源池' },
          ],
        },
        { key: '/models', icon: <DatabaseOutlined />, label: '模型广场' },
        { key: '/training', icon: <ExperimentOutlined />, label: '训练管理' },
      ],
    },
    {
      key: 'lab',
      icon: <BulbOutlined />,
      label: '创新实验室',
      children: [
        { key: '/lab', icon: <FundOutlined />, label: '总览' },
        { key: '/lab/digital-twin', icon: <ApiOutlined />, label: '数字孪生' },
        { key: '/lab/strategy-lab', icon: <ExperimentOutlined />, label: '策略实验室' },
        { key: '/lab/workload', icon: <FundOutlined />, label: '负载感知' },
        { key: '/lab/governance', icon: <SafetyCertificateOutlined />, label: '数据治理' },
      ],
    },
    {
      key: 'monitoring',
      icon: <MonitorOutlined />,
      label: '监控预警',
      children: [
        { key: '/monitoring', icon: <MonitorOutlined />, label: '运维监控看板' },
        { key: '/monitoring/alerts', icon: <AlertOutlined />, label: '告警列表' },
        { key: '/monitoring/rules', icon: <SettingOutlined />, label: '告警规则' },
      ],
    },
    {
      key: 'cluster-ops',
      icon: <ToolOutlined />,
      label: '集群运维',
      children: [
        { key: '/clusters', icon: <CloudServerOutlined />, label: '物理集群' },
        { key: '/workloads', icon: <RocketOutlined />, label: '负载管理' },
        { key: '/storage', icon: <HddOutlined />, label: '存储资源' },
      ],
    },
  ];

  // 找到当前匹配的 key
  const selectedKeys = [loc.pathname];
  const parentKeys = items
    .filter((m) => m.children?.some((c: any) => c.children
      ? c.children.some((cc: any) => cc.key === loc.pathname)
      : c.key === loc.pathname))
    .map((m) => m.key);
  const [openKeys, setOpenKeys] = useState<string[]>(parentKeys);
  
  useEffect(() => {
    setOpenKeys((prev) => {
      const next = [...new Set([...prev, ...parentKeys])];
      return next;
    });
  }, [loc.pathname]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={240}
        style={{ background: PSBC_GREEN.token.colorBgContainer, borderRight: `1px solid ${PSBC_COLORS.border}` }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderBottom: `1px solid ${PSBC_COLORS.border}`,
            gap: 8,
          }}
        >
          <ThunderboltOutlined style={{ fontSize: 26, color: PSBC_GREEN.token.colorPrimary }} />
          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: PSBC_GREEN.token.colorPrimary, lineHeight: 1.2 }}>
              ACMP
            </div>
            <div style={{ fontSize: 10, color: '#6B7768', lineHeight: 1.2 }}>算力管理平台</div>
          </div>
        </div>
        <Menu
          mode="inline"
          items={items}
          selectedKeys={selectedKeys}
          openKeys={openKeys}
          onOpenChange={setOpenKeys}
          onClick={({ key }) => nav(key)}
          style={{ borderRight: 0, paddingTop: 8 }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            borderBottom: `1px solid ${PSBC_COLORS.border}`,
          }}
        >
          <Space>
            <span style={{ color: '#6B7768', fontSize: 12 }}>
              ACMP · 异构算力管理
            </span>
          </Space>
          <Space>
            <Select
              value={clusterId}
              onChange={setClusterId}
              size="small"
              style={{ width: 160 }}
              options={[
                { value: 'cluster-bj-01', label: '北京生产 K8s 集群' },
                { value: 'cluster-sh-01', label: '上海测试 K8s 集群' },
              ]}
            />
          <Dropdown
            menu={{
              items: [
                {
                  key: 'info',
                  label: (
                    <div style={{ padding: '4px 0' }}>
                      <div><strong>{username || 'admin'}</strong></div>
                      <div style={{ fontSize: 12, color: '#6B7768' }}>{role && ROLE_LABELS[role]}</div>
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
              ],
              onClick: ({ key }) => {
                if (key === 'logout') {
                  logout();
                  nav('/login');
                }
              },
            }}
            placement="bottomRight"
          >
            <Button type="text" icon={<UserOutlined />}>
              {username || 'admin'}
            </Button>
          </Dropdown>
          </Space>
        </Header>
        <Content style={{ margin: 16, background: 'transparent' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default AppLayout;
