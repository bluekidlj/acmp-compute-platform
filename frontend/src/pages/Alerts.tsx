import { Card, Table, Tag, Space, Statistic, Row, Col, Button, Switch } from 'antd';
import { alertsApi } from '../api/mock';
import PageHeader from '../components/PageHeader';

const LEVEL_COLORS: Record<string, string> = { critical: 'red', warning: 'orange', info: 'blue' };
const STATUS_COLORS: Record<string, string> = { firing: 'red', resolved: 'default' };

export default function AlertsPage() {
  const items = alertsApi.list() as any;
  return (
    <div>
      <PageHeader
        title="告警列表"
        subtitle="实时告警 · 后端无（演示数据）"
        tags={[
          { label: `触发中 ${items.filter((a: any) => a.status === 'firing').length}`, color: 'red' },
          { label: `已恢复 ${items.filter((a: any) => a.status === 'resolved').length}`, color: 'default' },
        ]}
      />
      <Card style={{ borderRadius: 8 }}>
        <Table
          dataSource={items}
          rowKey="id"
          pagination={false}
          size="middle"
          columns={[
            { title: '级别', dataIndex: 'level', width: 100, render: (v) => <Tag color={LEVEL_COLORS[v]}>{v.toUpperCase()}</Tag> },
            { title: '来源', dataIndex: 'source', width: 180, render: (v) => <code className="mono">{v}</code> },
            { title: '消息', dataIndex: 'message' },
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color={STATUS_COLORS[v]}>{v}</Tag> },
            { title: '触发时间', dataIndex: 'firedAt', width: 200, render: (v) => new Date(v).toLocaleString() },
            { title: '操作', key: 'op', width: 100, render: () => <Button size="small">确认</Button> },
          ]}
        />
      </Card>
    </div>
  );
}