import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, DatePicker, Row, Select, Space, Table, Tag, message } from 'antd';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import * as echarts from 'echarts';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import type {
  ClusterMonitoringSummary,
  ClusterNode,
  DeploymentMetrics,
  ModelDeployment,
  MonitoringSeries,
  NodeMonitoringDetail,
  PhysicalCluster,
  Project,
  Tenant,
} from '../../types';

type TimeRange = '15m' | '1h' | '6h' | '24h' | 'custom';
type AxisDensity = 'compact' | 'standard' | 'wide';

interface ChartLine {
  name: string;
  color: string;
  values: Array<number | null>;
}

interface DeploymentMetricPoint {
  timestamp: string;
  runningRequests: number | null;
  waitingRequests: number | null;
  promptTokensPerSecond: number | null;
  generationTokensPerSecond: number | null;
}

type NodeMonitoringRange = {
  start: Dayjs;
  end: Dayjs;
  step: number;
};

function formatPercent(value: number | null) {
  return value === null ? '-' : `${value.toFixed(1)}%`;
}

function formatMbps(value: number | null) {
  return value === null ? '-' : `${value.toFixed(2)} Mbps`;
}

function formatLoad(value: number | null) {
  return value === null ? '-' : value.toFixed(2);
}

function formatMiB(value: number | null) {
  return value === null ? '-' : `${value.toFixed(0)} MiB`;
}

function isMasterNode(node: ClusterNode) {
  if (!node.labelsJson) return false;
  try {
    const labels = JSON.parse(node.labelsJson) as Record<string, string>;
    return Object.prototype.hasOwnProperty.call(labels, 'node-role.kubernetes.io/control-plane')
      || Object.prototype.hasOwnProperty.call(labels, 'node-role.kubernetes.io/master');
  } catch {
    return false;
  }
}

function findSeries(detail: { series: MonitoringSeries[] } | null | undefined, metric: string) {
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

function MonitoringChart(props: { title: string; unit: string; lines: ChartLine[]; labels?: string[]; density?: AxisDensity }) {
  const chartRef = useRef<HTMLDivElement | null>(null);
  const option = useMemo(() => {
    const sourceLabels = props.labels && props.labels.length > 0 ? props.labels : ['00', '05', '10', '15', '20', '25', '30', '35'];
    const slotCount = Math.max(sourceLabels.length, props.lines.reduce((max, line) => Math.max(max, line.values.length), 0), 8);
    const chartLabels = Array.from({ length: slotCount }, (_, index) => sourceLabels[Math.min(index, sourceLabels.length - 1)] || '');
    const numericValues = props.lines.flatMap(line => line.values)
      .filter((value): value is number => value !== null);
    const seriesMax = Math.max(...numericValues, 0);
    const upperBound = Math.max(seriesMax * 1.15, props.unit === '%' ? 100 : seriesMax > 0 ? seriesMax * 1.15 : 1);
    return {
      backgroundColor: 'transparent',
      animation: false,
      grid: { left: 56, right: 24, top: 36, bottom: 36, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
      legend: {
        top: 6,
        left: 8,
        icon: 'roundRect',
        itemWidth: 12,
        itemHeight: 8,
        textStyle: { color: '#595959' },
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: chartLabels,
        axisLine: { lineStyle: { color: '#d9d9d9' } },
        axisTick: { show: false },
        axisLabel: {
          color: '#8c8c8c',
          interval: props.density === 'compact' ? 2 : props.density === 'wide' ? 0 : 'auto',
          hideOverlap: true,
          margin: 14,
        },
        splitLine: { show: true, lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: upperBound,
        axisLine: { show: true, lineStyle: { color: '#d9d9d9' } },
        axisTick: { show: false },
        axisLabel: {
          color: '#8c8c8c',
          formatter: (value: number) => (props.unit === '%' ? `${value.toFixed(0)}%` : `${value.toFixed(0)}`),
        },
        splitLine: { show: true, lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
      },
      series: props.lines.map(line => ({
        name: line.name,
        type: 'line',
        smooth: true,
        symbol: 'circle',
        showSymbol: false,
        connectNulls: true,
        lineStyle: { width: 3, color: line.color },
        itemStyle: { color: line.color },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: `${line.color}55` },
            { offset: 1, color: `${line.color}08` },
          ]),
        },
        data: line.values.length > 0 ? line.values : Array.from({ length: chartLabels.length }, () => null),
      })),
      graphic: props.lines.length === 0 ? [{
        type: 'text',
        left: 'center',
        top: 'middle',
        style: {
          text: '暂无监控数据',
          fill: '#bfbfbf',
          fontSize: 14,
          fontWeight: 500,
        },
      }] : undefined,
    };
  }, [props.density, props.lines, props.labels, props.unit]);

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current);
    chart.setOption(option);
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [option]);

  return (
    <Card title={props.title} className="monitor-chart-card" extra={<span className="monitor-chart-unit">{props.unit}</span>}>
      <div ref={chartRef} className="monitor-echart" role="img" aria-label={props.title} />
    </Card>
  );
}

