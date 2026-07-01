import { Card, Table, Tag, Space, Row, Col, Statistic, Progress, Button, Alert } from 'antd';
import { RocketOutlined, ReloadOutlined } from '@ant-design/icons';
import { mockDeployments, mockMonitoring } from '../mock/data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function WorkloadsPage() {
  const dep = mockDeployments;
  const running = dep.filter((d) => d.status === 'running').length;
  const totalPods = dep.length;
  const mon = mockMonitoring.cluster;

  return (
    <div>
      <PageHeader
        title="负载管理"
        subtitle="工作负载 · Pod / Deployment 列表面板（演示数据）"
        tags={[
          { label: `运行 ${running}/${totalPods}`, color: 'green' },
          { label: `节点 ${mockMonitoring.nodes.length}`, color: 'cyan' },
        ]}
        extra={<Button type="primary" icon={<ReloadOutlined />} disabled>刷新</Button>}
      />
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="运行中 Pod" value={running} valueStyle={{ color: '#52C41A' }} prefix={<RocketOutlined />} /></Card></Col>
        <Col span={6}><Card><Statistic title="总 Pod" value={totalPods} /></Card></Col>
        <Col span={6}><Card><Statistic title="CPU 使用率" value={Math.round(mon.usedCpuCores / mon.totalCpuCores * 100)} suffix="%" /></Card></Col>
        <Col span={6}><Card><Statistic title="GPU 使用率" value={Math.round(mon.usedGpuCards / mon.totalGpuCards * 100)} suffix="%" /></Card></Col>
      </Row>

      <Alert type="info" showIcon message="演示数据" description="工作负载列表来自 mock，最终后端为 /api/v1/projects/{id}/deployments" style={{ marginBottom: 16 }} />

      <Card style={{ borderRadius: 8 }}>
        <Table
          dataSource={dep}
          rowKey="id"
          pagination={false}
          size="small"
          columns={[
            { title: 'Pod / Deployment', dataIndex: 'k8sDeploymentName', render: (v) => v ? <code className="mono">{v}</code> : '-' },
            { title: 'Service', dataIndex: 'k8sServiceName', render: (v) => v ? <code className="mono">{v}</code> : '-' },
            { title: '名称', dataIndex: 'name' },
            { title: '模型', dataIndex: 'modelName' },
            { title: '副本', dataIndex: 'replicas', width: 60 },
            { title: 'Ready', dataIndex: 'readyReplicas', width: 70, render: (v) => v ?? '-' },
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color={v === 'running' ? 'green' : v === 'failed' ? 'red' : 'orange'}>{v}</Tag> },
            { title: 'URL', dataIndex: 'serviceUrl', ellipsis: true, render: (v) => v ? <code className="mono" style={{ fontSize: 11 }}>{v}</code> : '-' },
            { title: '启动时间', dataIndex: 'createdAt', render: (v) => v?.slice(0, 10) },
          ]}
        />
      </Card>
    </div>
  );
}