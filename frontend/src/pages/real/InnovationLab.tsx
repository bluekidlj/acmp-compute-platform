import { useEffect, useMemo, useState } from 'react';
import {
  AreaChartOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  PlayCircleOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Collapse,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Table,
  Tag,
  message,
} from 'antd';
import dayjs from 'dayjs';
import { api } from '../../api/real';
import PageHeader from '../../components/PageHeader';
import type { ModelDeployment, Project, Tenant } from '../../types';

type LoadPattern =
  | '稳定型'
  | '周期型'
  | '突发型'
  | '批处理型'
  | '长上下文型'
  | '长输出型'
  | '低效占用型'
  | '混合冲突型';
type BusinessType =
  | '智能客服'
  | '员工知识助手'
  | '智能营销助手'
  | '风控与反欺诈辅助'
  | '合规审查与报告生成'
  | '票据与文档处理';
type InjectionType = 'TRAFFIC_SPIKE' | 'GPU_OFFLINE';
type StrategyType = 'BALANCED' | 'UTILIZATION' | 'COST' | 'SLA';

interface WorkloadProfile {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  deploymentId: string;
  deploymentName: string;
  businessType: BusinessType;
  patterns: LoadPattern[];
  rangeText: string;
  source: 'PROMETHEUS';
  createdAt: string;
}

interface TwinBaseline {
  id: string;
  name: string;
  workloadProfileId: string;
  workloadName: string;
  modelSnapshot: string;
  injectionType: InjectionType;
  injectionValue: number;
  createdAt: string;
}

interface StrategyResult {
  strategy: StrategyType;
  p95: number;
  throughput: number;
  sla: number;
  gpuUtilization: number;
  gpuHours: number;
  maxQueue: number;
  recoveryMinutes: number;
}

const PROFILE_KEY = 'acmp-lab-workload-profiles';
const TWIN_KEY = 'acmp-lab-twin-baselines';

const BUSINESS_OPTIONS: BusinessType[] = [
  '智能客服',
  '员工知识助手',
  '智能营销助手',
  '风控与反欺诈辅助',
  '合规审查与报告生成',
  '票据与文档处理',
];
const PATTERN_OPTIONS: LoadPattern[] = [
  '稳定型',
  '周期型',
  '突发型',
  '批处理型',
  '长上下文型',
  '长输出型',
  '低效占用型',
  '混合冲突型',
];
const MODEL_SNAPSHOTS = ['DeepSeek V4', 'GLM-5.1', 'Kimi K3'];
const STRATEGY_NAMES: Record<StrategyType, string> = {
  BALANCED: '平均分配',
  UTILIZATION: '利用率优先',
  COST: '成本优先',
  SLA: 'SLA 优先',
};

function readStorage<T>(key: string): T[] {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]') as T[];
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}

function writeStorage<T>(key: string, values: T[]) {
  localStorage.setItem(key, JSON.stringify(values));
}

function SimpleCurve(props: { title: string; unit: string; color: string; values: number[] }) {
  const max = Math.max(...props.values);
  const min = Math.min(...props.values);
  const points = props.values.map(function toPoint(value, index) {
    const x = 12 + (index * 276) / (props.values.length - 1);
    const y = max === min ? 55 : 92 - ((value - min) / (max - min)) * 68;
    return `${x},${y}`;
  }).join(' ');

  return (
    <Card size="small" className="lab-curve-card">
      <div className="lab-curve-title">
        <strong>{props.title}</strong>
        <span>{props.values[props.values.length - 1]} {props.unit}</span>
      </div>
      <svg viewBox="0 0 300 110" role="img" aria-label={`${props.title}负载曲线`}>
        <line x1="12" y1="92" x2="288" y2="92" className="lab-chart-axis" />
        <line x1="12" y1="24" x2="288" y2="24" className="lab-chart-grid" />
        <line x1="12" y1="58" x2="288" y2="58" className="lab-chart-grid" />
        <polyline points={points} fill="none" stroke={props.color} strokeWidth="3" />
      </svg>
      <div className="lab-chart-time"><span>开始</span><span>结束</span></div>
    </Card>
  );
}

