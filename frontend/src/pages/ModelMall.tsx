import { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Space, Modal, Form, Input, InputNumber, Select, message, Popconfirm } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { modelsApi } from '../api';
import type { Model, ModelSource } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const SOURCE_LABELS: Record<ModelSource, string> = {
  with_weights: '带权重',
  without_weights: '无权重',
};

export default function ModelMallPage() {
  const [items, setItems] = useState<Model[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setItems(await modelsApi.list()); } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    const v = await form.validateFields();
    try {
      await modelsApi.create(v);
      message.success('模型已添加');
      setOpen(false); form.resetFields(); load();
    } catch (e: any) { message.error(e?.message || '添加失败'); }
  };

  const handleDelete = async (id: string) => {
    try { await modelsApi.remove(id); message.success('已删除'); load(); }
    catch (e: any) { message.error(e?.message || '删除失败'); }
  };

  return (
    <div>
      <PageHeader
        title="模型广场"
        subtitle="常见模型展示 · 部署时引用模型 ID"
        tags={[{ label: `${items.length} 模型`, color: 'cyan' }]}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}
            style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
            添加模型
          </Button>
        }
      />
      <Card style={{ borderRadius: 8 }}>
        <Table
          loading={loading}
          dataSource={items}
          rowKey="id"
          pagination={false}
          size="middle"
          columns={[
            { title: '名称', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
            { title: '显示名', dataIndex: 'displayName' },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            { title: '来源', dataIndex: 'modelSource', width: 100, render: (v) => <Tag color={v === 'with_weights' ? 'green' : 'default'}>{SOURCE_LABELS[v as ModelSource]}</Tag> },
            { title: '存储', dataIndex: 'storageBackend', width: 80 },
            { title: '路径', dataIndex: 'storagePath', render: (v) => <code className="mono" style={{ fontSize: 11 }}>{v}</code> },
            { title: '大小', dataIndex: 'fileSizeMb', width: 100, render: (v) => v ? `${(v / 1024).toFixed(1)} GB` : '-' },
            { title: '操作', key: 'op', width: 80, fixed: 'right',
              render: (_, r) => <Popconfirm title="确认删除？" onConfirm={() => handleDelete(r.id)}>
                <Button danger size="small">删除</Button>
              </Popconfirm>,
            },
          ]}
        />
      </Card>

      <Modal title="添加模型" open={open} onOk={handleCreate} onCancel={() => setOpen(false)} okText="添加" width={520}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称（唯一）" rules={[{ required: true }]}>
            <Input placeholder="e.g. qwen3-14b" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名">
            <Input placeholder="通义千问 Qwen3-14B" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="modelSource" label="模型来源" rules={[{ required: true }]} initialValue="with_weights">
            <Select options={[{ value: 'with_weights', label: '带权重' }, { value: 'without_weights', label: '无权重' }]} />
          </Form.Item>
          <Form.Item name="storageBackend" label="存储后端" initialValue="nfs">
            <Input />
          </Form.Item>
          <Form.Item name="storagePath" label="存储路径" rules={[{ required: true }]} initialValue="/mnt/nfs/models">
            <Input />
          </Form.Item>
          <Form.Item name="fileSizeMb" label="文件大小 MB">
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}