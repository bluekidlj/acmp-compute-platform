import { useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Drawer, Form, Input, message, Popconfirm, Space, Table } from 'antd';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { Tenant } from '../../types';

export default function TenantsPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Tenant[]>([]);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Tenant | null>(null);
  const [form] = Form.useForm();

  function load() {
    api.tenants().then(setItems).catch(function fail(exception) { message.error(exception.message); });
  }
  useEffect(load, []);

  function showCreate() {
    setEditing(null);
    form.resetFields();
    setOpen(true);
  }

  function showEdit(item: Tenant) {
    setEditing(item);
    form.setFieldsValue({ name: item.name, description: item.description });
    setOpen(true);
  }

  async function submit(values: { name: string; description?: string }) {
    try {
      if (editing) {
        await api.updateTenant(editing.id, values);
      } else {
        await api.createTenant(values);
      }
      message.success(editing ? '租户已更新' : '租户已创建');
      setOpen(false);
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '保存失败');
    }
  }

  async function remove(id: string) {
    try {
      await api.deleteTenant(id);
      message.success('租户已删除');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败');
    }
  }

  return (
    <div>
      <div className="page-heading">
        <div><h1>租户</h1><p>管理业务边界及其可使用的算力规格配额</p></div>
        <Button type="primary" icon={<PlusOutlined />} onClick={showCreate}>新建租户</Button>
      </div>
      <div className="surface data-table">
        <Table
          rowKey="id"
          dataSource={items}
          pagination={false}
          onRow={function navigateRow(record) {
            return { onClick: function go() { navigate(`/tenants/${record.id}`); }, style: { cursor: 'pointer' } };
          }}
          columns={[
            { title: '租户名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
            { title: '描述', dataIndex: 'description', render: function render(value) { return value || '-'; } },
            { title: '状态', dataIndex: 'status', width: 120, render: function render(value) { return <StatusBadge value={value} />; } },
            { title: '创建者', dataIndex: 'createdBy' },
            { title: '创建时间', dataIndex: 'createdAt', render: function render(value) { return new Date(value).toLocaleString('zh-CN'); } },
            {
              title: '操作',
              width: 140,
              render: function render(_, record: Tenant) {
                return (
                  <Space onClick={function stop(event) { event.stopPropagation(); }}>
                    <Button size="small" onClick={function edit() { showEdit(record); }}>编辑</Button>
                    <Popconfirm title="只有空租户才能删除，确认继续？" onConfirm={function confirm() { remove(record.id); }}>
                      <Button size="small" danger>删除</Button>
                    </Popconfirm>
                  </Space>
                );
              },
            },
          ]}
        />
      </div>
      <Drawer title={editing ? '编辑租户' : '新建租户'} open={open} width={500} onClose={function close() { setOpen(false); }}>
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="name" label="租户名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={4} /></Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Drawer>
    </div>
  );
}
