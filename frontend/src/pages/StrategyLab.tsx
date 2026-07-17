import { useState } from 'react';
import { Card, Tabs, Row, Col, Tag, Button, Space, Slider, Input, Select, Statistic, Progress, Table, Divider, message } from 'antd';
import { PlayCircleOutlined, SwapOutlined, ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend, BarChart, Bar, LineChart, Line } from 'recharts';
import { generateTidalData, generateSimRecommendation, GPU_CATALOG } from '../mock/lab-data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const STRATEGIES = [
  { key: 'balanced', label: '平均分配', desc: '资源均匀分布到所有任务', color: '#00754A' },
  { key: 'utilization', label: '利用率优先', desc: '优先保障高负载任务', color: '#FAAD14' },
  { key: 'cost', label: '成本优先', desc: '优先使用低成本资源', color: '#52C41A' },
  { key: 'sla', label: 'SLA 优先', desc: '保障延迟敏感型任务', color: '#FF4D4F' },
];

function TidalTab() {
  const [strategy, setStrategy] = useState('balanced');
  const [time, setTime] = useState(12);
  const data = generateTidalData(strategy);

  const currentStrategy = STRATEGIES.find((s) => s.key === strategy)!;
  const currentPoint = data.beijingInfer[time];
  const efficiency = strategy === 'balanced' ? 72 : strategy === 'utilization' ? 85 : strategy === 'cost' ? 63 : 78;

  return (
    <div>
      <Card style={{ borderRadius: 8, marginBottom: 16, background: 'linear-gradient(135deg, #f0f7f0 0%, #e6f4ed 100%)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space>
            <span style={{ fontSize: 14, fontWeight: 600 }}>时间轴</span>
            <Tag color="blue" style={{ fontSize: 13 }}>{time.toString().padStart(2, '0')}:00</Tag>
          </Space>
          <Slider min={0} max={23} value={time} onChange={setTime} style={{ width: 400 }} />
          <span style={{ fontSize: 12, color: '#6B7768' }}>拖动模拟一天负载变化</span>
        </div>
      </Card>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={16}>
          <Card title="GPU 潮汐负载曲线" style={{ borderRadius: 8 }} size="small">
            <ResponsiveContainer width="100%" height={280}>
              <AreaChart data={data.hours.map((h, i) => ({
                hour: h, bjInfer: data.beijingInfer[i], bjTrain: data.beijingTrain[i], hfInfer: data.hefeiInfer[i],
              }))}>
                <defs>
                  <linearGradient id="bjI" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor={PSBC_COLORS.primary} stopOpacity={0.3} /><stop offset="95%" stopColor={PSBC_COLORS.primary} stopOpacity={0} /></linearGradient>
                  <linearGradient id="bjT" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor="#FAAD14" stopOpacity={0.3} /><stop offset="95%" stopColor="#FAAD14" stopOpacity={0} /></linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                <XAxis dataKey="hour" fontSize={10} tick={{ fill: '#6B7768' }} interval={3} />
                <YAxis fontSize={11} tick={{ fill: '#6B7768' }} />
                <Tooltip />
                <Legend />
                <Area type="monotone" dataKey="bjInfer" stroke={PSBC_COLORS.primary} fill="url(#bjI)" name="北京 推理负载" strokeWidth={2} />
                <Area type="monotone" dataKey="bjTrain" stroke="#FAAD14" fill="url(#bjT)" name="北京 训练负载" strokeWidth={2} />
                <Area type="monotone" dataKey="hfInfer" stroke="#1A8A50" fill="none" name="合肥 推理负载" strokeWidth={2} strokeDasharray="5 5" />
              </AreaChart>
            </ResponsiveContainer>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="调度策略" style={{ borderRadius: 8 }} size="small">
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              {STRATEGIES.map((s) => (
                <div key={s.key} onClick={() => setStrategy(s.key)}
                  style={{
                    padding: '10px 12px', borderRadius: 8, cursor: 'pointer', border: `2px solid ${strategy === s.key ? s.color : '#E5EBE7'}`,
                    background: strategy === s.key ? `${s.color}10` : '#fff', transition: 'all 0.2s',
                  }}>
                  <Space>
                    <div style={{ width: 10, height: 10, borderRadius: 5, background: s.color }} />
                    <strong style={{ color: strategy === s.key ? s.color : '#1F2A24' }}>{s.label}</strong>
                    {strategy === s.key && <Tag color="green">当前</Tag>}
                  </Space>
                  <div style={{ fontSize: 11, color: '#6B7768', marginTop: 2 }}>{s.desc}</div>
                </div>
              ))}
            </Space>
          </Card>
          <Card style={{ borderRadius: 8, marginTop: 12 }} size="small">
            <Statistic title="当前调度效率" value={`${efficiency}%`} prefix={<ThunderboltOutlined />} valueStyle={{ color: PSBC_COLORS.primary }} />
            <div style={{ marginTop: 8 }}>
              <Progress percent={efficiency} size="small" strokeColor={PSBC_COLORS.primary} />
            </div>
            <div style={{ fontSize: 11, color: '#6B7768', marginTop: 4 }}>
              当前时段 {time}:00 · 推理 {currentPoint}%
            </div>
          </Card>
        </Col>
      </Row>

      <Card title="策略 KPI 对比" style={{ borderRadius: 8 }} size="small">
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={STRATEGIES.map((s) => {
            const d = generateTidalData(s.key);
            const avgUtil = Math.round(d.beijingInfer.reduce((a, b) => a + b, 0) / 24);
            const avgCost = s.key === 'cost' ? 65 : s.key === 'utilization' ? 90 : s.key === 'sla' ? 82 : 75;
            return { name: s.label, 利用率: avgUtil, 成本: avgCost, 延迟: s.key === 'sla' ? 92 : s.key === 'balanced' ? 78 : s.key === 'utilization' ? 70 : 85 };
          })}>
            <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
            <XAxis dataKey="name" fontSize={11} />
            <YAxis fontSize={11} />
            <Tooltip />
            <Legend />
            <Bar dataKey="利用率" fill={PSBC_COLORS.primary} radius={[4, 4, 0, 0]} />
            <Bar dataKey="成本" fill="#FAAD14" radius={[4, 4, 0, 0]} />
            <Bar dataKey="延迟" fill="#1A8A50" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Card>
    </div>
  );
}

function SimulationTab() {
  const [desc, setDesc] = useState('');
  const [result, setResult] = useState<any>(null);
  const [whatIfGpu, setWhatIfGpu] = useState(4);
  const [whatIfQps, setWhatIfQps] = useState(500);

  const handleSimulate = () => {
    if (!desc.trim()) { message.warning('请输入业务描述'); return; }
    setResult(generateSimRecommendation(desc));
  };

  return (
    <div>
      <Row gutter={16}>
        <Col span={10}>
          <Card title="业务描述输入" style={{ borderRadius: 8 }} size="small">
            <Input.TextArea rows={4} value={desc} onChange={(e) => setDesc(e.target.value)}
              placeholder="例如：70B模型推理，QPS 500&#10;或：风控实时分析任务，延迟要求 &lt;200ms&#10;或：GLM-4 训练任务，批量 64"
              style={{ marginBottom: 12 }} />
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleSimulate}
              style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
              智能推荐
            </Button>
          </Card>
        </Col>
        <Col span={14}>
          <Card title="AI 推荐结果" style={{ borderRadius: 8 }} size="small">
            {!result ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#9CA8A0' }}>输入业务描述后点击"智能推荐"</div>
            ) : (
              <Row gutter={[12, 12]}>
                <Col span={8}>
                  <Card size="small" style={{ background: PSBC_COLORS.primaryLight, borderColor: PSBC_COLORS.primary }}>
                    <Statistic title="推荐 GPU" value={result.recommendedGpu} valueStyle={{ color: PSBC_COLORS.primary }} />
                    <Tag color="blue" style={{ marginTop: 4 }}>{result.cluster === 'beijing' ? '北京' : '合肥'}</Tag>
                  </Card>
                </Col>
                <Col span={4}><Statistic title="延迟" value={result.estimatedLatency} /></Col>
                <Col span={4}><Statistic title="成本" value={result.estimatedCost} /></Col>
                <Col span={4}><Statistic title="成功率" value={result.successRate} valueStyle={{ color: '#52C41A' }} /></Col>
                <Col span={4}>
                  <Statistic title="推荐数量" value={`${result.gpuCount} 卡`} />
                  <Progress percent={result.confidence} size="small" strokeColor={PSBC_COLORS.primary} style={{ marginTop: 4 }} />
                  <div style={{ fontSize: 10, color: '#6B7768' }}>置信度 {result.confidence}%</div>
                </Col>
              </Row>
            )}
          </Card>
        </Col>
      </Row>

      {result && (
        <Card title="What-if 对比分析" style={{ borderRadius: 8, marginTop: 16 }} size="small">
          <Row gutter={16}>
            <Col span={6}>
              <div style={{ fontSize: 12, color: '#6B7768', marginBottom: 4 }}>GPU 数量</div>
              <Slider min={1} max={16} value={whatIfGpu} onChange={setWhatIfGpu} />
              <Tag>{whatIfGpu} 卡</Tag>
            </Col>
            <Col span={6}>
              <div style={{ fontSize: 12, color: '#6B7768', marginBottom: 4 }}>QPS</div>
              <Slider min={100} max={2000} step={100} value={whatIfQps} onChange={setWhatIfQps} />
              <Tag>{whatIfQps} QPS</Tag>
            </Col>
            <Col span={6}>
              <div style={{ fontSize: 12, color: '#6B7768', marginBottom: 4 }}>预估延迟</div>
              <div style={{ fontSize: 24, fontWeight: 700, color: whatIfQps > 1000 ? '#FAAD14' : PSBC_COLORS.primary }}>
                {Math.round(120 + whatIfQps * 0.3 - whatIfGpu * 8)}ms
              </div>
            </Col>
            <Col span={6}>
              <div style={{ fontSize: 12, color: '#6B7768', marginBottom: 4 }}>预估成本</div>
              <div style={{ fontSize: 24, fontWeight: 700, color: PSBC_COLORS.primary }}>
                ¥{(whatIfGpu * 12.5).toFixed(1)}/h
              </div>
            </Col>
          </Row>
        </Card>
      )}
    </div>
  );
}

export default function StrategyLabPage() {
  return (
    <div>
      <PageHeader
        title="策略实验室"
        subtitle="潮汐调度 · 仿真模拟 · What-if 分析"
        tags={[{ label: '4 种策略', color: 'blue' }, { label: '实时仿真', color: 'green' }]}
      />
      <Card style={{ borderRadius: 8 }}>
        <Tabs
          defaultActiveKey="tidal"
          items={[
            { key: 'tidal', label: <span><SwapOutlined /> 潮汐调度</span>, children: <TidalTab /> },
            { key: 'simulation', label: <span><ExperimentOutlined /> 仿真模拟</span>, children: <SimulationTab /> },
          ]}
        />
      </Card>
    </div>
  );
}
