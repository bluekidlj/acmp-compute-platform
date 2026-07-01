import { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Space, Empty, Spin, Modal, Form, Input, Select, message, Popconfirm } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { clustersApi } from '../api/clusters';
import type { PhysicalCluster } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function PhysicalClustersPage() {
  const nav = useNavigate();
  const [items, setItems] = useState<PhysicalCluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setItems(await clustersApi.list()); } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const handleRegister = async () => {
    const v = await form.validateFields();
    try {
      // mock：不读真 kubeconfig
      const fakeKubeconfig = 'fake-' + btoa(v.name).slice(0, 32);
      await clustersApi.create({ name: v.name, kubeconfigBase64: fakeKubeconfig, gpuTypes: v.gpuTypes, location: v.location });
      message.success('集群注册成功');
      setOpen(false); form.resetFields(); load();
    } catch (e: any) { message.error(e?.message || '注册失败'); }
  };

  const handleDelete = async (id: string) => {
    try { await clustersApi.remove(id); message.success('已删除'); load(); }
    catch (e: any) { message.error(e?.message || '删除失败'); }
  };

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  return (
    <div>
      <PageHeader
        title="物理集群"
        subtitle="K8s 集群注册 · 节点 / GPU / HAMi 切分扫描"
        tags={[{ label: `${items.length} 集群`, color: 'green' }]}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}
            style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
            注册集群
          </Button>
        }
      />
      {items.length === 0 ? <Empty description="暂无集群" /> : (
        <Card style={{ borderRadius: 8 }}>
          <Table
            dataSource={items}
            rowKey="id"
            pagination={false}
            size="middle"
            onRow={(r) => ({ onClick: () => nav(`/clusters/${r.id}`), style: { cursor: 'pointer' } })}
            columns={[
              { title: '名称', dataIndex: 'name', render: (v) => <strong style={{ color: PSBC_COLORS.primary }}>{v}</strong> },
              { title: '位置', dataIndex: 'location' },
              { title: '状态', dataIndex: 'status', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
              { title: 'GPU 品牌', dataIndex: 'gpuTypes', render: (v) => v ? (v as string).split(',').map((b: string) => <Tag key={b} color="green" style={{ marginRight: 4 }}>{b.trim()}</Tag>) : '-' },
              { title: 'CPU cores', dataIndex: 'maxCpuCores', width: 100, render: (v) => v ?? '-' },
              { title: '内存 (GiB)', dataIndex: 'maxMemoryGib', width: 110, render: (v) => v ?? '-' },
              { title: '描述', dataIndex: 'description', ellipsis: true },
              { title: '操作', key: 'op', width: 100, fixed: 'right',
                render: (_, r) => <Popconfirm title="确认删除？" onConfirm={(e) => { e?.stopPropagation?.(); handleDelete(r.id); }}>
                  <Button danger size="small" onClick={(e) => e.stopPropagation()}>删除</Button>
                </Popconfirm>,
              },
            ]}
          />
        </Card>
      )}

      <Modal title="注册物理集群" open={open} onOk={handleRegister} onCancel={() => setOpen(false)} okText="注册" width={520}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="e.g. sh-k8s-01" />
          </Form.Item>
          <Form.Item name="gpuTypes" label="GPU 品牌（逗号分隔）" initialValue="NVIDIA">
            <Select mode="tags" options={[
              { value: 'NVIDIA', label: 'NVIDIA' },
              { value: 'HYGON', label: '海光 DCU' },
              { value: 'HUAWEI_ASCEND', label: '华为昇腾' },
            ]} />
          </Form.Item>
          <Form.Item name="location" label="位置">
            <Input placeholder="e.g. 上海-张江" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}