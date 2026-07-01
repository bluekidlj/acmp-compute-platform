import { Card, Table, Tag, Switch, Space, Button, Statistic, Row, Col } from 'antd';
import { alertRulesApi } from '../api/mock';
import PageHeader from '../components/PageHeader';

const LEVEL_COLORS: Record<string, string> = { critical: 'red', warning: 'orange', info: 'blue' };

export default function AlertRulesPage() {
  const items = alertRulesApi.list() as any;
  return (
    <div>
      <PageHeader
        title="告警规则"
        subtitle="Prometheus Alerting 规则 · 后端无（演示）"
        tags={[{ label: `${items.filter((r: any) => r.enabled).length} 启用`, color: 'green' }]}
      />
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="总规则数" value={items.length} /></Card></Col>
        <Col span={6}><Card><Statistic title="启用" value={items.filter((r: any) => r.enabled).length} valueStyle={{ color: '#52C41A' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="停用" value={items.filter((r: any) => !r.enabled).length} valueStyle={{ color: '#9CA8A0' }} /></Card></Col>
        <Col span={6}><Card><Button type="primary" disabled>新建规则</Button></Card></Col>
      </Row>
      <Card style={{ borderRadius: 8 }}>
        <Table
          dataSource={items}
          rowKey="id"
          pagination={false}
          size="middle"
          columns={[
            { title: '规则名', dataIndex: 'name' },
            { title: '指标', dataIndex: 'metric', render: (v) => <code className="mono">{v}</code> },
            { title: '条件', dataIndex: 'condition', width: 80 },
            { title: '阈值', dataIndex: 'threshold', width: 100 },
            { title: '级别', dataIndex: 'level', width: 100, render: (v) => <Tag color={LEVEL_COLORS[v]}>{v.toUpperCase()}</Tag> },
            { title: '启用', dataIndex: 'enabled', width: 80, render: (v) => <Switch checked={v} disabled /> },
            { title: '操作', key: 'op', width: 100, render: () => <Space><Button size="small">编辑</Button></Space> },
          ]}
        />
      </Card>
    </div>
  );
}