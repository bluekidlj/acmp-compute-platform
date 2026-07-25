import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Col, DatePicker, Empty, Row, Select, Space, Table, Tag, message } from 'antd';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import type {
  ClusterMonitoringDetail,
  ClusterMonitoringSummary,
  ModelDeployment,
  MonitoringSeries,
  PhysicalCluster,
  Project,
  Tenant,
} from '../../types';

type TimeRange = '15m' | '1h' | '6h' | '24h' | 'custom';

interface ChartLine {
  name: string;
  color: string;
  values: number[];
}

const SAMPLE_TIMES = ['09:00', '09:10', '09:20', '09:30', '09:40', '09:50', '10:00'];

function MonitoringChart(props: { title: string; unit: string; lines: ChartLine[]; labels?: string[] }) {
  if (props.lines.length === 0) {
    return <Card title={props.title} className="monitor-chart-card"><Empty description="暂无监控数据" /></Card>;
  }
  const values = props.lines.flatMap(line => line.values);
  const maximum = Math.max(...values, 1);
  const polylines = props.lines.map(function buildLine(line) {
    const points = line.values.map(function buildPoint(value, index) {
      const x = 36 + (index * 424) / Math.max(line.values.length - 1, 1);
      const y = 150 - (value / maximum) * 112;
      return `${x},${y}`;
    }).join(' ');
    return <polyline key={line.name} points={points} fill="none" stroke={line.color} strokeWidth="3" />;
  });

  return (
    <Card title={props.title} className="monitor-chart-card" extra={<span className="monitor-chart-unit">{props.unit}</span>}>
      <div className="monitor-chart-legend">
        {props.lines.map(line => <span key={line.name}><i style={{ background: line.color }} />{line.name}</span>)}
      </div>
      <svg viewBox="0 0 500 180" role="img" aria-label={props.title}>
        <line x1="36" y1="38" x2="460" y2="38" className="monitor-grid-line" />
        <line x1="36" y1="94" x2="460" y2="94" className="monitor-grid-line" />
        <line x1="36" y1="150" x2="460" y2="150" className="monitor-axis-line" />
        {polylines}
      </svg>
      <div className="monitor-chart-labels">{(props.labels || SAMPLE_TIMES).map(time => <span key={time}>{time}</span>)}</div>
    </Card>
  );
}

function TimeRangeSelector(props: {
  value: TimeRange;
  onChange: (value: TimeRange) => void;
  onCustomChange?: (start: Dayjs, end: Dayjs) => void;
  onRefresh?: () => void;
}) {
  return (
    <Space>
      <Select
        value={props.value}
        style={{ width: 140 }}
        onChange={props.onChange}
        options={[
          { label: '最近15分钟', value: '15m' },
          { label: '最近1小时', value: '1h' },
          { label: '最近6小时', value: '6h' },
          { label: '最近24小时', value: '24h' },
          { label: '自定义', value: 'custom' },
        ]}
      />
      {props.value === 'custom' && (
        <DatePicker.RangePicker
          showTime
          onChange={values => {
            if (values?.[0] && values[1]) {
              props.onCustomChange?.(values[0], values[1]);
            }
          }}
        />
      )}
      <Button icon={<ReloadOutlined />} onClick={props.onRefresh}>刷新</Button>
    </Space>
  );
}

function SampleTag() {
  return <Tag color="gold">前端样例数据 · 待接 Prometheus</Tag>;
}

