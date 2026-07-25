import { useEffect, useState } from 'react';
import { ClusterOutlined, DeploymentUnitOutlined, ProjectOutlined, TeamOutlined } from '@ant-design/icons';
import { Alert, Col, Row, Spin, Table } from 'antd';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ModelDeployment, PhysicalCluster, ResourcePool, Tenant } from '../../types';

interface DashboardData {
  clusters: PhysicalCluster[];
  pools: ResourcePool[];
  tenants: Tenant[];
  deployments: ModelDeployment[];
  projectCount: number;
}

export default function DashboardPage() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState('');

  useEffect(function loadDashboard() {
    Promise.all([api.clusters(), api.pools(), api.tenants(), api.deployments()])
      .then(async function buildData(values) {
        const [clusters, pools, tenants, deployments] = values;
        const projectGroups = await Promise.all(tenants.map(function loadProjects(tenant) {
          return api.projects(tenant.id);
        }));
        setData({
          clusters,
          pools,
          tenants,
          deployments,
          projectCount: projectGroups.reduce(function count(sum, projects) {
            return sum + projects.length;
          }, 0),
        });
      })
      .catch(function handleError(exception) {
        setError(exception instanceof Error ? exception.message : '概览加载失败');
      });
  }, []);

  if (error) {
    return <Alert type="error" message="无法加载平台概览" description={error} showIcon />;
  }
  if (!data) {
    return <Spin size="large" />;
  }

  const nodes = data.clusters.reduce(function sumNodes(total, cluster) {
    return total + (cluster.nodeCount || 0);
  }, 0);
  const gpus = data.clusters.reduce(function sumGpus(total, cluster) {
    return total + (cluster.gpuCount || 0);
  }, 0);
  const running = data.deployments.filter(function isRunning(item) {
    return item.status === 'RUNNING';
  }).length;

  return (
    <div>
      <div className="page-heading">
        <div>
          <h1>平台概览</h1>
          <p>来自 Kubernetes 和 ACMP 数据库的实时资源视图</p>
        </div>
      </div>

      <div className="hero-panel">
        <Row gutter={[32, 24]}>
          <Col xs={24} md={10}>
            <div className="hero-label">CONTROL PLANE STATUS</div>
            <div className="hero-value">
              <span className="live-dot" />
              运行正常
            </div>
            <div style={{ marginTop: 9, color: 'rgba(224, 248, 236, .62)' }}>
              {data.clusters.filter(function isActive(item) { return item.status === 'ACTIVE'; }).length}
              {' / '}
              {data.clusters.length} 个集群已连接
            </div>
          </Col>
          <Col xs={12} md={4}>
            <div className="hero-label">NODE</div>
            <div className="hero-value">{nodes}</div>
          </Col>
          <Col xs={12} md={4}>
            <div className="hero-label">GPU</div>
            <div className="hero-value">{gpus}</div>
          </Col>
          <Col xs={12} md={6}>
            <div className="hero-label">INFERENCE RUNNING</div>
            <div className="hero-value">{running}</div>
          </Col>
        </Row>
      </div>

      <div className="metric-grid">
        <Metric icon={<ClusterOutlined />} label="集群" value={data.clusters.length} hint="Kubernetes 集群" />
        <Metric icon={<TeamOutlined />} label="租户" value={data.tenants.length} hint="业务资源边界" />
        <Metric icon={<ProjectOutlined />} label="项目" value={data.projectCount} hint="继承租户算力规格" />
        <Metric icon={<DeploymentUnitOutlined />} label="推理服务" value={data.deployments.length} hint={`${running} 个运行中`} />
      </div>

      <div className="surface data-table">
        <div className="toolbar">
          <strong>最近推理服务</strong>
          <span style={{ color: '#66756f' }}>真实部署状态</span>
        </div>
        <Table
          rowKey="id"
          pagination={false}
          dataSource={data.deployments.slice(0, 6)}
          columns={[
            { title: '服务', dataIndex: 'name' },
            { title: '模型', dataIndex: 'modelName', render: function render(value) { return value || '-'; } },
            { title: '端口', dataIndex: 'port', width: 90 },
            {
              title: '就绪副本',
              width: 110,
              render: function renderReady(_, record: ModelDeployment) {
                return `${record.readyReplicas ?? 0} / ${record.replicas}`;
              },
            },
            { title: '状态', dataIndex: 'status', width: 120, render: function renderStatus(value) { return <StatusBadge value={value} />; } },
          ]}
        />
      </div>
    </div>
  );
}

function Metric(props: { icon: React.ReactNode; label: string; value: number; hint: string }) {
  return (
    <div className="surface metric">
      <div className="metric-label">{props.icon} &nbsp;{props.label}</div>
      <div className="metric-value">{props.value}</div>
      <div className="metric-hint">{props.hint}</div>
    </div>
  );
}
