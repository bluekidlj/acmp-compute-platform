import { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Space, Empty, Spin, Modal, Form, Input, InputNumber, message, Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workspacesApi, clustersApi, projectsApi } from '../api';
import type { Workspace, PhysicalCluster, Project } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function WorkspacesPage() {
  const nav = useNavigate();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [projectCounts, setProjectCounts] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const [ws, cs] = await Promise.all([workspacesApi.list(), clustersApi.list()]);
      setWorkspaces(ws);
      setClusters(cs);
      const counts: Record<string, number> = {};
      for (const w of ws) {
        const ps = await projectsApi.listByWorkspace(w.id);
        counts[w.id] = ps.length;
      }
      setProjectCounts(counts);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    const v = await form.validateFields();
    try {
      const w = await workspacesApi.create({
        name: v.name, description: v.description, clusterId: v.clusterId, maxPods: v.maxPods,
        memberIds: v.memberIds || [],
      });
      message.success('工作空间创建成功');
      setOpen(false);
      form.resetFields();
      load();
      nav(`/logical/workspaces/${w.id}`);
    } catch (e: any) { message.error(e?.message || '创建失败'); }
  };

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  return (
    <div>
      <PageHeader
        title="工作空间"
        subtitle="租户级 · 每个 WS 自动建 3 类池 · 含项目与配额"
        tags={[{ label: `${workspaces.length} WS`, color: 'green' }]}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}
            style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
            新建工作空间
          </Button>
        }
      />

      {workspaces.length === 0 ? (
        <Empty description="暂无工作空间" />
      ) : (
        <Card style={{ borderRadius: 8 }}>
          <Table
            dataSource={workspaces}
            rowKey="id"
            pagination={false}
            columns={[
              { title: '名称', dataIndex: 'name', render: (v, r) => (
                <a onClick={() => nav(`/logical/workspaces/${r.id}`)} style={{ fontWeight: 500 }}>{v}</a>
              )},
              { title: 'Namespace', dataIndex: 'namespace', render: (v) => <code className="mono">{v}</code> },
              { title: '所属集群', dataIndex: 'primaryClusterName' },
              { title: '项目数', width: 100, render: (_, r) => <Tag color="blue">{projectCounts[r.id] ?? 0}</Tag> },
              { title: '成员', dataIndex: 'memberIds', render: (v: string[]) => v.length },
              { title: '3 类池', render: (_, r) => (
                <Space>
                  {r.pools.map((p) => (
                    <Tag key={p.id} color={p.totalNodes > 0 ? 'cyan' : 'default'}>
                      {p.poolType.slice(0, 4)}: {p.totalNodes}
                    </Tag>
                  ))}
                </Space>
              )},
              { title: '状态', dataIndex: 'status', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
            ]}
          />
        </Card>
      )}

      <Modal title="新建工作空间" open={open} onOk={handleCreate} onCancel={() => setOpen(false)} okText="创建" width={560}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称（唯一）" rules={[{ required: true, pattern: /^[a-z0-9-]+$/, message: '小写字母/数字/中划线' }]}>
            <Input placeholder="e.g. cv-team" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="clusterId" label="物理集群" rules={[{ required: true }]}>
            <Select options={clusters.map((c) => ({ value: c.id, label: `${c.name} (${c.location || '?'})` }))} />
          </Form.Item>
          <Form.Item name="maxPods" label="最大 Pod 数" initialValue={50}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="memberIds" label="初始成员">
            <Select mode="tags" placeholder="输入用户 ID（可多个）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}