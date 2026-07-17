import { useState } from 'react';
import { Card, Row, Col, Tag, Table, Switch, Button, Space, Divider, Statistic, Modal, Descriptions, message, Tooltip } from 'antd';
import { SafetyCertificateOutlined, CheckCircleOutlined, CloseCircleOutlined, ExperimentOutlined, LockOutlined } from '@ant-design/icons';
import { DATA_CLASSIFICATIONS, GOVERNANCE_BINDINGS } from '../mock/lab-data';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const LEVEL_COLORS: Record<string, string> = {
  Public: '#52C41A', Internal: '#00754A', Sensitive: '#FAAD14', Regulatory: '#FF4D4F',
};

export default function DataGovernancePage() {
  const [crossPool, setCrossPool] = useState(false);
  const [hetero, setHetero] = useState(false);
  const [extAccess, setExtAccess] = useState(false);
  const [simulating, setSimulating] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const handleSimulate = () => {
    setSimulating(true);
    setTimeout(() => {
      setSimulating(false);
      const violations = GOVERNANCE_BINDINGS.filter((b) => {
        if (crossPool && !b.allowCrossPool) return true;
        if (hetero && !b.allowHetero) return true;
        return false;
      });
      if (violations.length > 0) {
        setResult(`检测到 ${violations.length} 条合规风险：${violations.map((v) => v.model).join('、')}`);
      } else {
        setResult('所有策略通过合规校验 ✅');
      }
    }, 1500);
  };

  return (
    <div>
      <PageHeader
        title="数据治理"
        subtitle="安全策略 · 数据分级 · 合规控制"
        tags={[
          { label: `${DATA_CLASSIFICATIONS.length} 级分类`, color: 'blue' },
          { label: `${GOVERNANCE_BINDINGS.length} 条绑定`, color: 'cyan' },
        ]}
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {DATA_CLASSIFICATIONS.map((cls) => (
          <Col span={6} key={cls.level}>
            <Card style={{ borderRadius: 8, borderLeft: `4px solid ${cls.color}` }} size="small">
              <Space>
                <div style={{ width: 12, height: 12, borderRadius: 6, background: cls.color }} />
                <strong>{cls.label}</strong>
                <Tag color={cls.color}>{cls.level}</Tag>
              </Space>
              <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>{cls.desc}</div>
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={16}>
          <Card title="模型-数据-资源 绑定关系" style={{ borderRadius: 8 }}>
            <Table dataSource={GOVERNANCE_BINDINGS} rowKey="model" pagination={false} size="small"
              columns={[
                { title: '模型', dataIndex: 'model', render: (v) => <strong>{v}</strong> },
                { title: '数据等级', dataIndex: 'dataLevel', width: 110,
                  render: (v) => <Tag color={LEVEL_COLORS[v]}>{v}</Tag> },
                { title: 'GPU 限制', dataIndex: 'gpuRestriction', width: 150,
                  render: (v) => <Tag color="cyan">{v}</Tag> },
                { title: '集群', dataIndex: 'cluster', width: 80 },
                { title: '跨池', dataIndex: 'allowCrossPool', width: 70,
                  render: (v) => v ? <CheckCircleOutlined style={{ color: '#52C41A' }} /> : <CloseCircleOutlined style={{ color: '#FF4D4F' }} /> },
                { title: '异构混跑', dataIndex: 'allowHetero', width: 80,
                  render: (v) => v ? <CheckCircleOutlined style={{ color: '#52C41A' }} /> : <CloseCircleOutlined style={{ color: '#FF4D4F' }} /> },
              ]}
            />
          </Card>
        </Col>

        <Col span={8}>
          <Card title="策略模拟器" style={{ borderRadius: 8 }}>
            <Space direction="vertical" style={{ width: '100%' }} size={16}>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span style={{ fontSize: 12 }}>允许跨池调度</span>
                  <Switch checked={crossPool} onChange={setCrossPool} />
                </div>
                {crossPool && (
                  <div style={{ fontSize: 11, color: '#FAAD14' }}>
                    ⚠️ Regulatory 级模型当前不允许跨池
                  </div>
                )}
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span style={{ fontSize: 12 }}>允许异构混跑</span>
                  <Switch checked={hetero} onChange={setHetero} />
                </div>
                {hetero && (
                  <div style={{ fontSize: 11, color: '#FAAD14' }}>
                    ⚠️ 部分模型仅限专用 GPU
                  </div>
                )}
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span style={{ fontSize: 12 }}>允许外部访问</span>
                  <Switch checked={extAccess} onChange={setExtAccess} />
                </div>
              </div>

              <Divider style={{ margin: '4px 0' }} />

              <Button type="primary" icon={<ExperimentOutlined />} onClick={handleSimulate} loading={simulating}
                block style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
                模拟校验
              </Button>

              {result && (
                <div style={{
                  padding: 12, borderRadius: 6,
                  background: result.includes('✅') ? '#F6FFED' : '#FFF7E6',
                  border: `1px solid ${result.includes('✅') ? '#52C41A' : '#FAAD14'}`,
                  fontSize: 12,
                }}>
                  {result}
                </div>
              )}
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
