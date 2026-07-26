import { useEffect, useState } from 'react';
import { Button, message, Popconfirm, Select, Space, Table } from 'antd';
import { DeleteOutlined, MessageOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ModelDeployment, Project, Tenant } from '../../types';

export default function DeploymentsPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<ModelDeployment[]>([]);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [tenantId, setTenantId] = useState<string>();
  const [status, setStatus] = useState<string>();
  const [loading, setLoading] = useState(false);

  function load() {
    setLoading(true);
    Promise.all([api.deployments({ tenantId, status }), api.tenants()])
      .then(async function loadProjects(values) {
        const [deployments, nextTenants] = values;
        const groups = await Promise.all(nextTenants.map(function query(tenant) {
          return api.projects(tenant.id);
        }));
        setItems(deployments);
        setTenants(nextTenants);
        setProjects(groups.flat());
      })
      .catch(function fail(exception) { message.error(exception.message); })
      .finally(function finish() { setLoading(false); });
  }

  useEffect(load, [tenantId, status]);

  async function deleteDeployment(record: ModelDeployment) {
    try {
      await api.deleteDeployment(record.projectId, record.id);
      message.success('推理服务已删除');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败');
    }
  }

  const tenantMap = Object.fromEntries(tenants.map(function entry(tenant) { return [tenant.id, tenant.name]; }));
  const projectMap = Object.fromEntries(projects.map(function entry(project) { return [project.id, project.name]; }));
  const running = items.filter(function isRunning(item) { return item.status === 'RUNNING'; }).length;
  const failed = items.filter(function isFailed(item) { return item.status === 'FAILED'; }).length;

  return (
    <div>
      <div className="page-heading">
        <div><h1>推理服务</h1><p>真实 Kubernetes 部署、Service 与就绪状态</p></div>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新状态</Button>
      </div>
      <div className="metric-grid">
        <div className="surface metric"><div className="metric-label">全部服务</div><div className="metric-value">{items.length}</div><div className="metric-hint">当前筛选范围</div></div>
        <div className="surface metric"><div className="metric-label">运行中</div><div className="metric-value">{running}</div><div className="metric-hint">Kubernetes Ready</div></div>
        <div className="surface metric"><div className="metric-label">处理中</div><div className="metric-value">{items.length - running - failed}</div><div className="metric-hint">PENDING / SUBMITTED</div></div>
        <div className="surface metric"><div className="metric-label">失败</div><div className="metric-value">{failed}</div><div className="metric-hint">需要检查错误信息</div></div>
      </div>
      <div className="surface data-table">
        <div className="toolbar">
          <Space>
            <Select
              allowClear
              placeholder="全部租户"
              style={{ width: 210 }}
              value={tenantId}
              onChange={setTenantId}
              options={tenants.map(function option(tenant) { return { value: tenant.id, label: tenant.name }; })}
            />
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 170 }}
              value={status}
              onChange={setStatus}
              options={['PENDING', 'SUBMITTED', 'RUNNING', 'FAILED'].map(function option(value) { return { value, label: value }; })}
            />
          </Space>
          <span>创建入口位于项目详情</span>
        </div>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={items}
          pagination={false}
          onRow={function row(record) { return { onClick: function go() { navigate(`/deployments/${record.projectId}/${record.id}`); }, style: { cursor: 'pointer' } }; }}
          columns={[
            { title: '服务名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
            { title: '模型', dataIndex: 'modelName', render: function render(value) { return value || '-'; } },
            { title: '租户', dataIndex: 'tenantId', render: function render(value) { return tenantMap[value] || value; } },
            { title: '项目', dataIndex: 'projectId', render: function render(value) { return projectMap[value] || value; } },
            { title: '端口', dataIndex: 'port', width: 80 },
            { title: '副本', width: 85, render: function render(_, record: ModelDeployment) { return `${record.readyReplicas ?? 0}/${record.replicas}`; } },
            { title: '状态', dataIndex: 'status', width: 120, render: function render(value) { return <StatusBadge value={value} />; } },
            {
              title: '操作',
              width: 180,
              render: function render(_, record: ModelDeployment) {
                return (
                  <Space onClick={function stop(event) { event.stopPropagation(); }}>
                    <Button
                      size="small"
                      icon={<MessageOutlined />}
                      disabled={record.status !== 'RUNNING'}
                      onClick={function chat() {
                        navigate(`/deployments/${record.projectId}/${record.id}/chat`);
                      }}
                    >
                      对话
                    </Button>
                    <Popconfirm
                      title="确认删除该推理服务？"
                      description="将同时删除对应的 Kubernetes Deployment 与 Service。"
                      onConfirm={function remove() { return deleteDeployment(record); }}
                    >
                      <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>
                  </Space>
                );
              },
            },
          ]}
        />
      </div>
    </div>
  );
}