function TimeRangeSelector(props: {
  value: TimeRange;
  onChange: (value: TimeRange) => void;
  onCustomChange?: (start: Dayjs, end: Dayjs) => void;
  onRefresh?: () => void;
  density: AxisDensity;
  onDensityChange: (value: AxisDensity) => void;
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
      <Select
        value={props.density}
        style={{ width: 132 }}
        onChange={props.onDensityChange}
        options={[
          { label: '时间轴紧凑', value: 'compact' },
          { label: '标准显示', value: 'standard' },
          { label: '时间轴宽松', value: 'wide' },
        ]}
      />
      <Button icon={<ReloadOutlined />} onClick={props.onRefresh}>刷新</Button>
    </Space>
  );
}

function StatCard(props: { label: string; value: string | ReactNode; accent?: string }) {
  return (
    <Card className="monitor-stat-card">
      <div className="monitor-summary-label">{props.label}</div>
      <div className={`monitor-summary-value ${props.accent ? 'monitor-summary-accent' : ''}`} style={props.accent ? { color: props.accent } : undefined}>
        {props.value}
      </div>
    </Card>
  );
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
      .catch(error => message.error(error instanceof Error ? error.message : '推理服务监控加载失败'))
      .finally(() => setLoading(false));
  }, []);

  const projectMap = Object.fromEntries(projects.map(project => [project.id, project.name]));
  const tenantMap = Object.fromEntries(tenants.map(tenant => [tenant.id, tenant.name]));

  return (
    <div>
      <PageHeader title="推理服务监控" subtitle="查看推理服务实例、模型和副本状态" tags={[{ label: 'vLLM', color: 'blue' }]} />
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
            { title: '端口', dataIndex: 'port' },
            { title: 'Service URL', dataIndex: 'serviceUrl', render: value => value || '-' },
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
  const [metrics, setMetrics] = useState<DeploymentMetrics>();
  const [metricPoints, setMetricPoints] = useState<DeploymentMetricPoint[]>([]);
  const [metricError, setMetricError] = useState<string>();
  const [metricsLoading, setMetricsLoading] = useState(false);
  const previousMetrics = useRef<DeploymentMetrics>();

  useEffect(function loadDeployment() {
    if (!projectId || !deploymentId) return;
    api.deployment(projectId, deploymentId)
      .then(setDeployment)
      .catch(error => message.error(error instanceof Error ? error.message : '推理服务加载失败'));
  }, [deploymentId, projectId]);

  async function loadMetrics(showLoading = false) {
    if (!projectId || !deploymentId) return;
    if (showLoading) setMetricsLoading(true);
    try {
      const next = await api.deploymentMetrics(projectId, deploymentId);
      setMetrics(next);
      if (!next.available) {
        setMetricError(next.message || 'vLLM 暂无可用监控指标');
        previousMetrics.current = undefined;
        return;
      }
      setMetricError(undefined);
      const previous = previousMetrics.current;
      const elapsedSeconds = previous
        ? (dayjs(next.collectedAt).valueOf() - dayjs(previous.collectedAt).valueOf()) / 1000
        : 0;
      const promptRate = previous && elapsedSeconds > 0
        && next.promptTokensTotal !== null && previous.promptTokensTotal !== null
        ? Math.max(0, next.promptTokensTotal - previous.promptTokensTotal) / elapsedSeconds
        : null;
      const generationRate = previous && elapsedSeconds > 0
        && next.generationTokensTotal !== null && previous.generationTokensTotal !== null
        ? Math.max(0, next.generationTokensTotal - previous.generationTokensTotal) / elapsedSeconds
        : null;
      setMetricPoints(current => [...current, {
        timestamp: next.collectedAt,
        runningRequests: next.runningRequests,
        waitingRequests: next.waitingRequests,
        promptTokensPerSecond: promptRate,
        generationTokensPerSecond: generationRate,
      }].slice(-120));
      previousMetrics.current = next;
    } catch (error) {
      setMetricError(error instanceof Error ? error.message : 'vLLM 指标读取失败');
      previousMetrics.current = undefined;
    } finally {
      setMetricsLoading(false);
    }
  }

  useEffect(function pollVllmMetrics() {
    previousMetrics.current = undefined;
    setMetricPoints([]);
    void loadMetrics(true);
    const timer = window.setInterval(function refreshMetrics() {
      void loadMetrics();
    }, 5000);
    return function stopPolling() {
      window.clearInterval(timer);
    };
  }, [deploymentId, projectId]);

  const latestPoint = metricPoints[metricPoints.length - 1];
  const labels = metricPoints.map(point => dayjs(point.timestamp).format('HH:mm:ss'));
  const requestLines: ChartLine[] = metricPoints.length === 0 ? [] : [
    { name: '运行请求', color: '#1677ff', values: metricPoints.map(point => point.runningRequests) },
    { name: '等待请求', color: '#fa8c16', values: metricPoints.map(point => point.waitingRequests) },
  ];
  const tokenLines: ChartLine[] = metricPoints.some(point => point.promptTokensPerSecond !== null
      || point.generationTokensPerSecond !== null)
    ? [
      { name: 'Prompt Token/s', color: '#722ed1', values: metricPoints.map(point => point.promptTokensPerSecond) },
      { name: 'Generation Token/s', color: '#13a8a8', values: metricPoints.map(point => point.generationTokensPerSecond) },
    ]
    : [];

  function metricValue(value: number | null | undefined, digits = 1) {
    return value === null || value === undefined ? '-' : value.toFixed(digits);
  }

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/monitoring/deployments')}>返回推理服务监控</Button>
      <PageHeader
        title={deployment?.name || '推理服务监控详情'}
        subtitle={`${deployment?.modelName || '-'} · ${deployment?.serviceUrl || '-'}`}
        tags={[{ label: 'vLLM /metrics', color: metrics?.available ? 'green' : 'default' }]}
        extra={<Space><span style={{ color: '#66756f' }}>每 5 秒刷新</span><Button loading={metricsLoading} icon={<ReloadOutlined />} onClick={() => void loadMetrics(true)}>立即刷新</Button></Space>}
      />
      {metricError && <Alert type="warning" showIcon message="vLLM 监控暂不可用" description={metricError} style={{ marginBottom: 16 }} />}
      <Row gutter={[16, 16]} className="monitor-summary-row">
        <Col span={4}><Card><div className="monitor-summary-label">服务状态</div><div className="monitor-summary-value">{deployment ? <StatusBadge value={deployment.status} /> : '-'}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">就绪副本</div><div className="monitor-summary-value">{deployment ? `${deployment.readyReplicas ?? 0}/${deployment.replicas}` : '-'}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">运行请求</div><div className="monitor-summary-value">{metricValue(metrics?.runningRequests, 0)}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">等待请求</div><div className="monitor-summary-value">{metricValue(metrics?.waitingRequests, 0)}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">Prompt Token/s</div><div className="monitor-summary-value">{metricValue(latestPoint?.promptTokensPerSecond)}</div></Card></Col>
        <Col span={4}><Card><div className="monitor-summary-label">Generation Token/s</div><div className="monitor-summary-value">{metricValue(latestPoint?.generationTokensPerSecond)}</div></Card></Col>
      </Row>
      <Row gutter={[16, 16]}>
        <Col span={12}><MonitoringChart title="请求状态" unit="requests" density="standard" labels={labels} lines={requestLines} /></Col>
        <Col span={12}><MonitoringChart title="Token 吞吐" unit="token/s" density="standard" labels={labels} lines={tokenLines} /></Col>
      </Row>
    </div>
  );
}

