import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Tag, Spin, Empty, Button, Tabs, Table, Space, Modal, Form,
  Select, Input, message, Popconfirm, Row, Col, Statistic,
} from 'antd';
import { ArrowLeftOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { poolsApi, cardsApi, specsApi, quotasApi } from '../api';
import type { ResourcePool, PoolCard, PoolCardListResponse, ComputeSpec, ProjectQuota } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const POOL_LABELS = { EXCLUSIVE: '独占', SHARED: '共享', OVERSELL: '超分' } as const;

export default function PhysicalPoolDetailPage() {
  const { wsId, poolId } = useParams<{ wsId: string; poolId: string }>();
  const nav = useNavigate();
  const pid = poolId ?? '';
  const wid = wsId ?? '';

  const [pool, setPool] = useState<ResourcePool | null>(null);
  const [cardList, setCardList] = useState<PoolCardListResponse | null>(null);
  const [specs, setSpecs] = useState<ComputeSpec[]>([]);
  const [quotas, setQuotas] = useState<ProjectQuota[]>([]);
  const [loading, setLoading] = useState(true);
  const [addOpen, setAddOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    if (!poolId || !wsId) return;
    setLoading(true);
    try {
      const p = await poolsApi.get(pid);
      setPool(p);
      const [cl, sp] = await Promise.all([
        cardsApi.listByPool(pid),
        specsApi.list({ poolType: p.poolType as 'EXCLUSIVE' | 'SHARED' | 'OVERSELL' }),
      ]);
      setCardList(cl);
      setSpecs(sp);
      // 取关联的 prq（找 poolId 匹配的 prq）
      const allPrq: ProjectQuota[] = [];
      // 暂时展示 mock：prq-llm-1 / prq-llm-2 / prq-llm-3 关联到 ai-rd 池
      const { quotasApi } = await import('../api/quotas');
      const prqs = await quotasApi.listByProject('proj-llm');
      allPrq.push(...prqs.filter((q) => q.poolId === poolId));
      setQuotas(allPrq);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [poolId, wsId]);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  if (!pool) return <Empty description="池不存在" />;

  const utilizationPct = pool.totalNodes > 0
    ? Math.round((pool.allocatedNodes / pool.totalNodes) * 100)
    : 0;

  const handleAddCard = async () => {
    const v = await form.validateFields();
    try {
      await cardsApi.add(pid, v);
      message.success('卡已加入池');
      setAddOpen(false);
      form.resetFields();
      load();
    } catch (e: any) { message.error(e?.message || '加入失败'); }
  };

  const handleRemoveCard = async (cardId: string) => {
    try {
      await cardsApi.remove(pid, cardId, false);
      message.success('已移除');
      load();
    } catch (e: any) { message.warning(e?.message || '移除失败，可加 force=true'); }
  };

  return (
    <div>
      <PageHeader
        title={`${pool.name} (${POOL_LABELS[pool.poolType]})`}
        subtitle={`项目: ${wsId?.slice(0, 8)}... · 池 ID: ${pool.id.slice(0, 8)}...`}
        extra={
          <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/resources/pools')}>
            返回列表
          </Button>
        }
      />

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card><Statistic title="总节点（卡 slots 累加）" value={pool.totalNodes} valueStyle={{ color: PSBC_COLORS.primary }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="已分配" value={pool.allocatedNodes} valueStyle={{ color: '#FAAD14' }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="可用" value={pool.totalNodes - pool.allocatedNodes} valueStyle={{ color: '#52C41A' }} /></Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="使用率" value={utilizationPct} suffix="%"
              valueStyle={{ color: utilizationPct > 90 ? '#FF4D4F' : utilizationPct > 70 ? '#FAAD14' : PSBC_COLORS.primary }} />
          </Card>
        </Col>
      </Row>

      <Card style={{ borderRadius: 8, marginBottom: 16 }}>
        <Descriptions column={3} size="small" title="基本信息">
          <Descriptions.Item label="ID"><code className="mono">{pool.id}</code></Descriptions.Item>
          <Descriptions.Item label="状态"><Tag color="green">活跃</Tag></Descriptions.Item>
          <Descriptions.Item label="策略"><Tag>{pool.capacityStrategy}</Tag></Descriptions.Item>
          <Descriptions.Item label="描述" span={3}>{pool.description || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Tabs
        defaultActiveKey="cards"
        items={[
          {
            key: 'cards',
            label: <span><ThunderboltOutlined /> 异构卡（{cardList?.cards.length || 0}）</span>,
            children: (
              <Card
                extra={
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddOpen(true)}
                    style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
                    加卡
                  </Button>
                }
                style={{ borderRadius: 8 }}
              >
                <Space direction="vertical" style={{ width: '100%' }} size={12}>
                  {cardList?.bySpec && Object.entries(cardList.bySpec).map(([specId, info]) => {
                    const spec = specs.find((s) => s.id === specId);
                    return (
                      <div key={specId} style={{ background: PSBC_COLORS.primaryLight, padding: '8px 12px', borderRadius: 6, fontSize: 13 }}>
                        <strong>{spec?.displayName || specId}</strong>：{info.cards} 张卡 · {info.slots} 节点
                      </div>
                    );
                  })}
                </Space>
                <Table
                  style={{ marginTop: 16 }}
                  dataSource={cardList?.cards || []}
                  rowKey="id"
                  pagination={false}
                  size="small"
                  columns={[
                    { title: '品牌', dataIndex: 'gpuBrand', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
                    { title: '型号', dataIndex: 'gpuModel', ellipsis: true },
                    { title: '序列号', dataIndex: 'serialNo', width: 140, render: (v) => v ? <code className="mono">{v}</code> : '-' },
                    { title: '节点', dataIndex: 'nodeName', width: 160, render: (v) => <code className="mono">{v}</code> },
                    { title: '应用规格', dataIndex: 'specId', width: 200, render: (v) => {
                      const s = specs.find((x) => x.id === v);
                      return s ? <Tag color="cyan">{s.displayName}</Tag> : <code className="mono">{v}</code>;
                    }},
                    { title: 'Slots', dataIndex: 'slots', width: 80,
                      render: (v) => <strong style={{ color: PSBC_COLORS.primary }}>{v}</strong> },
                    { title: '操作', key: 'op', width: 80,
                      render: (_, r) => (
                        <Popconfirm title="确认移除？" onConfirm={() => handleRemoveCard(r.id)}>
                          <Button danger size="small">移除</Button>
                        </Popconfirm>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'quotas',
            label: <span>项目配额（{quotas.length}）</span>,
            children: (
              <Card style={{ borderRadius: 8 }}>
                <Table
                  dataSource={quotas}
                  rowKey="id"
                  pagination={false}
                  size="small"
                  columns={[
                    { title: '项目', render: () => <Tag color="blue">llm-team</Tag> },
                    { title: '规格', dataIndex: 'specId', render: (v) => {
                      const s = specs.find((x) => x.id === v);
                      return s ? s.displayName : v;
                    }},
                    { title: '总节点', dataIndex: 'totalNodes', width: 100 },
                    { title: '已用', dataIndex: 'usedNodes', width: 100 },
                    { title: '可用', dataIndex: 'availableNodes', width: 100,
                      render: (v) => v > 0 ? <Tag color="green">{v}</Tag> : <Tag>0</Tag> },
                  ]}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal title="加卡到池" open={addOpen} onOk={handleAddCard} onCancel={() => setAddOpen(false)} okText="加卡" width={520}>
        <Form form={form} layout="vertical">
          <Form.Item name="gpuBrand" label="品牌" rules={[{ required: true }]}>
            <Select options={[
              { value: 'NVIDIA', label: 'NVIDIA' },
              { value: 'HYGON', label: '海光 DCU' },
              { value: 'HUAWEI_ASCEND', label: '华为昇腾' },
            ]} />
          </Form.Item>
          <Form.Item name="gpuModel" label="型号" rules={[{ required: true }]}>
            <Input placeholder="e.g. NVIDIA-A100-SXM4-80GB" />
          </Form.Item>
          <Form.Item name="nodeName" label="所在节点" rules={[{ required: true }]}>
            <Input placeholder="e.g. gpu-node-01" />
          </Form.Item>
          <Form.Item name="serialNo" label="序列号（可选）">
            <Input placeholder="e.g. GPU-A100-001" />
          </Form.Item>
          <Form.Item name="specId" label="应用规格" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={specs.map((s) => ({ value: s.id, label: `${s.displayName} (${s.name})` }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}