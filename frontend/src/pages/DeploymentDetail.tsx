import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Spin, Empty, Tag, Badge, Button, Space, message, Popconfirm } from 'antd';
import { ArrowLeftOutlined, MessageOutlined, DeleteOutlined } from '@ant-design/icons';
import { deploymentsApi, projectsApi } from '../api';
import type { ModelDeployment, Project } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function DeploymentDetailPage() {
  const { projectId, deploymentId } = useParams<{ projectId: string; deploymentId: string }>();
  const nav = useNavigate();
  const [dep, setDep] = useState<ModelDeployment | null>(null);
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    if (!projectId || !deploymentId) return;
    setLoading(true);
    try {
      const [d, p] = await Promise.all([
        deploymentsApi.get(projectId, deploymentId),
        projectsApi.get(projectId),
      ]);
      setDep(d);
      setProject(p);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [projectId, deploymentId]);

  const handleDelete = async () => {
    try {
      await deploymentsApi.remove(projectId!, deploymentId!);
      message.success('已删除');
      nav(`/logical/deployments/${projectId}`);
    } catch (e: any) { message.error(e?.message || '删除失败'); }
  };

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  if (!dep) return <Empty description="部署不存在" />;

  return (
    <div>
      <PageHeader
        title={dep.name}
        subtitle={`项目: ${project?.name || '?'} · 规格: ${dep.specId}`}
        tags={[{ label: dep.status, color: dep.status === 'running' ? 'green' : dep.status === 'failed' ? 'red' : 'orange' }]}
        extra={
          <Space>
            <Button type="primary" icon={<MessageOutlined />}
              onClick={() => nav(`/inference-chat/${deploymentId}`)}
              style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
              打开对话
            </Button>
            <Popconfirm title="确认删除？" onConfirm={handleDelete}>
              <Button danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
            <Button icon={<ArrowLeftOutlined />} onClick={() => nav(`/logical/deployments/${projectId}`)}>
              返回
            </Button>
          </Space>
        }
      />
      <Card style={{ borderRadius: 8 }}>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="ID"><code className="mono">{dep.id}</code></Descriptions.Item>
          <Descriptions.Item label="状态">
            <Badge status={dep.status === 'running' ? 'success' : dep.status === 'failed' ? 'error' : 'processing'} text={dep.status} />
          </Descriptions.Item>
          <Descriptions.Item label="模型名">{dep.modelName || '-'}</Descriptions.Item>
          <Descriptions.Item label="模型来源">{dep.modelSource || '-'}</Descriptions.Item>
          <Descriptions.Item label="模型路径" span={2}><code className="mono">{dep.modelIdOrPath || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="镜像" span={2}><code className="mono">{dep.vllmImage || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="副本数">{dep.replicas}</Descriptions.Item>
          <Descriptions.Item label="GPU/副本">{dep.gpuPerReplica}</Descriptions.Item>
          <Descriptions.Item label="K8s Deployment"><code className="mono">{dep.k8sDeploymentName || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="K8s Service"><code className="mono">{dep.k8sServiceName || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="Service URL" span={2}><code className="mono" style={{ fontSize: 11 }}>{dep.serviceUrl || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="Resource Key"><code className="mono">{dep.resourceKey || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="Pool Card ID"><code className="mono">{dep.poolCardId || '-'}</code></Descriptions.Item>
          <Descriptions.Item label="实际集群">{dep.actualClusterId || '-'}</Descriptions.Item>
          <Descriptions.Item label="创建者">{dep.createdBy}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{dep.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  );
}