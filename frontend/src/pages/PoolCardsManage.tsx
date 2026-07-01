import { useEffect, useState } from 'react';
import { Card, Table, Tag, Space, Spin, Empty } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { poolsApi, cardsApi, workspacesApi, specsApi } from '../api';
import type { PoolCard, PoolCardListResponse, ResourcePool, Workspace, ComputeSpec } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const POOL_LABELS = { EXCLUSIVE: '独占', SHARED: '共享', OVERSELL: '超分' } as const;
const BRAND_COLORS: Record<string, string> = { NVIDIA: 'green', HYGON: 'purple', HUAWEI_ASCEND: 'magenta' };

export default function PoolCardsManagePage() {
  const [loading, setLoading] = useState(true);
  const [allCards, setAllCards] = useState<(PoolCard & { poolName: string; wsName: string })[]>([]);
  const [byPool, setByPool] = useState<Record<string, PoolCardListResponse>>({});
  const [pools, setPools] = useState<ResourcePool[]>([]);
  const [specs, setSpecs] = useState<ComputeSpec[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);

  useEffect(() => {
    (async () => {
      try {
        const ws = await workspacesApi.list();
        setWorkspaces(ws);
        const allPools: ResourcePool[] = [];
        const allCards: any[] = [];
        const poolMap: Record<string, PoolCardListResponse> = {};
        for (const w of ws) {
          const ps = await poolsApi.listByWorkspace(w.id);
          allPools.push(...ps);
          for (const p of ps) {
            const cl = await cardsApi.listByPool(p.id);
            poolMap[p.id] = cl;
            cl.cards.forEach((c) => {
              allCards.push({ ...c, poolName: p.name, wsName: w.name });
            });
          }
        }
        setPools(allPools);
        setByPool(poolMap);
        setAllCards(allCards);
        setSpecs(await specsApi.list());
      } finally { setLoading(false); }
    })();
  }, []);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  const totalSlots = allCards.reduce((s, c) => s + c.slots, 0);
  const byBrand: Record<string, number> = {};
  allCards.forEach((c) => { byBrand[c.gpuBrand] = (byBrand[c.gpuBrand] || 0) + 1; });
  const bySpec: Record<string, { count: number; slots: number }> = {};
  allCards.forEach((c) => {
    if (!bySpec[c.specId]) bySpec[c.specId] = { count: 0, slots: 0 };
    bySpec[c.specId].count += 1;
    bySpec[c.specId].slots += c.slots;
  });

  return (
    <div>
      <PageHeader
        title="异构卡管理"
        subtitle="全平台异构卡总览（1 张物理卡 + 1 规格 = N 节点）"
        tags={[
          { label: `${allCards.length} 张卡`, color: 'green' },
          { label: `${totalSlots} 节点`, color: 'cyan' },
          { label: `${Object.keys(byBrand).length} 品牌`, color: 'purple' },
        ]}
      />

      <Space direction="vertical" style={{ width: '100%' }} size={16}>
        {pools.map((p) => {
          const cl = byPool[p.id];
          if (!cl || cl.cards.length === 0) return null;
          const w = workspaces.find((x) => x.id === p.workspaceId);
          return (
            <Card
              key={p.id}
              title={
                <Space>
                  <span>{w?.name || '?'} / </span>
                  <strong>{p.name}</strong>
                  <Tag>{POOL_LABELS[p.poolType]}</Tag>
                </Space>
              }
              extra={
                <Space>
                  {Object.entries(cl.bySpec).map(([specId, info]) => {
                    const spec = specs.find((s) => s.id === specId);
                    return <Tag key={specId} color="blue">{spec?.displayName || specId}: {info.cards} 卡 / {info.slots} 节点</Tag>;
                  })}
                </Space>
              }
              style={{ borderRadius: 8 }}
            >
              <Table
                dataSource={cl.cards}
                rowKey="id"
                pagination={false}
                size="small"
                columns={[
                  { title: '品牌', dataIndex: 'gpuBrand', width: 100, render: (v) => <Tag color={BRAND_COLORS[v] || 'default'}>{v}</Tag> },
                  { title: '型号', dataIndex: 'gpuModel' },
                  { title: '序列号', dataIndex: 'serialNo', width: 160, render: (v) => v ? <code className="mono">{v}</code> : '-' },
                  { title: '节点', dataIndex: 'nodeName', width: 160, render: (v) => <code className="mono">{v}</code> },
                  { title: '规格', dataIndex: 'specId', render: (v) => {
                    const s = specs.find((x) => x.id === v);
                    return s ? <Tag color="cyan">{s.displayName}</Tag> : <code className="mono">{v}</code>;
                  }},
                  { title: 'Slots', dataIndex: 'slots', width: 80, render: (v) => <strong style={{ color: PSBC_COLORS.primary }}>{v}</strong> },
                  { title: '状态', dataIndex: 'status', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
                ]}
              />
            </Card>
          );
        })}
        {allCards.length === 0 ? <Empty description="暂无异构卡" /> : null}
      </Space>
    </div>
  );
}