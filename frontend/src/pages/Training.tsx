import { useEffect, useState } from 'react';
import { Card, Table, Tag, Spin, Empty, Alert, Button, Space, Progress } from 'antd';
import { ExperimentOutlined } from '@ant-design/icons';
import { trainingApi } from '../api/mock';
import PageHeader from '../components/PageHeader';

const STATUS_COLORS: Record<string, string> = {
  running: 'green', pending: 'orange', completed: 'blue', failed: 'red',
};

export default function TrainingPage() {
  const [items, setItems] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    trainingApi.list().then((d) => { setItems(d); setLoading(false); });
  }, []);

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  const running = items.filter((j) => j.status === 'running').length;
  const totalGpu = items.filter((j) => j.status === 'running' || j.status === 'pending').reduce((s, j) => s + j.replicas, 0);

  return (
    <div>
      <Alert
        type="info" showIcon
        message="训练管理（演示）"
        description="后端暂未实现 VolcanoJob 真实业务，前端演示数据来自 mock。可在此查看任务列表、提交状态、占用资源。"
        style={{ marginBottom: 16 }}
      />
      <PageHeader
        title="训练管理"
        subtitle="VolcanoJob 训练任务 · 演示模式"
        tags={[
          { label: `运行 ${running}`, color: 'green' },
          { label: `占用 GPU ${totalGpu}`, color: 'cyan' },
          { label: `总计 ${items.length}`, color: 'blue' },
        ]}
        extra={
          <Space>
            <Button type="primary" icon={<ExperimentOutlined />} disabled
              style={{ background: '#00754A', borderColor: '#00754A' }}>
              提交训练任务
            </Button>
          </Space>
        }
      />
      {items.length === 0 ? <Empty description="暂无训练任务" /> : (
        <Card style={{ borderRadius: 8 }}>
          <Table
            dataSource={items} rowKey="id" pagination={false} size="middle"
            columns={[
              { title: '任务名', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
              { title: '镜像', dataIndex: 'image', ellipsis: true, render: (v) => <code className="mono" style={{ fontSize: 11 }}>{v}</code> },
              { title: '规格', dataIndex: 'spec', render: (v) => <Tag color="cyan">{v}</Tag> },
              { title: '副本数', dataIndex: 'replicas', width: 100 },
              { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color={STATUS_COLORS[v] || 'default'}>{v}</Tag> },
              { title: '创建者', dataIndex: 'createdBy', width: 130 },
              { title: '创建时间', dataIndex: 'createdAt', render: (v) => v?.slice(0, 10) },
              { title: '进度', width: 120, render: () => {
                const pct = Math.floor(Math.random() * 60) + 20;
                return <Progress percent={pct} size="small" />;
              }},
            ]}
          />
        </Card>
      )}
    </div>
  );
}