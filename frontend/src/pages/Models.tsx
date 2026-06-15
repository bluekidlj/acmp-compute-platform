import React, { useEffect, useState, useCallback } from 'react';
import {
  Table, Button, Modal, Form, Input, Select, Space, Tag, Typography,
  message, Popconfirm, Empty, Tooltip,
} from 'antd';
import { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined, DatabaseOutlined } from '@ant-design/icons';
import { modelApi } from '../api/models';
import type { Model, ModelRequest } from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title, Text } = Typography;

const ModelsPage: React.FC = () => {
  const { isAdmin } = useAuth();
  const [models, setModels] = useState<Model[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editingModel, setEditingModel] = useState<Model | null>(null);
  const [form] = Form.useForm();
  const [editForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await modelApi.list();
      setModels(res.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await modelApi.create(values as ModelRequest);
      message.success('模型已添加');
      setCreateOpen(false);
      form.resetFields();
      load();
    } catch { /* handled */ }
    finally { setSubmitting(false); }
  };

  const handleEdit = async () => {
    if (!editingModel) return;
    const values = await editForm.validateFields();
    setSubmitting(true);
    try {
      await modelApi.update(editingModel.id, values as Partial<ModelRequest>);
      message.success('模型已更新');
      setEditOpen(false);
      setEditingModel(null);
      editForm.resetFields();
      load();
    } catch { /* handled */ }
    finally { setSubmitting(false); }
  };

  const handleDelete = async (id: string) => {
    await modelApi.delete(id);
    message.success('模型已删除');
    load();
  };

  const openEdit = (model: Model) => {
    setEditingModel(model);
    editForm.setFieldsValue({
      name: model.name,
      displayName: model.displayName,
      description: model.description,
      modelSource: model.modelSource,
      storageBackend: model.storageBackend,
      storagePath: model.storagePath,
      fileSizeMb: model.fileSizeMb,
    });
    setEditOpen(true);
  };

  const columns = [
    { title: '模型名称', dataIndex: 'name', key: 'name', width: 150,
      render: (v: string) => <Text code>{v}</Text> },
    { title: '展示名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
    { title: '来源', dataIndex: 'modelSource', key: 'modelSource', width: 130,
      render: (v: string) => (
        <Tag color={v === 'with_weights' ? 'green' : 'orange'}>
          {v === 'with_weights' ? '内置权重' : '外部挂载'}
        </Tag>
      ) },
    { title: '存储后端', dataIndex: 'storageBackend', key: 'storageBackend', width: 100,
      render: (v: string) => <Tag>{v}</Tag> },
    { title: '存储路径', dataIndex: 'storagePath', key: 'storagePath', ellipsis: true,
      render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    { title: '大小', dataIndex: 'fileSizeMb', key: 'fileSizeMb', width: 110,
      render: (v: number) => v ? `${(v / 1024 / 1024).toFixed(1)} GB` : '-' },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v: string) => new Date(v).toLocaleString() },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: unknown, record: Model) => (
        <Space>
          {isAdmin && (
            <>
              <Tooltip title="编辑">
                <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)} />
              </Tooltip>
              <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.id)}>
                <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>
          <DatabaseOutlined style={{ marginRight: 8 }} />
          模型广场
        </Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              注册模型
            </Button>
          )}
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={models}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={models.length === 0 ? { emptyText: <Empty description="暂无模型，请先注册" /> } : undefined}
      />

      {/* Create Modal */}
      <Modal
        title="注册模型"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); form.resetFields(); }}
        confirmLoading={submitting}
        okText="创建"
        width={560}
      >
        <Form form={form} layout="vertical">
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="name" label="模型标识" rules={[{ required: true }]}
              tooltip="模型文件夹名称，如 qwen3-7b">
              <Input placeholder="qwen3-7b" style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="displayName" label="展示名称">
              <Input placeholder="Qwen3-7B-Instruct" style={{ width: 200 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="modelSource" label="模型来源" initialValue="with_weights">
              <Select style={{ width: 160 }}>
                <Select.Option value="with_weights">内置权重</Select.Option>
                <Select.Option value="without_weights">外部挂载</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="fileSizeMb" label="文件大小 (MB)">
              <Input type="number" placeholder="14000000" style={{ width: 140 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="storageBackend" label="存储后端" initialValue="nfs">
              <Select style={{ width: 140 }}>
                <Select.Option value="nfs">NFS</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="storagePath" label="存储根路径" rules={[{ required: true }]}
              tooltip="存储挂载的根路径前缀，如 /mnt/nfs/models">
              <Input placeholder="/mnt/nfs/models" style={{ width: 260 }} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="模型描述" />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit Modal */}
      <Modal
        title="编辑模型"
        open={editOpen}
        onOk={handleEdit}
        onCancel={() => { setEditOpen(false); setEditingModel(null); editForm.resetFields(); }}
        confirmLoading={submitting}
        okText="保存"
        width={560}
      >
        <Form form={editForm} layout="vertical">
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="name" label="模型标识" rules={[{ required: true }]}>
              <Input disabled placeholder="qwen3-7b" style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="displayName" label="展示名称">
              <Input placeholder="Qwen3-7B-Instruct" style={{ width: 200 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="modelSource" label="模型来源">
              <Select style={{ width: 160 }}>
                <Select.Option value="with_weights">内置权重</Select.Option>
                <Select.Option value="without_weights">外部挂载</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="fileSizeMb" label="文件大小 (MB)">
              <Input type="number" placeholder="14000000" style={{ width: 140 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="storageBackend" label="存储后端">
              <Select style={{ width: 140 }}>
                <Select.Option value="nfs">NFS</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="storagePath" label="存储根路径" rules={[{ required: true }]}>
              <Input placeholder="/mnt/nfs/models" style={{ width: 260 }} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="模型描述" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ModelsPage;