export function DeploymentMonitoringListPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<ModelDeployment[]>([]);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(function loadList() {
    Promise.all([api.deployments(), api.tenants()])
      .then(async function loadProjects(values) {
        const projectGroups = await Promise.all(values[1].map(tenant => api.projects(tenant.id)));
        setItems(values[0]);
        setTenants(values[1]);
        setProjects(projectGroups.flat());
      })
      .catch(error => message.error(error instanceof Error ? error.message : '推理服务加载失败'))
      .finally(() => setLoading(false));
  }, []);

  const projectMap = Object.fromEntries(projects.map(project => [project.id, project.name]));
  const tenantMap = Object.fromEntries(tenants.map(tenant => [tenant.id, tenant.name]));

  return (
    <div>
      <PageHeader title="推理服务监控" subtitle="查看推理引擎直接暴露的请求与 Token 指标" tags={[{ label: 'Prometheus', color: 'blue' }]} />
      <div className="surface data-table">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={items}
          pagination={false}
          onRow={record => ({ onClick: () => navigate(`/monitoring/deployments/${record.projectId}/${record.id}`), style: { cursor: 'pointer' } })}
          columns={[
            { title: '服务名称', dataIndex: 'name', render: value => <strong>{value}</strong> },
            { title: '租户 / 项目', render: (_, item) => `${tenantMap[item.tenantId] || item.tenantId} / ${projectMap[item.projectId] || item.projectId}` },
            { title: '模型', dataIndex: 'modelName', render: value => value || '-' },
            { title: '状态', dataIndex: 'status', render: value => <StatusBadge value={value} /> },
            { title: '副本', render: (_, item) => `${item.readyReplicas ?? 0}/${item.replicas}` },
            { title: '运行请求', render: () => '-' },
            { title: '等待请求', render: () => '-' },
            { title: '监控采集', render: () => <Tag>待接入</Tag> },
            { title: '操作', render: (_, item) => <Button size="small" onClick={event => { event.stopPropagation(); navigate(`/monitoring/deployments/${item.projectId}/${item.id}`); }}>查看详情</Button> },
          ]}
        />
      </div>
    </div>
  );
}

export function DeploymentMonitoringDetailPage() {
  const navigate = useNavigate();
  const { projectId, deploymentId } = useParams();
  const [deployment, setDeployment] = useState<ModelDeployment>();
  const [range, setRange] = useState<TimeRange>('1h');

  useEffect(function loadDeployment() {
    if (!projectId || !deploymentId) return;
    api.deployment(projectId, deploymentId)
      .then(setDeployment)
      .catch(error => message.error(error instanceof Error ? error.message : '推理服务加载失败'));
  }, [deploymentId, projectId]);

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/monitoring/deployments')}>返回推理服务监控</Button>
      <PageHeader
        title={deployment?.name || '推理服务监控详情'}
        subtitle={`${deployment?.modelName || '-'} · ${deployment?.serviceUrl || '-'}`}
        extra={<TimeRangeSelector value={range} onChange={setRange} />}
      />
      <SampleTag />
      <Row gutter={[16, 16]} className="monitor-summary-row">
        <Col span={4}><Card><div className="monitor-summary-label">服务状态</div><div className="monitor-summary-value">{deployment ? <StatusBadge value={deployment.status} /> : '-'}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">就绪副本</div><div className="monitor-summary-value">{deployment ? `${deployment.readyReplicas ?? 0}/${deployment.replicas}` : '-'}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">运行请求</div><div className="monitor-summary-value">6</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">等待请求</div><div className="monitor-summary-value">1</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">Prompt Token/s</div><div className="monitor-summary-value">320.5</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">Generation Token/s</div><div className="monitor-summary-value">86.2</div></Card></Col>
      </Row>
      <Row gutter={[16, 16]}>
        <Col span={12}><MonitoringChart title="请求状态" unit="requests" lines={[{ name: '运行请求', color: '#1677ff', values: [2, 3, 4, 4, 6, 5, 6] }, { name: '等待请求', color: '#fa8c16', values: [0, 0, 1, 2, 1, 0, 1] }]} /></Col>
        <Col span={12}><MonitoringChart title="Token 吞吐" unit="token/s" lines={[{ name: 'Prompt Token/s', color: '#722ed1', values: [180, 205, 240, 286, 310, 298, 320] }, { name: 'Generation Token/s', color: '#13a8a8', values: [42, 48, 61, 75, 82, 78, 86] }]} /></Col>
      </Row>
    </div>
  );
}

function formatPercent(value: number | null) {
  return value === null ? '-' : `${value.toFixed(1)}%`;
}

function findMonitoringSeries(detail: ClusterMonitoringDetail | undefined, metric: string) {
  return detail?.series.find(item => item.metric === metric);
}

