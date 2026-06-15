import React, { useEffect, useState, useCallback } from 'react';
import {
  Table, Button, Modal, Form, Input, Select, Space, Tag, Typography,
  Descriptions, Popconfirm, message, Tooltip,
} from 'antd';
import { PlusOutlined, ReloadOutlined, DashboardOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { physicalClusterApi } from '../api/physicalClusters';
import type { PhysicalCluster, PhysicalClusterCreateRequest, PhysicalClusterCapacity } from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title, Text } = Typography;
const { TextArea } = Input;

const PhysicalClustersPage: React.FC = () => {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [capacityOpen, setCapacityOpen] = useState<string | null>(null);
  const [capacity, setCapacity] = useState<PhysicalClusterCapacity | null>(null);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await physicalClusterApi.list();
      setClusters(res.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    await physicalClusterApi.create(values as PhysicalClusterCreateRequest);
    message.success('物理集群注册成功');
    setCreateOpen(false);
    form.resetFields();
    load();
  };

  const handleViewCapacity = async (id: string) => {
    setCapacityOpen(id);
    try {
      const res = await physicalClusterApi.capacity(id);
      setCapacity(res.data);
    } catch { setCapacity(null); }
  };

  const handleDelete = async (id: string) => {
    await physicalClusterApi.delete(id);
    message.success('已删除');
    load();
  };

  const statusTag = (status: string) =>
    status === 'active' ? <Tag color="green">在线</Tag> : <Tag color="red">离线</Tag>;

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '位置', dataIndex: 'location', key: 'location', width: 100 },
    {
      title: 'GPU 类型', dataIndex: 'gpuTypes', key: 'gpuTypes', width: 120,
      render: (v: string) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: 'GPU 槽位', dataIndex: 'totalGpuSlots', key: 'totalGpuSlots', width: 100,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => statusTag(v),
    },
    {
      title: '操作', key: 'actions', width: 280,
      render: (_: unknown, record: PhysicalCluster) => (
        <Space>
          <Button size="small" type="link" icon={<EyeOutlined />}
            onClick={() => navigate(`/physical-clusters/${record.id}`)}>
            详情
          </Button>
          <Tooltip title="实时容量">
            <Button size="small" icon={<DashboardOutlined />} onClick={() => handleViewCapacity(record.id)}>
              容量
            </Button>
          </Tooltip>
          {isAdmin && (
            <Popconfirm title="确定删除该物理集群？" onConfirm={() => handleDelete(record.id)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>物理集群管理</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              注册集群
            </Button>
          )}
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={clusters}
        rowKey="id"
        loading={loading}
        pagination={false}
        expandable={{
          expandedRowRender: (record: PhysicalCluster) => (
            <div>
              {record.description && <p><Text type="secondary">{record.description}</Text></p>}
              {record.nodeLabels && (
                <p>
                  <Text strong>节点标签: </Text>
                  <Text code className="mono">{record.nodeLabels}</Text>
                </p>
              )}
              {record.taints && (
                <p>
                  <Text strong>污点: </Text>
                  <Text code className="mono">{record.taints}</Text>
                </p>
              )}
            </div>
          ),
          rowExpandable: (r: PhysicalCluster) => !!(r.nodeLabels || r.taints || r.description),
        }}
      />

      {/* 注册集群弹窗 */}
      <Modal
        title="注册物理集群"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); form.resetFields(); }}
        okText="注册"
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="集群名称" rules={[{ required: true }]}>
            <Input placeholder="如 beijing-nvidia-01" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="集群描述" />
          </Form.Item>
          <Form.Item name="kubeconfigBase64" label="Kubeconfig (Base64)" rules={[{ required: true, message: '请输入 Base64 编码的 kubeconfig' }]}>
            <TextArea rows={6} placeholder="cat ~/.kube/config | base64" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="gpuTypes" label="GPU 类型" initialValue="NVIDIA">
              <Select style={{ width: 140 }}>
                <Select.Option value="NVIDIA">NVIDIA</Select.Option>
                <Select.Option value="HYGON">海光 DCU</Select.Option>
                <Select.Option value="HUAWEI_ASCEND">华为昇腾</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="location" label="位置" initialValue="default">
              <Input style={{ width: 140 }} placeholder="beijing" />
            </Form.Item>
          </Space>
          <Form.Item name="nodeLabels" label="节点标签 (JSON)">
            <Input placeholder='{"pool":"nvidia-gpu"}' />
          </Form.Item>
          <Form.Item name="taints" label="污点容忍 (JSON 数组)">
            <Input placeholder='[{"key":"nvidia.com/gpu","value":"present","effect":"NoSchedule"}]' />
          </Form.Item>
        </Form>
      </Modal>

      {/* 容量查看弹窗 */}
      <Modal
        title="集群实时容量"
        open={!!capacityOpen}
        onCancel={() => { setCapacityOpen(null); setCapacity(null); }}
        footer={null}
        width={400}
      >
        {capacity ? (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="GPU 槽位">{capacity.gpuSlots}</Descriptions.Item>
            <Descriptions.Item label="CPU">{capacity.cpu}</Descriptions.Item>
            <Descriptions.Item label="内存">{capacity.memory} bytes</Descriptions.Item>
          </Descriptions>
        ) : (
          <Text type="secondary">正在加载...</Text>
        )}
      </Modal>
    </div>
  );
};

export default PhysicalClustersPage;
