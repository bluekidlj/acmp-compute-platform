import { useState } from 'react';
import { Card, Table, Tag, Button, Space, Spin, Row, Col, Statistic, Modal, Form, Input, InputNumber, Select, message } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useCluster } from '../contexts/ClusterContext';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const POOL_LABELS: Record<string, string> = { EXCLUSIVE: '独占', SHARED: '共享', OVERSELL: '超分' };
const POOL_DESCS: Record<string, string> = {
  EXCLUSIVE: '整卡独占，适用于对性能要求高的训练/推理任务',
  SHARED: 'HAMi vGPU 切分共享，适用于中小模型推理',
  OVERSELL: '超分占位，适用于非实时批量任务',
};

const mockGlobalPools = [
  { type: 'EXCLUSIVE', totalNodes: 8, allocatedNodes: 2, gpuBrand: 'NVIDIA', gpuModel: 'A100-80G' },
  { type: 'SHARED', totalNodes: 32, allocatedNodes: 10, gpuBrand: 'NVIDIA', gpuModel: 'A100-80G' },
  { type: 'OVERSELL', totalNodes: 20, allocatedNodes: 5, gpuBrand: 'NVIDIA', gpuModel: 'A100-80G' },
];

export default function PhysicalPoolsPage() {
  const { clusterName } = useCluster();
  const [pools] = useState(mockGlobalPools);
  const [editOpen, setEditOpen] = useState(false);
  const [editPool, setEditPool] = useState<typeof mockGlobalPools[0] | null>(null);
  const [form] = Form.useForm();

  const total = pools.reduce((s, p) => s + p.totalNodes, 0);
  const allocated = pools.reduce((s, p) => s + p.allocatedNodes, 0);

  const handleEdit = (pool: typeof mockGlobalPools[0]) => {
    setEditPool(pool);
    form.setFieldsValue(pool);
    setEditOpen(true);
  };

  const handleSave = async () => {
    const v = await form.validateFields();
    message.success(`${POOL_LABELS[v.type]} 池已更新`);
    setEditOpen(false);
  };

  return (
    <div>
      <PageHeader
        title="物理资源池"
        subtitle={`${clusterName} · 三类池为集群全局唯一，扫描集群显卡后累加节点数`}
        tags={[
          { label: `总计 ${total} 节点`, color: 'cyan' },
          { label: `已分配 ${allocated}`, color: 'orange' },
          { label: `可用 ${total - allocated}`, color: 'green' },
        ]}
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {pools.map((p) => {
          const pct = p.totalNodes > 0 ? Math.round((p.allocatedNodes / p.totalNodes) * 100) : 0;
          return (
            <Col span={8} key={p.type}>
              <Card
                hoverable
                onClick={() => handleEdit(p)}
                style={{ borderRadius: 8, borderTop: `3px solid ${PSBC_COLORS.primary}` }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Tag color="blue" style={{ fontSize: 14, padding: '2px 12px' }}>{POOL_LABELS[p.type]}</Tag>
                  <Button size="small" type="text" icon={<EditOutlined />} onClick={(e) => { e.stopPropagation(); handleEdit(p); }}>编辑</Button>
                </div>
                <div style={{ fontSize: 28, fontWeight: 700, color: PSBC_COLORS.primary, marginTop: 12 }}>
                  {p.totalNodes}
                  <span style={{ fontSize: 12, color: '#6B7768', fontWeight: 400 }}> 节点</span>
                </div>
                <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>
                  已分配 {p.allocatedNodes} · 可用 {p.totalNodes - p.allocatedNodes}
                </div>
                <div style={{ fontSize: 12, color: '#6B7768', marginTop: 2 }}>
                  {p.gpuBrand} · {p.gpuModel}
                </div>
                <div style={{ fontSize: 11, color: '#9CA8A0', marginTop: 8 }}>{POOL_DESCS[p.type]}</div>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Card title="池详情" style={{ borderRadius: 8 }}>
        <Table
          dataSource={pools}
          rowKey="type"
          pagination={false}
          size="middle"
          columns={[
            { title: '池类型', dataIndex: 'type', width: 120, render: (v) => <Tag color="blue">{POOL_LABELS[v]}</Tag> },
            { title: '总节点', dataIndex: 'totalNodes', width: 120, render: (v) => <strong style={{ color: PSBC_COLORS.primary }}>{v}</strong> },
            { title: '已分配', dataIndex: 'allocatedNodes', width: 100 },
            { title: '可用', width: 100, render: (_, r) => <Tag color={r.totalNodes - r.allocatedNodes > 0 ? 'green' : 'default'}>{r.totalNodes - r.allocatedNodes}</Tag> },
            { title: 'GPU 品牌', dataIndex: 'gpuBrand', width: 100, render: (v) => <Tag color="green">{v}</Tag> },
            { title: 'GPU 型号', dataIndex: 'gpuModel' },
            { title: '说明', render: (_, r) => POOL_DESCS[r.type] },
            { title: '操作', width: 80, render: (_, r) => <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button> },
          ]}
        />
      </Card>

      <Modal title={`编辑 ${editPool ? POOL_LABELS[editPool.type] : ''} 池`} open={editOpen} onOk={handleSave} onCancel={() => setEditOpen(false)} okText="保存" width={480}>
        <Form form={form} layout="vertical">
          <Form.Item name="type" label="池类型"><Input disabled /></Form.Item>
          <Form.Item name="totalNodes" label="总节点数" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="allocatedNodes" label="已分配"><InputNumber disabled style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="gpuBrand" label="GPU 品牌"><Input disabled /></Form.Item>
          <Form.Item name="gpuModel" label="GPU 型号"><Input disabled /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
