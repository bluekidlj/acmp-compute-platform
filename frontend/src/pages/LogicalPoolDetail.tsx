import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Tabs, Table, Tag, Spin, Empty, Button, Space, Row, Col, Statistic, Modal,
  Form, Input, InputNumber, Select, message, Progress, Descriptions, Badge,
} from 'antd';
import { ArrowLeftOutlined, PlusOutlined, RocketOutlined, ClusterOutlined, DashboardOutlined } from '@ant-design/icons';
import { workspacesApi, poolsApi, projectsApi, quotasApi, deploymentsApi, cardsApi } from '../api';
import { mockMonitoring } from '../mock/data';
import type { Workspace, ResourcePool, Project, ProjectQuota, ModelDeployment, PoolCard } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const POOL_LABELS = { EXCLUSIVE: '独占', SHARED: '共享', OVERSELL: '超分' } as const;

export default function LogicalPoolDetailPage() {
  const { wsId } = useParams<{ wsId: string }>();
  const nav = useNavigate();

  const [ws, setWs] = useState<Workspace | null>(null);
  const [pools, setPools] = useState<ResourcePool[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [quotas, setQuotas] = useState<ProjectQuota[]>([]);
  const [allDeployments, setAllDeployments] = useState<ModelDeployment[]>([]);
  const [cards, setCards] = useState<Record<string, PoolCard[]>>({});
  const [loading, setLoading] = useState(true);

  const [quotaOpen, setQuotaOpen] = useState(false);
  const [quotaProjectId, setQuotaProjectId] = useState<string>('');
  const [quotaForm] = Form.useForm();

  const [deployOpen, setDeployOpen] = useState(false);
  const [deployProjectId, setDeployProjectId] = useState<string>('');
  const [deployForm] = Form.useForm();

  const load = async () => {
    if (!wsId) return;
    setLoading(true);
    try {
      const w = await workspacesApi.get(wsId);
      setWs(w);
      const ps = await poolsApi.listByWorkspace(wsId);
      setPools(ps);
      const prs = await projectsApi.listByWorkspace(wsId);
      setProjects(prs);
      const allQuotas: ProjectQuota[] = [];
      const allDeploys: ModelDeployment[] = [];
      const cardsMap: Record<string, PoolCard[]> = {};
      for (const pr of prs) {
        const qs = await quotasApi.listByProject(pr.id);
        allQuotas.push(...qs);
        const ds = await deploymentsApi.listByProject(pr.id);
        allDeploys.push(...ds);
      }
      for (const p of ps) {
        const cl = await cardsApi.listByPool(p.id);
        cardsMap[p.id] = cl.cards;
      }
      setQuotas(allQuotas);
      setAllDeployments(allDeploys);
      setCards(cardsMap);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [wsId]);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  if (!ws) return <Empty description="工作空间不存在" />;

  const totalNodes = pools.reduce((s, p) => s + p.totalNodes, 0);
  const allocatedNodes = pools.reduce((s, p) => s + p.allocatedNodes, 0);
  const runningDeploys = allDeployments.filter((d) => d.status === 'running').length;
  const totalQuotas = quotas.reduce((s, q) => s + q.totalNodes, 0);
  const usedQuotas = quotas.reduce((s, q) => s + q.usedNodes, 0);

  const openQuotaModal = (projectId: string) => {
    setQuotaProjectId(projectId);
    quotaForm.resetFields();
    setQuotaOpen(true);
  };
  const handleAllocate = async () => {
    const v = await quotaForm.validateFields();
    try {
      await quotasApi.allocate(quotaProjectId, { poolId: v.poolId, specId: v.specId, totalNodes: v.totalNodes });
      message.success('配额分配成功');
      setQuotaOpen(false);
      load();
    } catch (e: any) { message.error(e?.message || '分配失败'); }
  };

  const openDeployModal = (projectId: string) => {
    setDeployProjectId(projectId);
    deployForm.resetFields();
    setDeployOpen(true);
  };
  const handleDeploy = async () => {
    const v = await deployForm.validateFields();
    try {
      await deploymentsApi.create(deployProjectId, v);
      message.success('部署已提交');
      setDeployOpen(false);
      load();
    } catch (e: any) { message.error(e?.message || '部署失败'); }
  };

  return (
    <div>
      <PageHeader
        title={ws.name}
        subtitle={`${ws.description || ''} · ${ws.namespace}`}
        tags={[
          { label: ws.status === 'active' ? '活跃' : '停用', color: ws.status === 'active' ? 'green' : 'red' },
          { label: `${projects.length} 项目`, color: 'blue' },
          { label: `${pools.length} 池`, color: 'cyan' },
          { label: `${runningDeploys} 运行中`, color: 'purple' },
        ]}
        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => nav('/logical/workspaces')}>返回</Button>}
      />

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="总节点" value={totalNodes} valueStyle={{ color: PSBC_COLORS.primary }} /></Card></Col>
        <Col span={6}><Card><Statistic title="已分配" value={allocatedNodes} valueStyle={{ color: '#FAAD14' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="运行中部署" value={runningDeploys} valueStyle={{ color: '#52C41A' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="配额使用率" value={totalQuotas ? Math.round(usedQuotas / totalQuotas * 100) : 0} suffix="%" /></Card></Col>
      </Row>

      <Tabs
        defaultActiveKey="overview"
        items={[
          {
            key: 'overview',
            label: <span><DashboardOutlined /> 概览</span>,
            children: (
              <Card style={{ borderRadius: 8 }}>
                <Descriptions column={2} size="small">
                  <Descriptions.Item label="ID"><code className="mono">{ws.id}</code></Descriptions.Item>
                  <Descriptions.Item label="Namespace"><code className="mono">{ws.namespace}</code></Descriptions.Item>
                  <Descriptions.Item label="物理集群">{ws.primaryClusterName}</Descriptions.Item>
                  <Descriptions.Item label="SA"><code className="mono">{ws.serviceAccountName}</code></Descriptions.Item>
                  <Descriptions.Item label="Volcano Queue"><code className="mono">{ws.volcanoQueueName}</code></Descriptions.Item>
                  <Descriptions.Item label="maxPods">{ws.maxPods}</Descriptions.Item>
                  <Descriptions.Item label="创建时间" span={2}>{ws.createdAt}</Descriptions.Item>
                </Descriptions>
                <h4 style={{ marginTop: 24 }}>3 类池</h4>
                <Row gutter={16}>
                  {pools.map((p) => {
                    const pct = p.totalNodes > 0 ? Math.round((p.allocatedNodes / p.totalNodes) * 100) : 0;
                    const cardCount = (cards[p.id] || []).length;
                    return (
                      <Col span={8} key={p.id}>
                        <Card size="small" style={{ background: p.totalNodes > 0 ? PSBC_COLORS.primaryLight : '#F5F5F5' }}>
                          <Space>
                            <Tag color="blue">{POOL_LABELS[p.poolType]}</Tag>
                            <strong>{p.name}</strong>
                          </Space>
                          <div style={{ marginTop: 8 }}>
                            <div style={{ fontSize: 24, fontWeight: 700, color: PSBC_COLORS.primary }}>
                              {p.totalNodes} <span style={{ fontSize: 12, color: '#6B7768' }}>节点</span>
                            </div>
                            <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>
                              已分配 {p.allocatedNodes} · 可用 {p.totalNodes - p.allocatedNodes} · 异构卡 {cardCount}
                            </div>
                            <Progress percent={pct} showInfo={false} strokeColor={PSBC_COLORS.primary} size="small" style={{ marginTop: 6 }} />
                          </div>
                        </Card>
                      </Col>
                    );
                  })}
                </Row>
              </Card>
            ),
          },
          {
            key: 'projects',
            label: <span><ClusterOutlined /> 项目与配额（{projects.length}）</span>,
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                {projects.map((p) => {
                  const projectQuotas = quotas.filter((q) => q.projectId === p.id);
                  return (
                    <Card
                      key={p.id}
                      title={<Space><strong>{p.name}</strong><Tag>{p.description || '无描述'}</Tag></Space>}
                      extra={
                        <Space>
                          <Button size="small" onClick={() => openQuotaModal(p.id)} icon={<PlusOutlined />}>分配配额</Button>
                          <Button size="small" type="primary" onClick={() => openDeployModal(p.id)} icon={<RocketOutlined />}>部署</Button>
                        </Space>
                      }
                      style={{ borderRadius: 8 }}
                    >
                      <Table
                        size="small"
                        dataSource={projectQuotas}
                        rowKey="id"
                        pagination={false}
                        columns={[
                          { title: '池', dataIndex: 'poolId', render: (v) => {
                            const pool = pools.find((p) => p.id === v);
                            return pool ? `${POOL_LABELS[pool.poolType]} (${pool.name})` : v;
                          }},
                          { title: '规格', dataIndex: 'specId', render: (v) => <Tag color="cyan">{v}</Tag> },
                          { title: '总', dataIndex: 'totalNodes', width: 70 },
                          { title: '已用', dataIndex: 'usedNodes', width: 70 },
                          { title: '可用', dataIndex: 'availableNodes', width: 70, render: (v) => v > 0 ? <Tag color="green">{v}</Tag> : <Tag>0</Tag> },
                          { title: '使用率', render: (_, r) => {
                            const pct = r.totalNodes > 0 ? Math.round((r.usedNodes / r.totalNodes) * 100) : 0;
                            return <Progress percent={pct} size="small" style={{ width: 100 }} />;
                          }},
                        ]}
                      />
                    </Card>
                  );
                })}
                {projects.length === 0 && <Empty description="暂无项目，点击右侧新建" />}
              </Space>
            ),
          },
          {
            key: 'deployments',
            label: <span><RocketOutlined /> 部署（{allDeployments.length}）</span>,
            children: (
              <Card style={{ borderRadius: 8 }}>
                <Table
                  dataSource={allDeployments}
                  rowKey="id"
                  pagination={false}
                  size="small"
                  columns={[
                    { title: '名称', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
                    { title: '项目', dataIndex: 'projectId', render: (v) => projects.find((p) => p.id === v)?.name || v },
                    { title: '模型', dataIndex: 'modelName' },
                    { title: '规格', dataIndex: 'specId', render: (v) => <Tag color="cyan">{v}</Tag> },
                    { title: '副本', dataIndex: 'replicas', width: 60 },
                    { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Badge status={v === 'running' ? 'success' : v === 'failed' ? 'error' : 'processing'} text={v} /> },
                    { title: 'K8s 部署', dataIndex: 'k8sDeploymentName', render: (v) => v ? <code className="mono">{v}</code> : '-' },
                    { title: 'URL', dataIndex: 'serviceUrl', ellipsis: true, render: (v) => v ? <code className="mono" style={{ fontSize: 11 }}>{v}</code> : '-' },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'monitoring',
            label: <span><DashboardOutlined /> 监控看板</span>,
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                <Card title="本工作空间所属集群节点" style={{ borderRadius: 8 }}>
                  <Table
                    dataSource={mockMonitoring.nodes}
                    rowKey="name"
                    pagination={false}
                    size="small"
                    columns={[
                      { title: '节点', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
                      { title: 'CPU', dataIndex: 'cpuUsage', width: 130,
                        render: (v) => <Progress percent={v} size="small" status={v > 90 ? 'exception' : v > 70 ? 'active' : 'normal'} /> },
                      { title: '内存', dataIndex: 'memUsage', width: 130,
                        render: (v) => <Progress percent={v} size="small" status={v > 90 ? 'exception' : v > 70 ? 'active' : 'normal'} /> },
                      { title: 'GPU', dataIndex: 'gpuUsage', width: 130,
                        render: (v) => <Progress percent={v} size="small" strokeColor={PSBC_COLORS.primary} /> },
                      { title: '温度', dataIndex: 'gpuTemp', width: 90, render: (v) => v > 80 ? <Tag color="red">{v}°C</Tag> : v > 0 ? <Tag color="green">{v}°C</Tag> : '-' },
                      { title: '状态', dataIndex: 'status', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
                    ]}
                  />
                </Card>
              </Space>
            ),
          },
        ]}
      />

      {/* 分配配额 Modal */}
      <Modal title="分配项目配额" open={quotaOpen} onOk={handleAllocate} onCancel={() => setQuotaOpen(false)} okText="分配" width={520}>
        <Form form={quotaForm} layout="vertical">
          <Form.Item name="poolId" label="物理池" rules={[{ required: true }]}>
            <Select options={pools.map((p) => ({ value: p.id, label: `${POOL_LABELS[p.poolType]} - ${p.name} (剩余 ${p.totalNodes - p.allocatedNodes} 节点)` }))} />
          </Form.Item>
          <Form.Item name="specId" label="规格" rules={[{ required: true }]}>
            <Input placeholder="spec-shared-a100-14" />
          </Form.Item>
          <Form.Item name="totalNodes" label="总节点" rules={[{ required: true }]} initialValue={1}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 部署 Modal */}
      <Modal title="部署推理服务" open={deployOpen} onOk={handleDeploy} onCancel={() => setDeployOpen(false)} okText="部署" width={560}>
        <Form form={deployForm} layout="vertical">
          <Form.Item name="name" label="部署名称" rules={[{ required: true }]}>
            <Input placeholder="e.g. qwen3-svc" />
          </Form.Item>
          <Form.Item name="specName" label="规格名" rules={[{ required: true }]}>
            <Input placeholder="e.g. shared-hami-a100-1/4" />
          </Form.Item>
          <Form.Item name="replicas" label="副本数" rules={[{ required: true }]} initialValue={1}>
            <InputNumber min={1} max={1} disabled style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="image" label="镜像" rules={[{ required: true }]} initialValue="vllm/vllm-openai:latest">
            <Input />
          </Form.Item>
          <Form.Item name="modelSource" label="模型来源" rules={[{ required: true }]} initialValue="with_weights">
            <Select options={[{ value: 'with_weights', label: '带预训练权重' }, { value: 'without_weights', label: '无权重' }]} />
          </Form.Item>
          <Form.Item name="modelIdOrPath" label="模型路径 / 模型广场 ID" rules={[{ required: true }]} initialValue="/mnt/nfs/models">
            <Input />
          </Form.Item>
          <Form.Item name="modelName" label="模型名（可读）">
            <Input placeholder="Qwen3-14B" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}