async function loadProjectOptions() {
  const tenants: Tenant[] = await api.tenants();
  const projectGroups = await Promise.all(tenants.map(function loadTenantProjects(tenant) {
    return api.projects(tenant.id);
  }));
  return projectGroups.flat();
}

export function WorkloadInsightPage() {
  const [form] = Form.useForm();
  const [projects, setProjects] = useState<Project[]>([]);
  const [deployments, setDeployments] = useState<ModelDeployment[]>([]);
  const [profiles, setProfiles] = useState<WorkloadProfile[]>(readStorage(PROFILE_KEY));
  const [analyzed, setAnalyzed] = useState(false);
  const selectedProjectId = Form.useWatch('projectId', form);

  useEffect(function loadInputs() {
    Promise.all([loadProjectOptions(), api.deployments()])
      .then(function applyInputs(values) {
        setProjects(values[0]);
        setDeployments(values[1]);
      })
      .catch(function showLoadError(error) {
        message.error(error instanceof Error ? error.message : '负载感知输入加载失败');
      });
  }, []);

  const availableDeployments = useMemo(function filterDeployments() {
    return deployments.filter(function belongsToProject(item) {
      return item.projectId === selectedProjectId;
    });
  }, [deployments, selectedProjectId]);

  function analyze() {
    form.validateFields().then(function showAnalysis() {
      setAnalyzed(true);
    }).catch(function keepFormErrors() {
      setAnalyzed(false);
    });
  }

  function saveProfile() {
    form.validateFields().then(function persist(values) {
      const project = projects.find(function findProject(item) { return item.id === values.projectId; });
      const deployment = deployments.find(function findDeployment(item) { return item.id === values.deploymentId; });
      const profile: WorkloadProfile = {
        id: `profile-${Date.now()}`,
        name: values.name,
        projectId: values.projectId,
        projectName: project?.name || values.projectId,
        deploymentId: values.deploymentId,
        deploymentName: deployment?.name || values.deploymentId,
        businessType: values.businessType,
        patterns: values.patterns,
        rangeText: `${values.range[0].format('YYYY-MM-DD HH:mm')} ~ ${values.range[1].format('YYYY-MM-DD HH:mm')}`,
        source: 'PROMETHEUS',
        createdAt: dayjs().format('YYYY-MM-DD HH:mm:ss'),
      };
      const next = [profile, ...profiles];
      writeStorage(PROFILE_KEY, next);
      setProfiles(next);
      message.success('负载感知结果已保存，并生成 SimAI 负载快照');
    });
  }

  return (
    <div className="innovation-lab">
      <PageHeader title="负载感知" subtitle="建立推理服务负载基线，并生成可用于 SimAI 的负载快照" />
      <Card title="1. 选择监控数据" className="lab-surface">
        <Form form={form} layout="vertical" initialValues={{
          name: '智能客服工作日负载基线',
          businessType: '智能客服',
          patterns: ['周期型'],
          range: [dayjs().subtract(24, 'hour'), dayjs()],
        }}>
          <Row gutter={16}>
            <Col span={8}><Form.Item name="name" label="负载感知名称" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={8}><Form.Item name="projectId" label="项目" rules={[{ required: true }]}><Select options={projects.map(item => ({ label: item.name, value: item.id }))} onChange={() => form.setFieldValue('deploymentId', undefined)} /></Form.Item></Col>
            <Col span={8}><Form.Item name="deploymentId" label="推理服务" rules={[{ required: true }]}><Select disabled={!selectedProjectId} options={availableDeployments.map(item => ({ label: item.name, value: item.id }))} /></Form.Item></Col>
            <Col span={8}><Form.Item name="range" label="监控时间范围" rules={[{ required: true }]}><DatePicker.RangePicker showTime style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={8}><Form.Item name="businessType" label="推理服务类型" rules={[{ required: true }]}><Select options={BUSINESS_OPTIONS.map(value => ({ label: value, value }))} /></Form.Item></Col>
            <Col span={8}><Form.Item name="patterns" label="待识别负载模式" rules={[{ required: true }]}><Select mode="multiple" maxTagCount={2} options={PATTERN_OPTIONS.map(value => ({ label: value, value }))} /></Form.Item></Col>
          </Row>
          <Space>
            <Button type="primary" icon={<AreaChartOutlined />} onClick={analyze}>开始负载感知</Button>
            <Tag color="blue">数据来源：PROMETHEUS</Tag>
          </Space>
        </Form>
      </Card>

      {analyzed && (
        <Card title="2. 负载感知结果" className="lab-surface" extra={<Tag color="success">负载感知完毕</Tag>}>
          <Row gutter={[16, 16]}>
            <Col span={12}><SimpleCurve title="请求负载（QPS / 并发）" unit="QPS" color="#1677ff" values={[82, 95, 110, 148, 132, 196, 238, 205, 172, 156]} /></Col>
            <Col span={12}><SimpleCurve title="Token 负载（输入 / 输出）" unit="Token/s" color="#722ed1" values={[900, 1050, 1210, 1680, 1510, 2230, 2510, 2180, 1850, 1720]} /></Col>
            <Col span={12}><SimpleCurve title="服务质量（TTFT / P95 / P99）" unit="ms" color="#fa8c16" values={[320, 340, 390, 510, 470, 720, 860, 690, 520, 460]} /></Col>
            <Col span={12}><SimpleCurve title="GPU 负载（利用率 / 显存）" unit="%" color="#08979c" values={[31, 36, 42, 55, 51, 71, 84, 76, 63, 58]} /></Col>
          </Row>
          <Alert className="lab-result-alert" type="success" showIcon message="已识别：周期型 + 突发型" description="工作时段负载呈周期变化，峰值窗口 QPS、排队请求数和 P95 延迟同时上升。" />
          <Space>
            <Button type="primary" icon={<SaveOutlined />} onClick={saveProfile}>保存负载感知结果</Button>
            <span>保存内容：请求、Token、延迟、成功/失败、排队、副本及 GPU 指标快照</span>
          </Space>
        </Card>
      )}

      <Card title={`已保存负载快照（${profiles.length}）`} className="lab-surface">
        {profiles.length === 0 ? <Empty description="暂无负载快照" /> : (
          <Table rowKey="id" pagination={false} dataSource={profiles} columns={[
            { title: '名称', dataIndex: 'name' },
            { title: '项目 / 推理服务', render: (_, item) => `${item.projectName} / ${item.deploymentName}` },
            { title: '业务类型', dataIndex: 'businessType' },
            { title: '负载模式', render: (_, item) => item.patterns.map(pattern => <Tag key={pattern}>{pattern}</Tag>) },
            { title: '数据来源', dataIndex: 'source' },
            { title: '创建时间', dataIndex: 'createdAt' },
          ]} />
        )}
      </Card>
    </div>
  );
}