function toChartLine(series: MonitoringSeries | undefined, name: string, color: string): ChartLine[] {
  if (!series) return [];
  return [{ name, color, values: series.points.map(point => point.value) }];
}

function timeLabels(series: MonitoringSeries | undefined) {
  if (!series || series.points.length === 0) return [];
  const indexes = Array.from(new Set([
    0,
    Math.floor(series.points.length / 3),
    Math.floor(series.points.length * 2 / 3),
    series.points.length - 1,
  ]));
  return indexes.map(index => dayjs.unix(series.points[index].timestamp).format('HH:mm'));
}

function queryRange(range: TimeRange, customRange?: [Dayjs, Dayjs]) {
  const end = dayjs();
  if (range === 'custom' && customRange) {
    const durationHours = customRange[1].diff(customRange[0], 'hour', true);
    const step = durationHours <= 1 ? 60 : durationHours <= 6 ? 300 : 900;
    return { start: customRange[0], end: customRange[1], step };
  }
  if (range === '15m') return { start: end.subtract(15, 'minute'), end, step: 15 };
  if (range === '6h') return { start: end.subtract(6, 'hour'), end, step: 300 };
  if (range === '24h') return { start: end.subtract(24, 'hour'), end, step: 900 };
  return { start: end.subtract(1, 'hour'), end, step: 60 };
}

export function ClusterMonitoringListPage() {
  const navigate = useNavigate();
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [monitoring, setMonitoring] = useState<ClusterMonitoringSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(function loadClusters() {
    // 集群资产是列表主数据，不能因为 Prometheus 未配置或监控接口未启动而消失。
    api.clusters()
      .then(setClusters)
      .catch(error => message.error(error instanceof Error ? error.message : '集群资产加载失败'))
      .finally(() => setLoading(false));

    // 监控数据是可选增强项，查询失败时保留现有集群资产列表。
    api.clusterMonitoring()
      .then(setMonitoring)
      .catch(() => setMonitoring([]));
  }, []);

  const monitoringMap = Object.fromEntries(monitoring.map(item => [item.clusterId, item]));
  const items = clusters.map(function mergeCluster(cluster) {
    return {
      cluster,
      monitoring: monitoringMap[cluster.id] as ClusterMonitoringSummary | undefined,
    };
  });

  return (
    <div>
      <PageHeader title="集群监控" subtitle="查看 Kubernetes 集群、真实节点和 GPU 的运行指标" tags={[{ label: 'Prometheus', color: 'blue' }]} />
      <div className="surface data-table">
        <Table
          rowKey={item => item.cluster.id}
          loading={loading}
          dataSource={items}
          pagination={false}
          onRow={record => ({ onClick: () => navigate(`/monitoring/clusters/${record.cluster.id}`), style: { cursor: 'pointer' } })}
          columns={[
            { title: '集群名称', render: (_, item) => <strong>{item.cluster.name}</strong> },
            { title: '状态', render: (_, item) => <StatusBadge value={item.cluster.status} /> },
            { title: 'Kubernetes', render: (_, item) => item.cluster.kubernetesVersion || '-' },
            { title: '节点数', render: (_, item) => item.cluster.nodeCount },
            { title: 'GPU 数', render: (_, item) => item.cluster.gpuCount },
            { title: 'CPU 使用率', render: (_, item) => formatPercent(item.monitoring?.cpuUsagePercent ?? null) },
            { title: '内存使用率', render: (_, item) => formatPercent(item.monitoring?.memoryUsagePercent ?? null) },
            { title: 'GPU 利用率', render: (_, item) => formatPercent(item.monitoring?.gpuUsagePercent ?? null) },
            {
              title: '最近同步',
              render: (_, item) => item.cluster.lastSyncAt
                ? dayjs(item.cluster.lastSyncAt).format('YYYY-MM-DD HH:mm:ss')
                : '-',
            },
            {
              title: '监控采集',
              render: (_, item) => item.monitoring?.lastCollectedAt
                ? dayjs(item.monitoring.lastCollectedAt).format('YYYY-MM-DD HH:mm:ss')
                : <Tag>暂无数据</Tag>,
            },
            { title: '操作', render: (_, item) => <Button size="small" onClick={event => { event.stopPropagation(); navigate(`/monitoring/clusters/${item.cluster.id}`); }}>查看详情</Button> },
          ]}
        />
      </div>
    </div>
  );
}

