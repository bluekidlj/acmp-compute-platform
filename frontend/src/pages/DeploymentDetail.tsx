import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Spin, Empty, Tag, Badge, Button, Space, message, Popconfirm, Row, Col, Select } from 'antd';
import { ArrowLeftOutlined, MessageOutlined, DeleteOutlined } from '@ant-design/icons';
import { deploymentsApi, projectsApi } from '../api';
import type { ModelDeployment, Project } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area, Legend } from 'recharts';

export default function DeploymentDetailPage() {
  const { projectId, deploymentId, wsId } = useParams<{ projectId: string; deploymentId: string; wsId: string }>();
  const nav = useNavigate();
  const [dep, setDep] = useState<ModelDeployment | null>(null);
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [timeRange, setTimeRange] = useState('1h');

  const generateTimeSeries = (points: number) => {
    const now = Date.now();
    return Array.from({ length: points }, (_, i) => ({
      time: new Date(now - (points - 1 - i) * 60000).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      latency: Math.floor(Math.random() * 120 + 40),
      p99: Math.floor(Math.random() * 200 + 100),
      ttft: Math.floor(Math.random() * 400 + 200),
      tps: Math.floor(Math.random() * 60 + 15),
    }));
  };

  const pointCounts: Record<string, number> = { '1h': 60, '6h': 72, '24h': 96, '7d': 168 };
  const [chartData] = useState(() => generateTimeSeries(pointCounts['1h']));

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
      nav(`/projects/${wsId}/deployments/${projectId}`);
    } catch (e: any) { message.error(e?.message || '删除失败'); }
  };

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  if (!dep) return <Empty description="部署不存在" />;

  const metrics = dep.status === 'running' ? {
    avgLatency: Math.round(chartData.reduce((s, d) => s + d.latency, 0) / chartData.length),
    p99Latency: Math.round(chartData.reduce((s, d) => s + d.p99, 0) / chartData.length),
    ttft: Math.round(chartData.reduce((s, d) => s + d.ttft, 0) / chartData.length),
    tps: Math.round(chartData.reduce((s, d) => s + d.tps, 0) / chartData.length),
  } : null;

  return (
    <div>
      <PageHeader
        title={dep.name}
        subtitle={`项目: ${project?.name || '?'} · 规格: ${dep.specId}`}
        tags={[{ label: dep.status, color: dep.status === 'running' ? 'green' : dep.status === 'failed' ? 'red' : 'orange' }]}
        extra={
          <Space>
            <Button type="primary" icon={<MessageOutlined />}
               onClick={() => nav(`/inference/${deploymentId}/chat`)}
              style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
              打开对话
            </Button>
            <Popconfirm title="确认删除？" onConfirm={handleDelete}>
              <Button danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
            <Button icon={<ArrowLeftOutlined />} onClick={() => nav(`/projects/${wsId}/deployments/${projectId}`)}>
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

      {metrics && (
        <Card
          title="调用监控"
          style={{ borderRadius: 8, marginTop: 16 }}
          extra={
            <Select value={timeRange} onChange={setTimeRange} size="small" style={{ width: 120 }}
              options={[
                { value: '1h', label: '近 1 小时' },
                { value: '6h', label: '近 6 小时' },
                { value: '24h', label: '近 24 小时' },
                { value: '7d', label: '近 7 天' },
              ]}
            />
          }
        >
          <Row gutter={[16, 16]}>
            <Col span={6}><Card size="small"><div style={{ fontSize: 12, color: '#6B7768' }}>平均延迟</div><div style={{ fontSize: 22, fontWeight: 700, color: metrics.avgLatency > 150 ? '#FAAD14' : '#52C41A' }}>{metrics.avgLatency}ms</div></Card></Col>
            <Col span={6}><Card size="small"><div style={{ fontSize: 12, color: '#6B7768' }}>P99 延迟</div><div style={{ fontSize: 22, fontWeight: 700, color: metrics.p99Latency > 300 ? '#FF4D4F' : '#FAAD14' }}>{metrics.p99Latency}ms</div></Card></Col>
            <Col span={6}><Card size="small"><div style={{ fontSize: 12, color: '#6B7768' }}>首 Token 时延 (TTFT)</div><div style={{ fontSize: 22, fontWeight: 700, color: metrics.ttft > 500 ? '#FAAD14' : '#52C41A' }}>{metrics.ttft}ms</div></Card></Col>
            <Col span={6}><Card size="small"><div style={{ fontSize: 12, color: '#6B7768' }}>吞吐 (TPS)</div><div style={{ fontSize: 22, fontWeight: 700, color: PSBC_COLORS.primary }}>{metrics.tps}</div></Card></Col>
          </Row>
          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col span={12}>
              <div style={{ fontSize: 13, fontWeight: 500, marginBottom: 8, color: '#1F2A24' }}>延迟趋势</div>
              <ResponsiveContainer width="100%" height={220}>
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                  <XAxis dataKey="time" fontSize={11} tick={{ fill: '#6B7768' }} />
                  <YAxis fontSize={11} tick={{ fill: '#6B7768' }} />
                  <Tooltip />
                  <Legend />
                  <Line type="monotone" dataKey="latency" stroke="#52C41A" name="平均延迟(ms)" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="p99" stroke="#FAAD14" name="P99延迟(ms)" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </Col>
            <Col span={12}>
              <div style={{ fontSize: 13, fontWeight: 500, marginBottom: 8, color: '#1F2A24' }}>吞吐 & 首 Token 时延</div>
              <ResponsiveContainer width="100%" height={220}>
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                  <XAxis dataKey="time" fontSize={11} tick={{ fill: '#6B7768' }} />
                  <YAxis fontSize={11} tick={{ fill: '#6B7768' }} />
                  <Tooltip />
                  <Legend />
                  <Line type="monotone" dataKey="tps" stroke={PSBC_COLORS.primary} name="吞吐(TPS)" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="ttft" stroke="#FF4D4F" name="TTFT(ms)" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </Col>
          </Row>
        </Card>
      )}
    </div>
  );
}