export function ClusterMonitoringListPage() {
  const navigate = useNavigate();
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [monitoring, setMonitoring] = useState<ClusterMonitoringSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(function loadClusters() {
    api.clusters()
      .then(setClusters)
      .catch(error => message.error(error instanceof Error ? error.message : '集群监控加载失败'))
      .finally(() => setLoading(false));

    api.clusterMonitoring()
      .then(setMonitoring)
      .catch(() => setMonitoring([]));
  }, []);

  const monitoringMap = Object.fromEntries(monitoring.map(item => [item.clusterId, item]));

  return (
    <div>
      <PageHeader title="集群监控" subtitle="查看集群关键监控信息，点击进入节点列表" tags={[{ label: 'Prometheus', color: 'blue' }]} />
      <div className="surface data-table">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={clusters}
          pagination={false}
          onRow={record => ({ onClick: () => navigate(`/monitoring/clusters/${record.id}`), style: { cursor: 'pointer' } })}
          columns={[
            { title: '集群名称', render: (_, item) => <strong>{item.name}</strong> },
            { title: '状态', render: (_, item) => <StatusBadge value={item.status} /> },
            { title: 'Kubernetes', render: (_, item) => item.kubernetesVersion || '-' },
            { title: '节点数', render: (_, item) => item.nodeCount },
            { title: 'GPU 数', render: (_, item) => item.gpuCount },
            { title: 'CPU 使用率', render: (_, item) => formatPercent(monitoringMap[item.id]?.cpuUsagePercent ?? null) },
            { title: '内存使用率', render: (_, item) => formatPercent(monitoringMap[item.id]?.memoryUsagePercent ?? null) },
            { title: 'GPU 利用率', render: (_, item) => formatPercent(monitoringMap[item.id]?.gpuUsagePercent ?? null) },
            { title: '操作', render: (_, item) => <Button size="small" onClick={event => { event.stopPropagation(); navigate(`/monitoring/clusters/${item.id}`); }}>查看节点</Button> },
          ]}
        />
      </div>
    </div>
  );
}

