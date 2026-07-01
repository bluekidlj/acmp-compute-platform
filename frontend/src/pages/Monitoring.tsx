import { Card, Row, Col, Progress, Table, Tag, Statistic, Space } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined, ClusterOutlined, HddOutlined } from '@ant-design/icons';
import { mockMonitoring } from '../mock/data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function MonitoringPage() {
  const m = mockMonitoring.cluster;
  const cpuPct = Math.round(m.usedCpuCores / m.totalCpuCores * 100);
  const memPct = Math.round(m.usedMemoryGib / m.totalMemoryGib * 100);
  const gpuPct = Math.round(m.usedGpuCards / m.totalGpuCards * 100);

  return (
    <div>
      <PageHeader
        title="运维监控看板"
        subtitle="集群级 + 节点级 · 实时使用率（mock 演示）"
        tags={[{ label: `${m.totalGpuCards} GPU 卡`, color: 'green' }]}
      />
      <Row gutter={16}>
        <Col span={6}><Card><Statistic title="CPU 核心" value={`${m.usedCpuCores}/${m.totalCpuCores}`} suffix="核" valueStyle={{ color: PSBC_COLORS.primary }} /></Card></Col>
        <Col span={6}><Card><Statistic title="内存" value={`${m.usedMemoryGib}/${m.totalMemoryGib}`} suffix="GiB" valueStyle={{ color: PSBC_COLORS.primary }} /></Card></Col>
        <Col span={6}><Card><Statistic title="GPU 卡" value={`${m.usedGpuCards}/${m.totalGpuCards}`} suffix="张" valueStyle={{ color: PSBC_COLORS.primary }} /></Card></Col>
        <Col span={6}><Card><Statistic title="节点数" value={mockMonitoring.nodes.length} suffix="个" valueStyle={{ color: PSBC_COLORS.primary }} /></Card></Col>
      </Row>

      <Card title="集群级使用率" style={{ borderRadius: 8, marginTop: 16 }}>
        <Row gutter={32}>
          <Col span={8}>
            <BigProgress label="CPU" pct={cpuPct} used={m.usedCpuCores} total={m.totalCpuCores} unit="核" />
          </Col>
          <Col span={8}>
            <BigProgress label="内存" pct={memPct} used={m.usedMemoryGib} total={m.totalMemoryGib} unit="GiB" />
          </Col>
          <Col span={8}>
            <BigProgress label="GPU 卡" pct={gpuPct} used={m.usedGpuCards} total={m.totalGpuCards} unit="张" />
          </Col>
        </Row>
      </Card>

      <Card title="节点级使用率" style={{ borderRadius: 8, marginTop: 16 }}>
        <Table
          dataSource={mockMonitoring.nodes}
          rowKey="name"
          pagination={false}
          size="small"
          columns={[
            { title: '节点', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
            { title: 'CPU', dataIndex: 'cpuUsage', width: 130, render: (v) => <BarRow v={v} /> },
            { title: '内存', dataIndex: 'memUsage', width: 130, render: (v) => <BarRow v={v} /> },
            { title: 'GPU 卡', dataIndex: 'gpuUsage', width: 130, render: (v) => <BarRow v={v} strokeColor={PSBC_COLORS.primary} /> },
            { title: 'GPU 显存', dataIndex: 'gpuMemUsage', width: 130, render: (v) => <BarRow v={v} strokeColor={PSBC_COLORS.primary} /> },
            { title: '温度', dataIndex: 'gpuTemp', width: 100, render: (v) => v > 80 ? <Tag color="red">{v}°C 🔥</Tag> : v > 0 ? <Tag color="green">{v}°C</Tag> : '-' },
            { title: '状态', dataIndex: 'status', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
          ]}
        />
      </Card>
    </div>
  );
}

function BarRow({ v, strokeColor }: { v: number; strokeColor?: string }) {
  return <Progress percent={v} size="small" status={v > 90 ? 'exception' : v > 70 ? 'active' : 'normal'} strokeColor={strokeColor} />;
}

function BigProgress({ label, pct, used, total, unit }: { label: string; pct: number; used: number; total: number; unit: string }) {
  const color = pct > 90 ? '#FF4D4F' : pct > 70 ? '#FAAD14' : PSBC_COLORS.primary;
  return (
    <div style={{ textAlign: 'center' }}>
      <Progress type="dashboard" percent={pct} strokeColor={color} size={140} />
      <div style={{ marginTop: 12, fontSize: 18, fontWeight: 700, color }}>{pct}%</div>
      <div style={{ fontSize: 12, color: '#6B7768' }}>{label} · {used} / {total} {unit}</div>
    </div>
  );
}