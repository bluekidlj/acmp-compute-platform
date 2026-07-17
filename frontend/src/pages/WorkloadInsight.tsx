import { useState } from 'react';
import { Card, Table, Tag, Button, Space, Row, Col, Statistic, Progress, Tooltip as AntTooltip, message, Select } from 'antd';
import { ThunderboltOutlined, ExperimentOutlined, ReloadOutlined } from '@ant-design/icons';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend, AreaChart, Area } from 'recharts';
import { WORKLOAD_TASKS } from '../mock/lab-data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const PATTERN_COLORS: Record<string, string> = { steady: '#52C41A', burst: '#FAAD14', batch: '#00754A' };

function generatePatternData(pattern: string) {
  const points = 24;
  return Array.from({ length: points }, (_, i) => {
    let v: number;
    if (pattern === 'steady') v = 60 + Math.random() * 20;
    else if (pattern === 'burst') v = (i >= 8 && i <= 18) ? 70 + Math.random() * 25 : 10 + Math.random() * 15;
    else v = (i >= 22 || i <= 6) ? 80 + Math.random() * 15 : 5 + Math.random() * 10;
    return { time: `${i}:00`, value: Math.round(v) };
  });
}

export default function WorkloadInsightPage() {
  const [learning, setLearning] = useState(false);

  const handleLearn = () => {
    setLearning(true);
    message.loading({ content: 'AI 正在学习负载模式...', key: 'learn' });
    setTimeout(() => {
      setLearning(false);
      message.success({ content: '学习完成！发现 3 个优化建议', key: 'learn' });
    }, 2000);
  };

  return (
    <div>
      <PageHeader
        title="负载感知"
        subtitle="任务级 GPU 使用模式分析与智能优化"
        tags={[{ label: `${WORKLOAD_TASKS.length} 任务`, color: 'cyan' }]}
        extra={
          <Button type="primary" icon={<ExperimentOutlined />} onClick={handleLearn} loading={learning}
            style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
            AI 学习反馈
          </Button>
        }
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {[
          { title: '运行中任务', value: WORKLOAD_TASKS.filter((t) => t.status === 'running').length, color: '#52C41A' },
          { title: 'Steady 模式', value: WORKLOAD_TASKS.filter((t) => t.pattern === 'steady').length, color: '#52C41A' },
          { title: 'Burst 模式', value: WORKLOAD_TASKS.filter((t) => t.pattern === 'burst').length, color: '#FAAD14' },
          { title: 'Batch 模式', value: WORKLOAD_TASKS.filter((t) => t.pattern === 'batch').length, color: '#00754A' },
        ].map((item, i) => (
          <Col span={6} key={i}>
            <Card style={{ borderRadius: 8, textAlign: 'center' }} size="small">
              <Statistic title={item.title} value={item.value} valueStyle={{ color: item.color, fontWeight: 700 }} />
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={16}>
          <Card title="任务负载行为曲线" style={{ borderRadius: 8 }} size="small">
            <ResponsiveContainer width="100%" height={280}>
              <LineChart>
                <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                <XAxis dataKey="time" fontSize={10} tick={{ fill: '#6B7768' }} interval={3}
                  tickFormatter={(v: string, i: number) => i === 0 ? '' : v} />
                <YAxis fontSize={11} tick={{ fill: '#6B7768' }} />
                <Tooltip />
                <Legend />
                {['steady', 'burst', 'batch'].map((pattern) => {
                  const data = generatePatternData(pattern);
                  return (
                    <Line key={pattern} data={data} type="monotone" dataKey="value"
                      stroke={PATTERN_COLORS[pattern]} name={`${pattern === 'steady' ? '稳态' : pattern === 'burst' ? '突发' : '批处理'}`}
                      strokeWidth={2} dot={false} connectNulls />
                  );
                })}
              </LineChart>
            </ResponsiveContainer>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="模式分布" style={{ borderRadius: 8 }} size="small">
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              {['steady', 'burst', 'batch'].map((pattern) => {
                const total = WORKLOAD_TASKS.length;
                const count = WORKLOAD_TASKS.filter((t) => t.pattern === pattern).length;
                const pct = Math.round((count / total) * 100);
                return (
                  <div key={pattern}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                      <span><Tag color={PATTERN_COLORS[pattern]}>{pattern === 'steady' ? '稳态' : pattern === 'burst' ? '突发' : '批处理'}</Tag></span>
                      <span style={{ fontWeight: 600 }}>{count}/{total}</span>
                    </div>
                    <Progress percent={pct} size="small" strokeColor={PATTERN_COLORS[pattern]} />
                  </div>
                );
              })}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="任务列表" style={{ borderRadius: 8 }}>
        <Table dataSource={WORKLOAD_TASKS} rowKey="id" pagination={false} size="small"
          columns={[
            { title: '任务名称', dataIndex: 'name', render: (v) => <strong>{v}</strong> },
            { title: 'GPU', dataIndex: 'gpu', render: (v) => <Tag color="blue">{v}</Tag> },
            { title: '集群', dataIndex: 'cluster', width: 80, render: (v) => <Tag>{v}</Tag> },
            { title: '模式', dataIndex: 'pattern', width: 90, render: (v) => <Tag color={PATTERN_COLORS[v]}>{v === 'steady' ? '稳态' : v === 'burst' ? '突发' : '批处理'}</Tag> },
            { title: 'QPS', dataIndex: 'qps', width: 90 },
            { title: 'GPU 利用率', dataIndex: 'gpuUtil', width: 120, render: (v, r) => <Progress percent={v} size="small" strokeColor={v > 80 ? '#FF4D4F' : v > 60 ? '#FAAD14' : '#52C41A'} /> },
            { title: '运行时段', dataIndex: 'duration', width: 120 },
            { title: '优化建议', width: 120, render: (_, r) => {
              const tips: Record<string, string> = { steady: '维持当前配置', burst: '建议弹性伸缩', batch: '可降频节能' };
              return <Tag color="cyan">{tips[r.pattern]}</Tag>;
            }},
          ]}
        />
      </Card>
    </div>
  );
}