export function ClusterMonitoringDetailPage() {
  const navigate = useNavigate();
  const { clusterId } = useParams();
  const [cluster, setCluster] = useState<PhysicalCluster | null>(null);
  const [nodes, setNodes] = useState<Array<{ node: ClusterNode; monitoring: NodeMonitoringDetail | null }>>([]);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<TimeRange>('1h');
  const [density, setDensity] = useState<AxisDensity>('standard');
  const [customRange, setCustomRange] = useState<[Dayjs, Dayjs]>();

  function load() {
    if (!clusterId) return;
    setLoading(true);
    const selectedRange = queryRange(range, customRange);
    Promise.all([api.cluster(clusterId), api.nodes(clusterId)])
      .then(async ([clusterInfo, clusterNodes]) => {
        setCluster(clusterInfo);
        const monitoringList = await Promise.all(clusterNodes.map(async node => {
          try {
            const monitoringDetail = await api.nodeMonitoringDetail(clusterId, node.id, {
              start: selectedRange.start.toISOString(),
              end: selectedRange.end.toISOString(),
              step: selectedRange.step,
            });
            return { node, monitoring: monitoringDetail };
          } catch {
            return { node, monitoring: null };
          }
        }));
        setNodes(monitoringList);
      })
      .catch(error => message.error(error instanceof Error ? error.message : '节点监控加载失败'))
      .finally(() => setLoading(false));
  }

  useEffect(load, [clusterId, range, customRange]);

  const nodeRows = nodes.map(item => ({
    ...item,
    cpu: item.monitoring?.summary.cpuUsagePercent ?? null,
    memory: item.monitoring?.summary.memoryUsagePercent ?? null,
    disk: item.monitoring?.summary.diskUsagePercent ?? null,
    gpu: item.monitoring?.summary.gpuUsagePercent ?? null,
    load: item.monitoring?.summary.loadAverage1m ?? null,
  }));

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/monitoring/clusters')}>返回集群监控</Button>
      <PageHeader
        title={cluster?.name || '集群节点监控'}
        subtitle="列表形式查看节点监控，再点进节点监控页"
        extra={(
          <TimeRangeSelector
            value={range}
            onChange={setRange}
            onCustomChange={(start, end) => setCustomRange([start, end])}
            onRefresh={load}
            density={density}
            onDensityChange={setDensity}
          />
        )}
      />

      <div className="surface data-table">
        <Table
          rowKey={item => item.node.id}
          loading={loading}
          dataSource={nodeRows}
          pagination={false}
          columns={[
            { title: '节点名称', render: (_, item) => <strong>{item.node.name}</strong> },
            { title: '角色', render: (_, item) => <Tag color={isMasterNode(item.node) ? 'blue' : item.node.gpuCount > 0 ? 'gold' : 'green'}>{isMasterNode(item.node) ? 'Master' : item.node.gpuCount > 0 ? 'GPU Worker' : 'Worker'}</Tag> },
            { title: 'IP', render: (_, item) => <code>{item.node.internalIp || '-'}</code> },
            { title: '状态', render: (_, item) => <StatusBadge value={item.node.status} /> },
            { title: 'CPU', render: (_, item) => formatPercent(item.cpu) },
            { title: '内存', render: (_, item) => formatPercent(item.memory) },
            { title: '磁盘', render: (_, item) => formatPercent(item.disk) },
            { title: 'GPU 平均', render: (_, item) => formatPercent(item.gpu) },
            { title: '操作', render: (_, item) => <Button size="small" onClick={() => navigate(`/monitoring/clusters/${clusterId}/nodes/${item.node.id}`)}>进入节点</Button> },
          ]}
        />
      </div>
    </div>
  );
}

