import React, { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, Select, Space, Tag, Typography, message, InputNumber } from 'antd';
import { PlusOutlined, ReloadOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { resourcePoolApi } from '../api/resourcePools';
import { physicalClusterApi } from '../api/physicalClusters';
import { specApi } from '../api/specs';
import type { ResourcePool, ResourcePoolCreateRequest, PhysicalCluster, ComputeSpec } from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title } = Typography;

const ResourcePoolsPage: React.FC = () => {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [pools, setPools] = useState<ResourcePool[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [allClusters, setAllClusters] = useState<PhysicalCluster[]>([]);
  const [allSpecs, setAllSpecs] = useState<ComputeSpec[]>([]);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await resourcePoolApi.list();
      setPools(res.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const openCreate = async () => {
    setCreateOpen(true);
    try {
      const [clusters, specs] = await Promise.all([
        physicalClusterApi.list(),
        specApi.list(),
      ]);
      setAllClusters(clusters.data);
      setAllSpecs(specs.data);
    } catch { /* ignore */ }
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    const data: ResourcePoolCreateRequest = {
      ...values,
      specQuotas: values.specQuotas || [],
    };
    await resourcePoolApi.create(data);
    message.success('资源池创建成功');
    setCreateOpen(false);
    form.resetFields();
    load();
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '部门编码', dataIndex: 'departmentCode', key: 'departmentCode', width: 110 },
    { title: '部门名称', dataIndex: 'departmentName', key: 'departmentName', width: 120 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => v === 'active' ? <Tag color="green">正常</Tag> : <Tag color="red">停用</Tag>,
    },
    {
      title: '关联物理集群', dataIndex: 'physicalClusterIds', key: 'physicalClusterIds', width: 120,
      render: (ids: string[]) => <Tag color="blue">{ids.length} 个集群</Tag>,
    },
    {
      title: '规格配额', dataIndex: 'specQuotas', key: 'specQuotas', width: 180,
      render: (quotas: ResourcePool['specQuotas']) =>
        quotas?.map((q) => (
          <Tag key={q.specId} color={q.availableQuota > 0 ? 'green' : 'red'}>
            {q.specName}: {q.allocatedQuota}/{q.totalQuota}
          </Tag>
        )),
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: unknown, record: ResourcePool) => (
        <Button
          size="small"
          type="link"
          icon={<EyeOutlined />}
          onClick={() => navigate(`/resource-pools/${record.id}`)}
        >
          详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>逻辑资源池管理</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              创建资源池
            </Button>
          )}
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={pools}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <Modal
        title="创建逻辑资源池"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); form.resetFields(); }}
        okText="创建"
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="资源池名称" rules={[{ required: true }]}>
            <Input placeholder="如 算法部资源池" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="departmentCode" label="部门编码" rules={[{ required: true }]}>
              <Input placeholder="algo" style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="departmentName" label="部门名称" rules={[{ required: true }]}>
              <Input placeholder="算法部" style={{ width: 180 }} />
            </Form.Item>
          </Space>
          <Form.Item name="physicalClusterIds" label="关联物理集群" rules={[{ required: true }]}>
            <Select mode="multiple" placeholder="选择物理集群">
              {allClusters.map((c) => (
                <Select.Option key={c.id} value={c.id}>
                  {c.name} ({c.gpuTypes})
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.List name="specQuotas">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...rest }) => (
                  <Space key={key} align="baseline">
                    <Form.Item {...rest} name={[name, 'specName']} rules={[{ required: true }]}>
                      <Select placeholder="选择规格" style={{ width: 220 }}>
                        {allSpecs.map((s) => (
                          <Select.Option key={s.id} value={s.name}>{s.displayName}</Select.Option>
                        ))}
                      </Select>
                    </Form.Item>
                    <Form.Item {...rest} name={[name, 'totalQuota']} rules={[{ required: true }]}>
                      <InputNumber min={1} placeholder="总配额" style={{ width: 100 }} />
                    </Form.Item>
                    <Button size="small" danger onClick={() => remove(name)}>移除</Button>
                  </Space>
                ))}
                <Button type="dashed" onClick={() => add()} block>
                  + 添加规格配额
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
    </div>
  );
};

export default ResourcePoolsPage;
