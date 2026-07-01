import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Table, Tag, Spin, Empty, Badge } from 'antd';
import { deploymentsApi, projectsApi } from '../api';
import type { ModelDeployment, Project } from '../types';
import PageHeader from '../components/PageHeader';

export default function DeploymentsListPage() {
  const { projectId, wsId } = useParams<{ projectId: string; wsId: string }>();
  const nav = useNavigate();
  const [items, setItems] = useState<ModelDeployment[]>([]);
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      if (!projectId) return;
      try {
        const [p, ds] = await Promise.all([projectsApi.get(projectId), deploymentsApi.listByProject(projectId)]);
        setProject(p);
        setItems(ds);
      } finally { setLoading(false); }
    })();
  }, [projectId]);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  if (!project) return <Empty description="项目不存在" />;

  return (
    <div>
      <PageHeader
        title={`${project.name} · 部署`}
        subtitle={`workspaceId=${project.workspaceId.slice(0, 8)}... · 项目 ID: ${project.id.slice(0, 8)}...`}
        tags={[
          { label: `总计 ${items.length}`, color: 'blue' },
          { label: `运行 ${items.filter((d) => d.status === 'running').length}`, color: 'green' },
          { label: `失败 ${items.filter((d) => d.status === 'failed').length}`, color: 'red' },
        ]}
      />
      <Card style={{ borderRadius: 8 }}>
        <Table
          dataSource={items}
          rowKey="id"
          pagination={false}
          size="small"
          onRow={(r) => ({ onClick: () => nav(`/projects/${r.workspaceId}/deployments/${projectId}/${r.id}`), style: { cursor: 'pointer' } })}
          columns={[
            { title: '名称', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
            { title: '模型', dataIndex: 'modelName' },
            { title: '规格', dataIndex: 'specId', render: (v) => <Tag color="cyan">{v}</Tag> },
            { title: '副本', dataIndex: 'replicas', width: 60 },
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Badge status={v === 'running' ? 'success' : v === 'failed' ? 'error' : 'processing'} text={v} /> },
            { title: 'Ready', dataIndex: 'readyReplicas', width: 70, render: (v) => v ?? '-' },
            { title: 'K8s 部署', dataIndex: 'k8sDeploymentName', render: (v) => v ? <code className="mono">{v}</code> : '-' },
            { title: 'URL', dataIndex: 'serviceUrl', ellipsis: true, render: (v) => v ? <code className="mono" style={{ fontSize: 11 }}>{v}</code> : '-' },
            { title: '创建时间', dataIndex: 'createdAt', render: (v) => v?.slice(0, 16) },
          ]}
        />
      </Card>
    </div>
  );
}