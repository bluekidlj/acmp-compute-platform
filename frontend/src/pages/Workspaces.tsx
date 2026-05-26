import React, { useEffect, useState, useCallback } from 'react';
import {
  Table, Button, Modal, Form, Input, Select, Space, Tag, Typography, message, InputNumber,
} from 'antd';
import { PlusOutlined, ReloadOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workspaceApi } from '../api/workspaces';
import { resourcePoolApi } from '../api/resourcePools';
import { specApi } from '../api/specs';
import type { Workspace, WorkspaceCreateRequest, ResourcePool, ComputeSpec } from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title } = Typography;

const WorkspacesPage: React.FC = () => {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [allPools, setAllPools] = useState<ResourcePool[]>([]);
  const [allSpecs, setAllSpecs] = useState<ComputeSpec[]>([]);
  const [selectedPoolSpecs, setSelectedPoolSpecs] = useState<ComputeSpec[]>([]);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await workspaceApi.list();
      setWorkspaces(res.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const openCreate = async () => {
    setCreateOpen(true);
    try {
      const [pools, specs] = await Promise.all([
        resourcePoolApi.list(),
        specApi.list(),
      ]);
      setAllPools(pools.data);
      setAllSpecs(specs.data);
    } catch { /* ignore */ }
  };

  const handlePoolChange = async (poolId: string) => {
    if (!poolId) return;
    try {
      const res = await resourcePoolApi.get(poolId);
      const poolSpecNames = res.data.specQuotas?.map((q) => q.specName) || [];
      const matched = allSpecs.filter((s) => poolSpecNames.includes(s.name));
      setSelectedPoolSpecs(matched);
    } catch { setSelectedPoolSpecs([]); }
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    const data: WorkspaceCreateRequest = {
      ...values,
      specQuotas: values.specQuotas || [],
    };
    await workspaceApi.create(data);
    message.success('工作空间创建成功');
    setCreateOpen(false);
    form.resetFields();
    setSelectedPoolSpecs([]);
    load();
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '所属资源池', dataIndex: 'resourcePoolName', key: 'resourcePoolName', width: 140 },
    {
      title: 'Namespace', dataIndex: 'namespace', key: 'namespace', width: 220, ellipsis: true,
      render: (v: string) => <Text code className="mono">{v}</Text>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => v === 'active' ? <Tag color="green">活跃</Tag> : <Tag color="red">停用</Tag>,
    },
    {
      title: '规格配额', dataIndex: 'specQuotas', key: 'specQuotas', width: 200,
      render: (quotas: Workspace['specQuotas']) =>
        quotas?.map((q) => (
          <Tag key={q.specId} color={q.availableQuota > 0 ? 'green' : 'red'}>
            {q.specName}: {q.usedQuota}/{q.maxQuota}
          </Tag>
        )),
    },
    {
      title: '最大 Pod', dataIndex: 'maxPods', key: 'maxPods', width: 90,
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: unknown, record: Workspace) => (
        <Button
          size="small"
          type="link"
          icon={<EyeOutlined />}
          onClick={() => navigate(`/workspaces/${record.id}`)}
        >
          详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>工作空间管理</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              创建工作空间
            </Button>
          )}
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={workspaces}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <Modal
        title="创建工作空间"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); form.resetFields(); setSelectedPoolSpecs([]); }}
        okText="创建"
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="工作空间名称" rules={[{ required: true }]}>
            <Input placeholder="如 llm-training" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="resourcePoolId" label="所属资源池" rules={[{ required: true }]}>
            <Select placeholder="选择资源池" onChange={handlePoolChange}>
              {allPools.map((p) => (
                <Select.Option key={p.id} value={p.id}>{p.name}</Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="maxPods" label="最大 Pod 数" initialValue={50}>
            <InputNumber min={1} max={500} style={{ width: '100%' }} />
          </Form.Item>
          <Form.List name="specQuotas">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...rest }) => (
                  <Space key={key} align="baseline">
                    <Form.Item {...rest} name={[name, 'specName']} rules={[{ required: true }]}>
                      <Select placeholder="选择规格" style={{ width: 220 }}>
                        {selectedPoolSpecs.map((s) => (
                          <Select.Option key={s.id} value={s.name}>{s.displayName}</Select.Option>
                        ))}
                      </Select>
                    </Form.Item>
                    <Form.Item {...rest} name={[name, 'maxQuota']} rules={[{ required: true }]}>
                      <InputNumber min={1} placeholder="最大配额" style={{ width: 100 }} />
                    </Form.Item>
                    <Button size="small" danger onClick={() => remove(name)}>移除</Button>
                  </Space>
                ))}
                <Button type="dashed" onClick={() => add()} block disabled={selectedPoolSpecs.length === 0}>
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

export default WorkspacesPage;
