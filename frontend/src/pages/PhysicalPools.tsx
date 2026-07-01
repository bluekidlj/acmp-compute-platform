import { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Space, Empty, Spin } from 'antd';
import { useNavigate } from 'react-router-dom';
import { PlusOutlined } from '@ant-design/icons';
import { workspacesApi, poolsApi } from '../api';
import type { ResourcePool, Workspace } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const POOL_LABELS = { EXCLUSIVE: '独占', SHARED: '共享', OVERSELL: '超分' } as const;

export default function PhysicalPoolsPage() {
  const nav = useNavigate();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [poolsByWs, setPoolsByWs] = useState<Record<string, ResourcePool[]>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const ws = await workspacesApi.list();
        setWorkspaces(ws);
        const map: Record<string, ResourcePool[]> = {};
        for (const w of ws) {
          map[w.id] = await poolsApi.listByWorkspace(w.id);
        }
        setPoolsByWs(map);
      } finally { setLoading(false); }
    })();
  }, []);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  return (
    <div>
      <PageHeader
        title="物理资源池"
        subtitle="每个工作空间自动建 3 类池（EXCLUSIVE / SHARED / OVERSELL）"
        tags={[{ label: `${workspaces.length} 工作空间`, color: 'green' }, { label: `${Object.values(poolsByWs).reduce((s, p) => s + p.length, 0)} 池`, color: 'cyan' }]}
        extra={
          <Button type="primary" icon={<PlusOutlined />} disabled
            style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
            新建池（自动随 WS 创建）
          </Button>
        }
      />
      {workspaces.length === 0 ? (
        <Empty description="暂无工作空间" />
      ) : (
        workspaces.map((w) => (
          <Card
            key={w.id}
            title={<span>{w.name} <Tag color="blue" style={{ marginLeft: 8 }}>{w.pools.length} 池</Tag></span>}
            extra={<Button type="link" onClick={() => nav(`/logical/workspaces/${w.id}`)}>查看工作空间</Button>}
            style={{ borderRadius: 8, marginBottom: 16 }}
            size="small"
          >
            <Table
              dataSource={poolsByWs[w.id] || []}
              rowKey="id"
              pagination={false}
              size="small"
              columns={[
                { title: '池名', dataIndex: 'name', render: (v, r: ResourcePool) => (
                  <a onClick={() => nav(`/resources/pools/${w.id}/${r.id}`)}>{v}</a>
                ) },
                { title: '类型', dataIndex: 'poolType', width: 100, render: (v) => <Tag>{POOL_LABELS[v as keyof typeof POOL_LABELS]}</Tag> },
                { title: '总节点', dataIndex: 'totalNodes', width: 100,
                  render: (v) => v > 0 ? <strong style={{ color: PSBC_COLORS.primary }}>{v}</strong> : <span style={{ color: '#9CA8A0' }}>0</span> },
                { title: '已分配', dataIndex: 'allocatedNodes', width: 100 },
                { title: '可用', dataIndex: 'availableNodes', width: 100, render: (v) => v > 0 ? <Tag color="green">{v}</Tag> : <Tag>0</Tag> },
                { title: '说明', dataIndex: 'description', ellipsis: true },
              ]}
            />
          </Card>
        ))
      )}
    </div>
  );
}