export function ClusterMonitoringDetailPage() {
  const navigate = useNavigate();
  const { clusterId } = useParams();
  const [detail, setDetail] = useState<ClusterMonitoringDetail>();
  const [range, setRange] = useState<TimeRange>('1h');
  const [customRange, setCustomRange] = useState<[Dayjs, Dayjs]>();

  function loadClusterMonitoring() {
    if (!clusterId) return;
    const selectedRange = queryRange(range, customRange);
    api.clusterMonitoringDetail(clusterId, {
      start: selectedRange.start.toISOString(),
      end: selectedRange.end.toISOString(),
      step: selectedRange.step,
    })
      .then(setDetail)
      .catch(error => message.error(error instanceof Error ? error.message : '集群监控加载失败'));
  }

  useEffect(function loadCluster() {
    loadClusterMonitoring();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clusterId, range, customRange]);

  const summary = detail?.summary;
  const cpuSeries = findMonitoringSeries(detail, 'cpu_usage_percent');
  const memorySeries = findMonitoringSeries(detail, 'memory_usage_percent');
  const gpuSeries = findMonitoringSeries(detail, 'gpu_usage_percent');
  const gpuMemorySeries = findMonitoringSeries(detail, 'gpu_memory_used_mib');

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/monitoring/clusters')}>返回集群监控</Button>
      <PageHeader
        title={summary?.name || '集群监控详情'}
        subtitle={`最近采集 ${summary?.lastCollectedAt ? dayjs(summary.lastCollectedAt).format('YYYY-MM-DD HH:mm:ss') : '暂无 Prometheus 数据'}`}
        extra={(
          <TimeRangeSelector
            value={range}
            onChange={setRange}
            onCustomChange={(start, end) => setCustomRange([start, end])}
            onRefresh={loadClusterMonitoring}
          />
        )}
      />
      <Row gutter={[16, 16]} className="monitor-summary-row">
        <Col span={6}><Card><div className="monitor-summary-label">集群状态</div><div className="monitor-summary-value">{summary ? <StatusBadge value={summary.status} /> : '-'}</div></Card></Col>
        <Col span={6}><Card><div className="monitor-summary-label">Ready 节点</div><div className="monitor-summary-value">{summary ? `${summary.readyNodeCount}/${summary.nodeCount}` : '-'}</div></Card></Col>
        <Col span={6}><Card><div className="monitor-summary-label">GPU 设备</div><div className="monitor-summary-value">{summary?.gpuCount ?? 0}</div></Card></Col>
        <Col span={6}><Card><div className="monitor-summary-label">监控来源</div><div className="monitor-summary-value monitor-summary-small">Prometheus</div></Card></Col>
      </Row>
      <Row gutter={[16, 16]}>
        <Col span={12}><MonitoringChart title="CPU 使用率" unit="%" lines={toChartLine(cpuSeries, '集群 CPU', '#1677ff')} labels={timeLabels(cpuSeries)} /></Col>
        <Col span={12}><MonitoringChart title="内存使用率" unit="%" lines={toChartLine(memorySeries, '集群内存', '#722ed1')} labels={timeLabels(memorySeries)} /></Col>
        <Col span={12}><MonitoringChart title="GPU 平均利用率" unit="%" lines={toChartLine(gpuSeries, 'GPU 利用率', '#08979c')} labels={timeLabels(gpuSeries)} /></Col>
        <Col span={12}><MonitoringChart title="GPU 显存已用" unit="MiB" lines={toChartLine(gpuMemorySeries, '显存已用', '#fa8c16')} labels={timeLabels(gpuMemorySeries)} /></Col>
      </Row>
    </div>
  );
}