export function NodeMonitoringPage() {
  const navigate = useNavigate();
  const { clusterId = '', nodeId = '' } = useParams();
  const [node, setNode] = useState<ClusterNode | null>(null);
  const [cluster, setCluster] = useState<PhysicalCluster | null>(null);
  const [monitoring, setMonitoring] = useState<NodeMonitoringDetail | null>(null);
  const [range, setRange] = useState<TimeRange>('1h');
  const [density, setDensity] = useState<AxisDensity>('standard');
  const [customRange, setCustomRange] = useState<[Dayjs, Dayjs]>();
  const [loading, setLoading] = useState(true);

  function load() {
    if (!clusterId || !nodeId) return;
    setLoading(true);
    const selectedRange = queryRange(range, customRange);
    Promise.all([api.cluster(clusterId), api.nodes(clusterId)])
      .then(async ([clusterInfo, nodes]) => {
        setCluster(clusterInfo);
        const currentNode = nodes.find(item => item.id === nodeId) || null;
        setNode(currentNode);
        const nodeMonitoring = await api.nodeMonitoringDetail(clusterId, nodeId, {
          start: selectedRange.start.toISOString(),
          end: selectedRange.end.toISOString(),
          step: selectedRange.step,
        });
        setMonitoring(nodeMonitoring);
      })
      .catch(error => message.error(error instanceof Error ? error.message : '节点监控加载失败'))
      .finally(() => setLoading(false));
  }

  useEffect(load, [clusterId, nodeId, range, customRange]);

  const selectedRange = queryRange(range, customRange);
  const activeMonitoring = monitoring;
  const summary = activeMonitoring?.summary;
  const gpus = activeMonitoring?.gpus || [];

  if (loading && !monitoring) {
    return <div className="surface" style={{ padding: 32 }}>节点监控加载中...</div>;
  }

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/monitoring/clusters/${clusterId}`)}>返回节点列表</Button>
      <PageHeader
        title={node?.name || '节点监控'}
        subtitle={`${cluster?.name || '-'} · ${node?.internalIp || '-'}`}
        extra={(
          <Space>
            <TimeRangeSelector
              value={range}
              onChange={setRange}
              onCustomChange={(start, end) => setCustomRange([start, end])}
              onRefresh={load}
              density={density}
              onDensityChange={setDensity}
            />
          </Space>
        )}
      />

      <Row gutter={[16, 16]} className="monitor-summary-row">
        <Col span={6}><StatCard label="CPU 平均使用率" value={formatPercent(summary?.cpuUsagePercent ?? null)} accent="#1677ff" /></Col>
        <Col span={6}><StatCard label="内存平均使用率" value={formatPercent(summary?.memoryUsagePercent ?? null)} accent="#722ed1" /></Col>
        <Col span={6}><StatCard label="磁盘平均使用率" value={formatPercent(summary?.diskUsagePercent ?? null)} accent="#fa8c16" /></Col>
        <Col span={6}><StatCard label="GPU 平均利用率" value={formatPercent(summary?.gpuUsagePercent ?? null)} accent="#13c2c2" /></Col>
        <Col span={6}><StatCard label="1m Load" value={formatLoad(summary?.loadAverage1m ?? null)} accent="#eb2f96" /></Col>
        <Col span={6}><StatCard label="网络接收" value={formatMbps(summary?.networkReceiveMbps ?? null)} accent="#13c2c2" /></Col>
        <Col span={6}><StatCard label="网络发送" value={formatMbps(summary?.networkTransmitMbps ?? null)} accent="#13c2c2" /></Col>
        <Col span={6}><StatCard label="GPU 显存已用" value={formatMiB(summary?.gpuMemoryUsedMib ?? null)} accent="#fa541c" /></Col>
      </Row>

      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <div className="toolbar" style={{ marginBottom: 12 }}>
          <div>
            <strong>每张 GPU 的利用率</strong>
            <span style={{ marginLeft: 10, color: '#66756f' }}>仪表盘展示单卡当前值</span>
          </div>
          <Tag color="green">{gpus.length} 张</Tag>
        </div>
        <div className="gpu-gauge-grid">
          {gpus.length > 0
            ? gpus.map(gpu => <GpuGaugeMini key={gpu.gpuIndex} title={gpu.gpuLabel || `GPU${gpu.gpuIndex}`} value={gpu.gpuUsagePercent} />)
            : <div className="node-monitor-empty">暂无 GPU 数据</div>}
        </div>
      </div>

      <div className="surface" style={{ padding: 20 }}>
        <div className="toolbar" style={{ marginBottom: 12 }}>
          <div>
            <strong>监控曲线</strong>
            <span style={{ marginLeft: 10, color: '#66756f' }}>CPU / 内存 / 磁盘 / 网络 / GPU</span>
          </div>
        </div>
        <div className="node-trend-grid">
          <MiniTrendChart title="CPU 使用率" color="#1677ff" series={findSeries(activeMonitoring, 'cpu_usage_percent')} unit="%" density={density} />
          <MiniTrendChart title="内存使用率" color="#722ed1" series={findSeries(activeMonitoring, 'memory_usage_percent')} unit="%" density={density} />
          <MiniTrendChart title="磁盘使用率" color="#fa8c16" series={findSeries(activeMonitoring, 'disk_usage_percent')} unit="%" density={density} />
          <MiniTrendChart title="网络接收" color="#13c2c2" series={findSeries(activeMonitoring, 'network_receive_mbps')} unit="Mbps" density={density} />
          <MiniTrendChart title="网络发送" color="#13c2c2" series={findSeries(activeMonitoring, 'network_transmit_mbps')} unit="Mbps" density={density} />
          <MiniTrendChart title="1m Load" color="#eb2f96" series={findSeries(activeMonitoring, 'load_average_1m')} unit="load" density={density} />
          <MiniTrendChart title="GPU 平均利用率" color="#08979c" series={findSeries(activeMonitoring, 'gpu_usage_percent')} unit="%" density={density} />
          <MiniTrendChart title="GPU 显存已用" color="#fa541c" series={findSeries(activeMonitoring, 'gpu_memory_used_mib')} unit="MiB" density={density} />
        </div>
      </div>
    </div>
  );
}

function GpuGaugeMini(props: { title: string; value: number | null }) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);
    chart.setOption({
      series: [{
        type: 'gauge',
        startAngle: 210,
        endAngle: -30,
        min: 0,
        max: 100,
        radius: '88%',
        progress: { show: true, roundCap: true, width: 8 },
        axisLine: { lineStyle: { width: 8, color: [[0.6, '#52c41a'], [0.85, '#faad14'], [1, '#ff4d4f']] } },
        axisTick: { show: false },
        splitLine: { length: 6, lineStyle: { width: 1, color: '#d9d9d9' } },
        axisLabel: { show: false },
        pointer: { width: 3 },
        detail: { formatter: '{value}%', fontSize: 14, offsetCenter: [0, '60%'] },
        title: { offsetCenter: [0, '88%'], fontSize: 11, color: '#66756f' },
        data: [{ value: props.value ?? 0, name: props.title }],
      }],
    });
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [props.title, props.value]);

  return (
    <div className="gpu-gauge-mini-card">
      <div className="gpu-gauge-mini-title">{props.title}</div>
      <div ref={ref} className="gpu-gauge-mini-chart" />
    </div>
  );
}

function MiniTrendChart(props: {
  title: string;
  color: string;
  series: MonitoringSeries | undefined;
  unit: string;
  density: AxisDensity;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);
    const labels = props.series?.points.map(point => dayjs.unix(point.timestamp).format('HH:mm')) || [];
    const values = props.series?.points
      .map(point => point.value)
      .filter((value): value is number => Number.isFinite(value)) || [];
    const minimumValue = values.length > 0 ? Math.min(...values) : 0;
    const maximumValue = values.length > 0 ? Math.max(...values) : 1;
    const valueSpread = maximumValue - minimumValue;
    // 监控曲线采用局部动态范围，避免 CPU、磁盘等稳定指标被固定 0 起点压成直线。
    const axisPadding = Math.max(
      valueSpread * 0.25,
      props.unit === '%' ? 1.5 : Math.max(Math.abs(maximumValue) * 0.08, 0.05),
    );
    let axisMinimum = minimumValue - axisPadding;
    let axisMaximum = maximumValue + axisPadding;
    if (props.unit === '%') {
      axisMinimum = Math.max(0, axisMinimum);
      axisMaximum = Math.min(100, axisMaximum);
    } else {
      axisMinimum = Math.max(0, axisMinimum);
    }
    if (axisMaximum <= axisMinimum) {
      axisMaximum = axisMinimum + (props.unit === '%' ? 3 : 1);
    }
    const targetLabelCount = props.density === 'compact' ? 5 : props.density === 'wide' ? 9 : 7;
    const xAxisInterval = labels.length > targetLabelCount
      ? Math.max(0, Math.ceil(labels.length / targetLabelCount) - 1)
      : 0;
    const formatAxisValue = (value: number) => {
      if (props.unit === '%') return `${value.toFixed(valueSpread < 5 ? 1 : 0)}%`;
      if (props.unit === 'Mbps' || props.unit === 'load') {
        return value < 10 ? value.toFixed(2) : value.toFixed(1);
      }
      return value >= 100 ? value.toFixed(0) : value.toFixed(1);
    };
    chart.setOption({
      animation: false,
      grid: { left: 16, right: 22, top: 18, bottom: 12, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(20, 31, 27, 0.92)',
        borderWidth: 0,
        textStyle: { color: '#fff' },
        valueFormatter: (value: number) => `${formatAxisValue(value)} ${props.unit === '%' ? '' : props.unit}`.trim(),
      },
      xAxis: {
        type: 'category',
        data: labels,
        boundaryGap: false,
        axisLabel: {
          color: '#8c8c8c',
          interval: xAxisInterval,
          hideOverlap: true,
          margin: 12,
        },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: '#d9d9d9' } },
      },
      yAxis: {
        type: 'value',
        min: values.length > 0 ? axisMinimum : 0,
        max: values.length > 0 ? axisMaximum : props.unit === '%' ? 100 : 1,
        scale: true,
        splitNumber: 4,
        axisLabel: {
          color: '#8c8c8c',
          formatter: formatAxisValue,
          margin: 12,
        },
        axisTick: { show: false },
        axisLine: { show: false },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
      },
      series: [{
        type: 'line',
        smooth: true,
        showSymbol: false,
        connectNulls: true,
        lineStyle: { width: 2.5, color: props.color },
        itemStyle: { color: props.color },
        emphasis: { focus: 'series' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: `${props.color}38` },
            { offset: 1, color: `${props.color}05` },
          ]),
        },
        data: props.series?.points.map(point => point.value) || [],
      }],
      graphic: values.length === 0 ? [{
        type: 'text',
        left: 'center',
        top: 'middle',
        style: { text: '暂无监控数据', fill: '#bfbfbf', fontSize: 13 },
      }] : undefined,
    });
    // 监听图表容器而不只是 window，保证侧栏和两列布局变化时画布始终铺满卡片。
    const resizeObserver = new ResizeObserver(() => chart.resize());
    resizeObserver.observe(ref.current);
    return () => {
      resizeObserver.disconnect();
      chart.dispose();
    };
  }, [props.color, props.density, props.series, props.unit]);

  return (
    <div className="mini-trend-card">
      <div className="mini-trend-title">{props.title}</div>
      <div ref={ref} className="mini-trend-chart" />
    </div>
  );
}
