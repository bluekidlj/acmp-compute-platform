import React, { useEffect, useState, useCallback } from 'react';
import {
  Table, Button, Modal, Form, Input, Select, Space, Tag, Typography, message, InputNumber,
  Card, Popconfirm, Divider, Empty, Descriptions,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, SettingOutlined, SyncOutlined,
  PartitionOutlined,
} from '@ant-design/icons';
import { hamiGpuConfigApi } from '../api/hamiGpuConfigs';
import { physicalClusterApi } from '../api/physicalClusters';
import type {
  HamiGpuConfig, HamiGpuConfigCreateRequest, HamiVgpuUnit, HamiVgpuUnitCreateRequest,
  PhysicalCluster,
} from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title, Text } = Typography;

const HamiGpuConfigsPage: React.FC = () => {
  const { isAdmin } = useAuth();
  const [configs, setConfigs] = useState<HamiGpuConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);

  // create config modal
  const [createOpen, setCreateOpen] = useState(false);
  const [configForm] = Form.useForm();

  // detail / vgpu units modal
  const [detailOpen, setDetailOpen] = useState(false);
  const [selectedConfig, setSelectedConfig] = useState<HamiGpuConfig | null>(null);
  const [vgpuUnits, setVgpuUnits] = useState<HamiVgpuUnit[]>([]);
  const [unitsLoading, setUnitsLoading] = useState(false);

  // add vgpu unit modal
  const [addUnitOpen, setAddUnitOpen] = useState(false);
  const [unitForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [configRes, clusterRes] = await Promise.all([
        hamiGpuConfigApi.list(),
        physicalClusterApi.list(),
      ]);
      setConfigs(configRes.data);
      setClusters(clusterRes.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    const values = await configForm.validateFields();
    await hamiGpuConfigApi.create(values as HamiGpuConfigCreateRequest);
    message.success('GPU 配置创建成功');
    setCreateOpen(false);
    configForm.resetFields();
    load();
  };

  const handleDelete = async (id: string) => {
    await hamiGpuConfigApi.delete(id);
    message.success('配置已删除');
    load();
  };

  const openDetail = async (config: HamiGpuConfig) => {
    setSelectedConfig(config);
    setDetailOpen(true);
    setUnitsLoading(true);
    try {
      const res = await hamiGpuConfigApi.listVgpuUnits(config.id);
      setVgpuUnits(res.data);
    } catch { setVgpuUnits([]); }
    finally { setUnitsLoading(false); }
  };

  const handleAddUnit = async () => {
    if (!selectedConfig) return;
    const values = await unitForm.validateFields();
    await hamiGpuConfigApi.addVgpuUnit(selectedConfig.id, values as HamiVgpuUnitCreateRequest);
    message.success('vGPU 单元已添加');
    setAddUnitOpen(false);
    unitForm.resetFields();
    const res = await hamiGpuConfigApi.listVgpuUnits(selectedConfig.id);
    setVgpuUnits(res.data);
  };

  const handleDeleteUnit = async (unitId: string) => {
    if (!selectedConfig) return;
    await hamiGpuConfigApi.deleteVgpuUnit(selectedConfig.id, unitId);
    message.success('vGPU 单元已删除');
    setVgpuUnits(vgpuUnits.filter((u) => u.id !== unitId));
  };

  const handleSync = async (unitId: string) => {
    if (!selectedConfig) return;
    await hamiGpuConfigApi.sync(selectedConfig.id, selectedConfig.physicalClusterId, unitId);
    message.success('同步完成');
    const res = await hamiGpuConfigApi.listVgpuUnits(selectedConfig.id);
    setVgpuUnits(res.data);
  };

  const getClusterName = (clusterId: string) =>
    clusters.find((c) => c.id === clusterId)?.name || clusterId;

  const configColumns = [
    { title: '配置名称', dataIndex: 'nodeSelectorPrefix', key: 'name', ellipsis: true },
    {
      title: '关联集群', dataIndex: 'physicalClusterId', key: 'cluster', width: 160,
      render: (v: string) => <Text code>{getClusterName(v)}</Text>,
    },
    { title: 'GPU 类型', dataIndex: 'gpuType', key: 'gpuType', width: 90 },
    {
      title: 'GPU 显存', dataIndex: 'gpuMemMb', key: 'gpuMemMb', width: 100,
      render: (v: number) => `${(v / 1024).toFixed(0)}GB`,
    },
    { title: '算力', dataIndex: 'gpuCores', key: 'gpuCores', width: 80 },
    {
      title: '切分数', dataIndex: 'totalVgpuCount', key: 'totalVgpuCount', width: 80,
      render: (v: number) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 160,
      render: (_: unknown, record: HamiGpuConfig) => (
        <Space>
          <Button size="small" type="link" onClick={() => openDetail(record)}>
            管理单元
          </Button>
          {isAdmin && (
            <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.id)}>
              <Button size="small" danger type="link">删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const unitColumns = [
    { title: '索引', dataIndex: 'vgpuIndex', width: 60 },
    { title: '名称', dataIndex: 'vgpuName', render: (v: string) => <Text code>{v}</Text> },
    {
      title: '显存', dataIndex: 'vgpuMemMb', width: 90,
      render: (v: number) => `${(v / 1024).toFixed(0)}GB`,
    },
    { title: '算力', dataIndex: 'vgpuCores', width: 80 },
    {
      title: '可用数', dataIndex: 'availableCount', width: 80,
      render: (v: number) => <Tag color={v > 0 ? 'green' : 'red'}>{v}</Tag>,
    },
    { title: 'NodeSelector', dataIndex: 'nodeSelectorValue', ellipsis: true },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_: unknown, record: HamiVgpuUnit) => (
        <Space>
          <Button size="small" icon={<SyncOutlined />} onClick={() => handleSync(record.id)}>
            同步
          </Button>
          {isAdmin && (
            <Popconfirm title="确定删除？" onConfirm={() => handleDeleteUnit(record.id)}>
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
        <Title level={4} style={{ margin: 0 }}>
          <PartitionOutlined style={{ marginRight: 8 }} />
          HAMi GPU 切分配置
        </Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              创建配置
            </Button>
          )}
        </Space>
      </div>

      <Table
        columns={configColumns}
        dataSource={configs}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      {/* Create Config Modal */}
      <Modal
        title="创建 HAMi GPU 配置"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); configForm.resetFields(); }}
        okText="创建"
        width={600}
      >
        <Form form={configForm} layout="vertical">
          <Form.Item name="physicalClusterId" label="物理集群" rules={[{ required: true }]}>
            <Select placeholder="选择物理集群">
              {clusters.map((c) => (
                <Select.Option key={c.id} value={c.id}>{c.name} ({c.gpuTypes})</Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="gpuType" label="GPU 类型" rules={[{ required: true }]}>
            <Input placeholder="NVIDIA" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="gpuMemMb" label="GPU 显存 (MB)" rules={[{ required: true }]}>
              <InputNumber min={1024} style={{ width: 160 }} placeholder="24576" />
            </Form.Item>
            <Form.Item name="gpuCores" label="GPU 算力" rules={[{ required: true }]}>
              <InputNumber min={1} max={100} style={{ width: 120 }} placeholder="100" />
            </Form.Item>
            <Form.Item name="totalVgpuCount" label="切分数量" rules={[{ required: true }]}>
              <InputNumber min={1} max={16} style={{ width: 100 }} placeholder="4" />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="nodeSelectorKey" label="节点选择键" rules={[{ required: true }]}>
              <Input placeholder="nvidia.com/gpu.product" style={{ width: 220 }} />
            </Form.Item>
            <Form.Item name="nodeSelectorPrefix" label="前缀" rules={[{ required: true }]}>
              <Input placeholder="NVIDIA-RTX-4090" style={{ width: 200 }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      {/* Detail / vGPU Units Modal */}
      <Modal
        title={selectedConfig ? `vGPU 单元管理 - ${selectedConfig.nodeSelectorPrefix}` : ''}
        open={detailOpen}
        onCancel={() => { setDetailOpen(false); setSelectedConfig(null); }}
        footer={null}
        width={800}
      >
        {selectedConfig && (
          <>
            <Descriptions size="small" column={3} bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item label="GPU 类型">{selectedConfig.gpuType}</Descriptions.Item>
              <Descriptions.Item label="总显存">{(selectedConfig.gpuMemMb / 1024).toFixed(0)}GB</Descriptions.Item>
              <Descriptions.Item label="算力">{selectedConfig.gpuCores}</Descriptions.Item>
              <Descriptions.Item label="切分数量">{selectedConfig.totalVgpuCount}</Descriptions.Item>
              <Descriptions.Item label="节点选择键">{selectedConfig.nodeSelectorKey}</Descriptions.Item>
              <Descriptions.Item label="前缀">{selectedConfig.nodeSelectorPrefix}</Descriptions.Item>
            </Descriptions>

            <Divider />

            <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between' }}>
              <Title level={5} style={{ margin: 0 }}>vGPU 单元列表</Title>
              {isAdmin && (
                <Button type="primary" size="small" icon={<PlusOutlined />}
                  onClick={() => { setAddUnitOpen(true); unitForm.resetFields(); }}>
                  添加单元
                </Button>
              )}
            </div>

            <Table
              columns={unitColumns}
              dataSource={vgpuUnits}
              rowKey="id"
              loading={unitsLoading}
              pagination={false}
              size="small"
            />
          </>
        )}
      </Modal>

      {/* Add vGPU Unit Modal */}
      <Modal
        title="添加 vGPU 单元"
        open={addUnitOpen}
        onOk={handleAddUnit}
        onCancel={() => { setAddUnitOpen(false); unitForm.resetFields(); }}
        okText="添加"
        width={560}
      >
        <Form form={unitForm} layout="vertical">
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="vgpuIndex" label="索引" rules={[{ required: true }]}>
              <InputNumber min={0} style={{ width: 80 }} />
            </Form.Item>
            <Form.Item name="vgpuName" label="名称" rules={[{ required: true }]}>
              <Input placeholder="rtx4090-6g" style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="vgpuMemMb" label="显存 (MB)" rules={[{ required: true }]}>
              <InputNumber min={512} style={{ width: 130 }} placeholder="6144" />
            </Form.Item>
            <Form.Item name="vgpuCores" label="算力" rules={[{ required: true }]}>
              <InputNumber min={1} max={100} style={{ width: 100 }} placeholder="25" />
            </Form.Item>
            <Form.Item name="availableCount" label="可用数">
              <InputNumber min={0} style={{ width: 80 }} placeholder="4" />
            </Form.Item>
          </Space>
          <Form.Item name="nodeSelectorValue" label="节点选择值" rules={[{ required: true }]}>
            <Input placeholder="NVIDIA-RTX-4090" />
          </Form.Item>
          <Form.Item name="tolerations" label="容忍配置 (JSON)">
            <Input.TextArea rows={2} placeholder='[{"key":"nvidia.com/gpu","operator":"Exists"}]' />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default HamiGpuConfigsPage;
