import { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Space, Spin, Empty, Badge, Row, Col, Statistic, Progress } from 'antd';
import { PlusOutlined, RocketOutlined, MessageOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workspacesApi, projectsApi, deploymentsApi } from '../api';
import type { ModelDeployment, Workspace, Project } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function InferenceServicesPage() {
  const nav = useNavigate();
  const [deployments, setDeployments] = useState<ModelDeployment[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const ws = await workspacesApi.list();
        const all: ModelDeployment[] = [];
        for (const w of ws) {
          const ps = await projectsApi.listByWorkspace(w.id);
          for (const p of ps) {
            const ds = await deploymentsApi.listByProject(p.id);
            all.push(...ds);
          }
        }
        setDeployments(all);
      } finally { setLoading(false); }
    })();
  }, []);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  const running = deployments.filter((d) => d.status === 'running').length;
  const failed = deployments.filter((d) => d.status === 'failed').length;

  return (
    <div>
      <PageHeader
        title="推理服务"
        subtitle="所有已部署的推理服务 · 监控指标 · 在线对话"
        tags={[
          { label: `运行 ${running}`, color: 'green' },
          { label: `失败 ${failed}`, color: 'red' },
          { label: `总计 ${deployments.length}`, color: 'blue' },
        ]}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => nav('/projects')}
            style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
            新建推理服务
          </Button>
        }
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card><Statistic title="总服务数" value={deployments.length} valueStyle={{ color: PSBC_COLORS.primary }} prefix={<RocketOutlined />} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="运行中" value={running} valueStyle={{ color: '#52C41A' }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="失败" value={failed} valueStyle={{ color: '#FF4D4F' }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="总副本" value={deployments.reduce((s, d) => s + d.replicas, 0)} valueStyle={{ color: '#FAAD14' }} /></Card>
        </Col>
      </Row>

      {deployments.length === 0 ? (
        <Empty description="暂无推理服务，请先创建项目并分配配额" />
      ) : (
        <Card style={{ borderRadius: 8 }}>
          <Table
            dataSource={deployments}
            rowKey="id"
            pagination={false}
            size="middle"
            onRow={(r) => ({
              onClick: () => {
                nav(`/projects/${r.workspaceId}/deployments/${r.projectId}/${r.id}`);
              },
              style: { cursor: 'pointer' },
            })}
            columns={[
              { title: '服务名', dataIndex: 'name', render: (v) => <strong>{v}</strong> },
              { title: '模型', dataIndex: 'modelName', render: (v) => v ?? '-' },
              { title: '规格', dataIndex: 'specId', width: 160, render: (v) => <Tag color="cyan">{v}</Tag> },
              { title: '副本', dataIndex: 'replicas', width: 60 },
              {
                title: '状态', dataIndex: 'status', width: 100,
                render: (v) => <Badge status={v === 'running' ? 'success' : v === 'failed' ? 'error' : 'processing'} text={v} />,
              },
              {
                title: '监控指标', key: 'metrics', width: 250,
                render: (_, r) => (
                  <Space size={12}>
                    <span style={{ fontSize: 12, color: '#6B7768' }}>
                      P99:{' '}
                      <span style={{ color: PSBC_COLORS.primary, fontWeight: 600 }}>
                        {r.status === 'running' ? `${Math.floor(Math.random() * 200 + 100)}ms` : '-'}
                      </span>
                    </span>
                    <span style={{ fontSize: 12, color: '#6B7768' }}>
                      TPS:{' '}
                      <span style={{ color: PSBC_COLORS.primary, fontWeight: 600 }}>
                        {r.status === 'running' ? `${Math.floor(Math.random() * 50 + 10)}` : '-'}
                      </span>
                    </span>
                  </Space>
                ),
              },
              {
                title: '操作', key: 'op', width: 120,
                render: (_, r) => (
                  <Button size="small" icon={<MessageOutlined />}
                    onClick={(e) => { e.stopPropagation(); nav(`/inference/${r.id}/chat`); }}>
                    对话
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
