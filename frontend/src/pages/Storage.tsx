import { useState, useEffect } from 'react';
import { Card, Table, Tag, Space, Statistic, Row, Col, Progress, Spin } from 'antd';
import { storageApi } from '../api/mock';
import PageHeader from '../components/PageHeader';

const STATUS_COLORS: Record<string, string> = { active: 'green', bound: 'cyan', released: 'default' };

interface StorageItem { id: string; name: string; backend: string; server: string; path: string; totalGib: number; usedGib: number; status: string; }
export default function StoragePage() {
  const [items, setItems] = useState<StorageItem[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => { storageApi.list().then((d) => { setItems(d as any); setLoading(false); }); }, []);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  return (
    <div>
      <PageHeader
        title="存储资源"
        subtitle="NFS 卷管理"
        tags={[
          { label: `${items.length} 卷`, color: 'blue' },
          { label: `${(items.reduce((s: number, v: any) => s + v.usedGib, 0) / 1024).toFixed(1)} TB 已用`, color: 'orange' },
        ]}
      />
      <Row gutter={16} style={{ marginBottom: 16 }}>
        {items.map((v: any) => {
          const pct = Math.round(v.usedGib / v.totalGib * 100);
          return (
            <Col span={8} key={v.id}>
              <Card>
                <Statistic title={v.name} value={`${v.usedGib}/${v.totalGib}`} suffix="GiB" valueStyle={{ color: pct > 80 ? '#FF4D4F' : pct > 60 ? '#FAAD14' : '#52C41A' }} />
                <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>{v.backend} · {v.server}:{v.path}</div>
                <Progress percent={pct} status={pct > 80 ? 'exception' : pct > 60 ? 'active' : 'normal'} size="small" style={{ marginTop: 8 }} />
              </Card>
            </Col>
          );
        })}
      </Row>
      <Card style={{ borderRadius: 8 }}>
        <Table
          dataSource={items}
          rowKey="id"
          pagination={false}
          size="small"
          columns={[
            { title: '卷名', dataIndex: 'name' },
            { title: '后端', dataIndex: 'backend' },
            { title: '服务端', dataIndex: 'server' },
            { title: '挂载路径', dataIndex: 'path', render: (v) => <code className="mono">{v}</code> },
            { title: '已用 / 总', render: (_, r) => `${r.usedGib} / ${r.totalGib} GiB` },
            { title: '使用率', render: (_, r) => {
              const pct = Math.round(r.usedGib / r.totalGib * 100);
              return <Progress percent={pct} size="small" style={{ width: 120 }} status={pct > 80 ? 'exception' : 'normal'} />;
            }},
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color={STATUS_COLORS[v]}>{v}</Tag> },
          ]}
        />
      </Card>
    </div>
  );
}