export function DigitalTwinPage() {
  const [form] = Form.useForm();
  const [profiles] = useState<WorkloadProfile[]>(readStorage(PROFILE_KEY));
  const [twins, setTwins] = useState<TwinBaseline[]>(readStorage(TWIN_KEY));
  const [preview, setPreview] = useState(false);
  const injectionType = Form.useWatch('injectionType', form) as InjectionType | undefined;
  const selectedProfileId = Form.useWatch('workloadProfileId', form);
  const selectedProfile = profiles.find(item => item.id === selectedProfileId);

  function buildBaseline() {
    form.validateFields().then(function showPreview() {
      setPreview(true);
    });
  }

  function saveBaseline() {
    form.validateFields().then(function persist(values) {
      const baseline: TwinBaseline = {
        id: `twin-${Date.now()}`,
        name: values.name,
        workloadProfileId: values.workloadProfileId,
        workloadName: selectedProfile?.name || values.workloadProfileId,
        modelSnapshot: values.modelSnapshot,
        injectionType: values.injectionType,
        injectionValue: values.injectionValue,
        createdAt: dayjs().format('YYYY-MM-DD HH:mm:ss'),
      };
      const next = [baseline, ...twins];
      writeStorage(TWIN_KEY, next);
      setTwins(next);
      message.success('数字孪生基线已保存');
    });
  }

  return (
    <div className="innovation-lab">
      <PageHeader title="数字孪生" subtitle="基于负载快照、模型快照和单项注入事件构建 SimAI 输入基线" />
      <Card title="1. 构建孪生输入" className="lab-surface">
        <Form form={form} layout="vertical" initialValues={{ name: '推理服务孪生基线', modelSnapshot: MODEL_SNAPSHOTS[0], injectionType: 'TRAFFIC_SPIKE', injectionValue: 3 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="name" label="基线名称" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="workloadProfileId" label="负载快照" rules={[{ required: true }]}><Select placeholder="选择一次已保存的负载感知结果" options={profiles.map(item => ({ label: `${item.name} · ${item.deploymentName}`, value: item.id }))} /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelSnapshot" label="模型快照" rules={[{ required: true }]}><Select options={MODEL_SNAPSHOTS.map(value => ({ label: value, value }))} /></Form.Item></Col>
            <Col span={12}><Form.Item name="injectionType" label="注入类型" rules={[{ required: true }]}><Select options={[{ label: '负载注入：流量突增', value: 'TRAFFIC_SPIKE' }, { label: '故障注入：GPU 下线', value: 'GPU_OFFLINE' }]} /></Form.Item></Col>
            <Col span={12}><Form.Item name="injectionValue" label={injectionType === 'GPU_OFFLINE' ? '下线 GPU 数量' : 'QPS 倍率'} rules={[{ required: true }]}><InputNumber min={1} max={injectionType === 'GPU_OFFLINE' ? 8 : 10} style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={12}><Form.Item label="注入时间"><Input value="基线开始后第 30 分钟，持续 20 分钟" disabled /></Form.Item></Col>
          </Row>
          <Button type="primary" icon={<DatabaseOutlined />} onClick={buildBaseline}>构建数字孪生基线</Button>
        </Form>
      </Card>

      {preview && selectedProfile && (
        <Card title="2. SimAI 输入预览" className="lab-surface" extra={<Tag color="processing">待保存</Tag>}>
          <Descriptions bordered column={2}>
            <Descriptions.Item label="负载快照">{selectedProfile.name}</Descriptions.Item>
            <Descriptions.Item label="监控范围">{selectedProfile.rangeText}</Descriptions.Item>
            <Descriptions.Item label="业务 / 模式">{selectedProfile.businessType} / {selectedProfile.patterns.join('、')}</Descriptions.Item>
            <Descriptions.Item label="数据来源">{selectedProfile.source}</Descriptions.Item>
            <Descriptions.Item label="模型快照">{form.getFieldValue('modelSnapshot')}</Descriptions.Item>
            <Descriptions.Item label="注入事件">{injectionType === 'GPU_OFFLINE' ? 'GPU 下线' : '流量突增'} × {form.getFieldValue('injectionValue')}</Descriptions.Item>
          </Descriptions>
          <Button className="lab-action-button" type="primary" icon={<SaveOutlined />} onClick={saveBaseline}>保存数字孪生基线</Button>
        </Card>
      )}

      <Card title={`已保存孪生基线（${twins.length}）`} className="lab-surface">
        {twins.length === 0 ? <Empty description="请先保存数字孪生基线" /> : (
          <Table rowKey="id" pagination={false} dataSource={twins} columns={[
            { title: '基线名称', dataIndex: 'name' },
            { title: '负载快照', dataIndex: 'workloadName' },
            { title: '模型快照', dataIndex: 'modelSnapshot' },
            { title: '注入', render: (_, item) => item.injectionType === 'GPU_OFFLINE' ? `GPU 下线 × ${item.injectionValue}` : `流量突增 × ${item.injectionValue}` },
            { title: '创建时间', dataIndex: 'createdAt' },
          ]} />
        )}
      </Card>
    </div>
  );
}

const RESULT_DATA: StrategyResult[] = [
  { strategy: 'BALANCED', p95: 740, throughput: 1820, sla: 96.2, gpuUtilization: 68, gpuHours: 18.2, maxQueue: 44, recoveryMinutes: 8 },
  { strategy: 'UTILIZATION', p95: 910, throughput: 1900, sla: 94.1, gpuUtilization: 84, gpuHours: 15.8, maxQueue: 71, recoveryMinutes: 11 },
  { strategy: 'COST', p95: 1280, throughput: 1640, sla: 89.6, gpuUtilization: 88, gpuHours: 12.4, maxQueue: 126, recoveryMinutes: 15 },
  { strategy: 'SLA', p95: 510, throughput: 2010, sla: 99.1, gpuUtilization: 61, gpuHours: 22.6, maxQueue: 18, recoveryMinutes: 5 },
];

export function StrategySimulationPage() {
  const [form] = Form.useForm();
  const [twins] = useState<TwinBaseline[]>(readStorage(TWIN_KEY));
  const [results, setResults] = useState<StrategyResult[]>([]);

  function runSimulation() {
    form.validateFields().then(function simulate(values) {
      setResults(RESULT_DATA.filter(item => values.strategies.includes(item.strategy)));
      message.success('策略仿真完成');
    });
  }

  return (
    <div className="innovation-lab">
      <PageHeader title="策略仿真" subtitle="使用同一数字孪生基线比较调度策略的 SimAI 仿真结果" />
      <Card title="1. 选择仿真基线与策略" className="lab-surface">
        <Form form={form} layout="vertical" initialValues={{ strategies: ['BALANCED', 'UTILIZATION', 'COST', 'SLA'] }}>
          <Form.Item name="twinId" label="数字孪生基线" rules={[{ required: true }]}>
            <Select placeholder="选择已保存的数字孪生基线" options={twins.map(item => ({ label: `${item.name} · ${item.modelSnapshot}`, value: item.id }))} />
          </Form.Item>
          <Form.Item name="strategies" label="调度策略" rules={[{ required: true }]}>
            <Checkbox.Group options={(Object.keys(STRATEGY_NAMES) as StrategyType[]).map(value => ({ label: STRATEGY_NAMES[value], value }))} />
          </Form.Item>
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={runSimulation}>启动 SimAI 策略仿真</Button>
        </Form>
      </Card>

      <Card title="2. KPI 对比结果" className="lab-surface" extra={results.length > 0 ? <Tag color="success">仿真完成</Tag> : undefined}>
        {results.length === 0 ? <Empty description="请选择孪生基线并启动仿真" /> : (
          <>
            <Table rowKey="strategy" pagination={false} dataSource={results} columns={[
              { title: '策略', dataIndex: 'strategy', render: value => <strong>{STRATEGY_NAMES[value as StrategyType]}</strong> },
              { title: 'P95 响应时间', dataIndex: 'p95', render: value => `${value} ms` },
              { title: '吞吐量', dataIndex: 'throughput', render: value => `${value} Token/s` },
              { title: 'SLA 达标率', dataIndex: 'sla', render: value => `${value}%` },
              { title: 'GPU 平均利用率', dataIndex: 'gpuUtilization', render: value => `${value}%` },
              { title: 'GPU 卡时', dataIndex: 'gpuHours', render: value => `${value} h` },
              { title: '最大排队', dataIndex: 'maxQueue' },
              { title: '恢复时间', dataIndex: 'recoveryMinutes', render: value => `${value} min` },
            ]} />
            <Alert className="lab-result-alert" type="info" showIcon icon={<ExperimentOutlined />} message="结果摘要：SLA 优先在当前注入场景下响应和恢复表现最好，但 GPU 卡时最高；成本优先卡时最低，但未达到同等 SLA。" />
          </>
        )}
      </Card>

      <Collapse className="lab-surface" items={[{
        key: 'guide',
        label: '仿真结果阅读指南',
        children: (
          <Descriptions bordered column={1}>
            <Descriptions.Item label="P95 / SLA">优先判断业务是否满足延迟目标；银行在线业务通常先看这两项。</Descriptions.Item>
            <Descriptions.Item label="吞吐量 / 最大排队">判断峰值期间是否形成请求积压，以及单位时间处理能力。</Descriptions.Item>
            <Descriptions.Item label="GPU 利用率 / 卡时">利用率反映资源使用强度，卡时用于比较资源成本，二者需结合 SLA 判断。</Descriptions.Item>
            <Descriptions.Item label="恢复时间">故障或流量注入结束后恢复到基线性能所需时间，越短越好。</Descriptions.Item>
            <Descriptions.Item label="使用边界">当前结果是 SimAI 仿真参考，不会自动修改真实 Kubernetes 调度策略。</Descriptions.Item>
          </Descriptions>
        ),
      }]} />
    </div>
  );
}

export default WorkloadInsightPage;
