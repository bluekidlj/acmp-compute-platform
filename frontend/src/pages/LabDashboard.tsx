import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Row, Col, Statistic, Tag, Table, Spin, Progress } from 'antd';
import { ArrowUpOutlined, ExperimentOutlined, BulbOutlined, SafetyCertificateOutlined, FundOutlined, ApiOutlined } from '@ant-design/icons';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend, BarChart, Bar } from 'recharts';
import { generateLabKpi, generateStrategyComparison, LAB_MODULES, LAB_RECORDS, GPU_CATALOG } from '../mock/lab-data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

export default function LabDashboard() {
  const nav = useNavigate();
  const [kpi] = useState(generateLabKpi());
  const [comparison] = useState(generateStrategyComparison());

  const moduleIcons: Record<string, any> = {
    'digital-twin': <ApiOutlined />, 'strategy-lab': <ExperimentOutlined />,
    'workload': <FundOutlined />, 'governance': <SafetyCertificateOutlined />,
  };

  const totalGpus = GPU_CATALOG.reduce((s, g) => s + g.count, 0);

  return (
    <div>
      <PageHeader
        title="创新实验室"
        subtitle="异构算力策略仿真与优化控制塔"
        tags={[{ label: `${kpi.experimentCount} 实验`, color: 'blue' }, { label: `${kpi.simulationCount} 仿真`, color: 'cyan' }]}
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {[
          { title: 'GPU 利用率', value: `${kpi.gpuUtilization}%`, color: kpi.gpuUtilization > 80 ? '#FF4D4F' : PSBC_COLORS.primary, prefix: <ArrowUpOutlined /> },
          { title: '资源浪费率', value: `${kpi.resourceWaste}%`, color: kpi.resourceWaste > 15 ? '#FAAD14' : '#52C41A', suffix: '%' },
          { title: '优化收益', value: `${kpi.optimizationGain.toFixed(1)}%`, color: '#52C41A', prefix: '+' },
          { title: '成本节约', value: `¥${kpi.costSaving}K`, color: PSBC_COLORS.primary },
          { title: '实验数量', value: kpi.experimentCount, color: '#00754A' },
          { title: '仿真次数', value: kpi.simulationCount, color: '#1A8A50' },
        ].map((item, i) => (
          <Col span={4} key={i}>
            <Card style={{ borderRadius: 8, borderTop: `3px solid ${item.color}`, textAlign: 'center' }} size="small">
              <Statistic title={item.title} value={item.value} valueStyle={{ color: item.color, fontWeight: 700, fontSize: 22 }} />
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={16}>
          <Card title="策略对比（当前 vs 实验）" style={{ borderRadius: 8 }} size="small">
            <ResponsiveContainer width="100%" height={220}>
              <LineChart data={comparison.hours.map((h, i) => ({ hour: h, current: comparison.current[i], experimental: comparison.experimental[i] }))}>
                <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                <XAxis dataKey="hour" fontSize={10} tick={{ fill: '#6B7768' }} interval={3} />
                <YAxis fontSize={11} tick={{ fill: '#6B7768' }} />
                <Tooltip />
                <Legend />
                <Line type="monotone" dataKey="current" stroke="#9CA8A0" name="当前策略" strokeWidth={2} dot={false} strokeDasharray="5 5" />
                <Line type="monotone" dataKey="experimental" stroke={PSBC_COLORS.primary} name="实验策略" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="GPU 资源分布" style={{ borderRadius: 8 }} size="small">
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={GPU_CATALOG} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                <XAxis type="number" fontSize={11} />
                <YAxis dataKey="model" type="category" fontSize={10} width={80} />
                <Tooltip />
                <Bar dataKey="count" fill={PSBC_COLORS.primary} radius={[0, 4, 4, 0]} name="数量" />
              </BarChart>
            </ResponsiveContainer>
          </Card>
          <div style={{ textAlign: 'right', fontSize: 12, color: '#6B7768', marginTop: 4 }}>总计 {totalGpus} 张 GPU</div>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        {LAB_MODULES.map((mod) => (
          <Col span={6} key={mod.key}>
            <Card
              hoverable
              onClick={() => nav(`/lab/${mod.key}`)}
              style={{ borderRadius: 12, borderTop: `3px solid ${mod.color}`, cursor: 'pointer', height: '100%' }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{ fontSize: 28 }}>{mod.icon}</div>
                <div>
                  <div style={{ fontWeight: 600, fontSize: 15 }}>{mod.title}</div>
                  <div style={{ fontSize: 12, color: '#6B7768' }}>{mod.desc}</div>
                </div>
              </div>
              <div style={{ marginTop: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Tag color={mod.status === 'running' ? 'green' : 'default'}>{mod.status === 'running' ? '运行中' : '待机'}</Tag>
                <span style={{ fontSize: 11, color: '#9CA8A0' }}>{mod.updated}</span>
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Card title="最近实验记录" style={{ borderRadius: 8 }}>
        <Table dataSource={LAB_RECORDS} rowKey="id" pagination={false} size="small"
          columns={[
            { title: '实验名称', dataIndex: 'name', render: (v) => <strong>{v}</strong> },
            { title: '类型', dataIndex: 'type', width: 100, render: (v) => <Tag>{v}</Tag> },
            { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color={v === 'completed' ? 'green' : v === 'running' ? 'processing' : 'red'}>{v}</Tag> },
            { title: '收益', dataIndex: 'gain', width: 100, render: (v) => <span style={{ color: v.startsWith('+') ? '#52C41A' : v === '-' ? '#6B7768' : '#FF4D4F', fontWeight: 600 }}>{v}</span> },
            { title: '时间', dataIndex: 'time', width: 160 },
          ]}
        />
      </Card>
    </div>
  );
}
