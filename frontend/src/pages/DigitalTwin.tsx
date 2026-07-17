import { useState, useEffect } from 'react';
import { Card, Row, Col, Tag, Button, Space, Progress, Statistic, Spin, Tooltip as AntTooltip } from 'antd';
import { PlayCircleOutlined, PlusOutlined, WarningOutlined, SwapOutlined, ReloadOutlined } from '@ant-design/icons';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area } from 'recharts';
import { generateTwinStatus, LAB_CLUSTERS } from '../mock/lab-data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const GPU_BRAND_COLORS: Record<string, string> = { NVIDIA: '#76B900', HUAWEI_ASCEND: '#CF0A2C', HYGON: '#005BAC' };

export default function DigitalTwinPage() {
  const [twin, setTwin] = useState(() => generateTwinStatus());
  const [simLog, setSimLog] = useState<string[]>([]);
  const [running, setRunning] = useState(false);
  const [historyData, setHistoryData] = useState<any[]>([]);

  useEffect(() => {
    if (!running) return;
    const iv = setInterval(() => {
      const d = generateTwinStatus();
      setTwin(d);
      setHistoryData((prev) => {
        const next = [...prev, {
          time: new Date().toLocaleTimeString(),
          bjUtil: d.beijing.gpuUtil,
          hfUtil: d.hefei.gpuUtil,
          bjTemp: d.beijing.gpuTemp,
          hfTemp: d.hefei.gpuTemp,
        }];
        return next.slice(-30);
      });
    }, 2000);
    return () => clearInterval(iv);
  }, [running]);

  const addLog = (msg: string) => setSimLog((p) => [`[${new Date().toLocaleTimeString()}] ${msg}`, ...p].slice(0, 50));

  return (
    <div>
      <PageHeader
        title="数字孪生"
        subtitle="集群镜像 · GPU 实时状态 · 仿真控制"
        extra={
          <Space>
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => { setRunning(!running); addLog(running ? '仿真暂停' : '仿真启动'); }}
              style={{ background: running ? '#FF4D4F' : PSBC_COLORS.primary, borderColor: running ? '#FF4D4F' : PSBC_COLORS.primary }}>
              {running ? '停止' : '运行仿真'}
            </Button>
            <Button icon={<PlusOutlined />} onClick={() => addLog('注入负载: 风控模型 500QPS')}>注入负载</Button>
            <Button icon={<WarningOutlined />} onClick={() => addLog('注入故障: GPU节点降级')}>故障注入</Button>
          </Space>
        }
      />

      <Row gutter={16} style={{ marginBottom: 16 }}>
        {(['beijing', 'hefei'] as const).map((clusterKey) => {
          const c = twin[clusterKey];
          const info = LAB_CLUSTERS.find((lc) => lc.id === clusterKey)!;
          return (
            <Col span={12} key={clusterKey}>
              <Card
                style={{ borderRadius: 12, borderLeft: `4px solid ${info.color}`, background: 'linear-gradient(135deg, #fafcfa 0%, #f0f7f0 100%)' }}
                title={
                  <Space>
                    <span style={{ fontWeight: 700, fontSize: 16 }}>{info.name}</span>
                    <Tag color="green">{info.onlineNodes}/{info.totalNodes} 节点在线</Tag>
                    <Tag color="blue">{info.gpus.join(' · ')}</Tag>
                  </Space>
                }
              >
                <Row gutter={16}>
                  <Col span={6}>
                    <Statistic title="GPU 利用率" value={`${c.gpuUtil}%`} valueStyle={{ color: c.gpuUtil > 80 ? '#FF4D4F' : PSBC_COLORS.primary }} />
                    <Progress percent={c.gpuUtil} size="small" strokeColor={c.gpuUtil > 80 ? '#FF4D4F' : PSBC_COLORS.primary} style={{ marginTop: 4 }} />
                  </Col>
                  <Col span={6}>
                    <Statistic title="温度" value={`${c.gpuTemp}°C`} valueStyle={{ color: c.gpuTemp > 75 ? '#FF4D4F' : '#FAAD14' }} />
                    <Progress percent={Math.round(c.gpuTemp / 100 * 100)} size="small" strokeColor={c.gpuTemp > 75 ? '#FF4D4F' : '#FAAD14'} style={{ marginTop: 4 }} />
                  </Col>
                  <Col span={6}>
                    <Statistic title="功耗" value={`${c.power}W`} />
                  </Col>
                  <Col span={6}>
                    <Statistic title="内存" value={`${c.memory}%`} />
                  </Col>
                </Row>
                <div style={{ marginTop: 12 }}>
                  {c.gpuList.map((gpu) => (
                    <div key={gpu.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 8px', background: '#fff', borderRadius: 6, marginBottom: 4, border: '1px solid #E5EBE7' }}>
                      <div style={{ width: 8, height: 8, borderRadius: 4, background: gpu.util > 70 ? '#52C41A' : gpu.util > 40 ? '#FAAD14' : '#9CA8A0' }} />
                      <Tag color="blue" style={{ fontSize: 10 }}>{gpu.model}</Tag>
                      <span style={{ flex: 1, fontSize: 12 }}>{gpu.task}</span>
                      <AntTooltip title={`显存: ${gpu.mem}% · 算力: ${gpu.util}% · 温度: ${gpu.temp}°C`}>
                        <Progress type="circle" percent={gpu.util} size={28} strokeColor={gpu.util > 70 ? '#52C41A' : PSBC_COLORS.primary} />
                      </AntTooltip>
                    </div>
                  ))}
                </div>
              </Card>
            </Col>
          );
        })}
      </Row>

      <Row gutter={16}>
        <Col span={16}>
          <Card title="集群实时指标趋势" style={{ borderRadius: 8 }} size="small">
            {historyData.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#9CA8A0' }}>点击"运行仿真"开始采集数据</div>
            ) : (
              <ResponsiveContainer width="100%" height={220}>
                <LineChart data={historyData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#E5EBE7" />
                  <XAxis dataKey="time" fontSize={10} tick={{ fill: '#6B7768' }} />
                  <YAxis fontSize={11} tick={{ fill: '#6B7768' }} />
                  <Tooltip />
                  <Legend />
                  <Line type="monotone" dataKey="bjUtil" stroke={PSBC_COLORS.primary} name="北京 GPU 利用率" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="hfUtil" stroke="#1A8A50" name="合肥 GPU 利用率" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="bjTemp" stroke="#FAAD14" name="北京温度" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="hfTemp" stroke="#FF4D4F" name="合肥温度" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </Card>
        </Col>
        <Col span={8}>
          <Card title="仿真日志" style={{ borderRadius: 8 }} size="small" bodyStyle={{ height: 250, overflow: 'auto', padding: 8 }}>
            {simLog.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#9CA8A0' }}>暂无日志</div>
            ) : (
              simLog.map((log, i) => <div key={i} style={{ fontSize: 11, padding: '2px 4px', color: log.includes('故障') ? '#FF4D4F' : '#1F2A24', borderBottom: '1px solid #F0F2F0' }}>{log}</div>)
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
