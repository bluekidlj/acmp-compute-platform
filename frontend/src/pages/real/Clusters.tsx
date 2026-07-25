import { useEffect, useState } from 'react';
import { CloudUploadOutlined, ReloadOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Drawer, Form, Input, message, Popconfirm, Space, Spin, Table, Upload } from 'antd';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { PhysicalCluster } from '../../types';

export default function ClustersPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<PhysicalCluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  function load() {
    setLoading(true);
    api.clusters()
      .then(setItems)
      .catch(function handleError(exception) {
        message.error(exception.message);
      })
      .finally(function finish() {
        setLoading(false);
      });
  }

  useEffect(load, []);

  async function createCluster(values: { name: string; description?: string; kubeconfigFile: { file: File } }) {
    setSubmitting(true);
    try {
      const file = values.kubeconfigFile.file;
      const kubeconfig = await file.text();
      await api.createCluster({ name: values.name, description: values.description, kubeconfig });
      message.success('集群已注册并开始同步');
      setOpen(false);
      form.resetFields();
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '注册失败');
    } finally {
      setSubmitting(false);
    }
  }

  async function syncCluster(id: string) {
    try {
      await api.syncCluster(id);
      message.success('同步完成');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '同步失败');
    }
  }

  async function deleteCluster(id: string) {
    try {
      await api.deleteCluster(id);
      message.success('集群已删除');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败');
    }
  }

  return (
    <div>
      <div className="page-heading">
        <div>
          <h1>集群管理</h1>
          <p>连接 Kubernetes API，同步 Node 与 Gpu 资产</p>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={function showDrawer() { setOpen(true); }}>
          注册集群
        </Button>
      </div>
      <div className="surface data-table">
        {loading ? <div style={{ padding: 64, textAlign: 'center' }}><Spin /></div> : (
          <Table
            rowKey="id"
            dataSource={items}
            pagination={false}
            onRow={function rowNavigation(record) {
              return {
                onClick: function goDetail() {
                  navigate(`/clusters/${record.id}`);
                },
                style: { cursor: 'pointer' },
              };
            }}
            columns={[
              { title: '集群名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
              { title: 'Kubernetes版本', dataIndex: 'kubernetesVersion', render: function render(value) { return value || '同步后获取'; } },
              { title: '节点数', dataIndex: 'nodeCount', width: 90 },
              { title: 'GPU设备数', dataIndex: 'gpuCount', width: 110 },
              { title: '状态', dataIndex: 'status', width: 120, render: function render(value) { return <StatusBadge value={value} />; } },
              { title: '最近同步', dataIndex: 'lastSyncAt', render: function render(value) { return formatTime(value); } },
              {
                title: '操作',
                width: 250,
                render: function renderActions(_, record: PhysicalCluster) {
                  return (
                    <Space onClick={function stop(event) { event.stopPropagation(); }}>
                      <Button size="small" icon={<ReloadOutlined />} onClick={function sync() { syncCluster(record.id); }}>同步</Button>
                      <Button size="small" onClick={function detail() { navigate(`/clusters/${record.id}`); }}>详情</Button>
                      <Popconfirm title="确认删除该集群？" onConfirm={function remove() { deleteCluster(record.id); }}>
                        <Button size="small" danger>删除</Button>
                      </Popconfirm>
                    </Space>
                  );
                },
              },
            ]}
          />
        )}
      </div>

      <Drawer title="注册 Kubernetes 集群" width={520} open={open} onClose={function close() { setOpen(false); }}>
        <Form form={form} layout="vertical" onFinish={createCluster}>
          <Form.Item name="name" label="集群名称" rules={[{ required: true, message: '请输入集群名称' }]}>
            <Input placeholder="例如：内网推理集群" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item
            name="kubeconfigFile"
            label="kubeconfig"
            rules={[{ required: true, message: '请选择 kubeconfig 文件' }]}
          >
            <Upload.Dragger beforeUpload={function preventUpload() { return false; }} maxCount={1} accept=".yaml,.yml,.config">
              <CloudUploadOutlined style={{ fontSize: 28, color: '#007D4C' }} />
              <p>选择 kubeconfig 文件</p>
              <p style={{ color: '#66756f' }}>文件内容只提交到 ACMP 后端，不在浏览器持久化</p>
            </Upload.Dragger>
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} block>注册并连接</Button>
        </Form>
      </Drawer>
    </div>
  );
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}
