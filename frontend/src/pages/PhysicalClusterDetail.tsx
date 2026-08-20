import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Tabs, Table, Tag, Spin, Empty, Button, Space, Statistic, Row, Col, Descriptions, Progress, message } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { clustersApi } from '../api/clusters';
import { mockGpus, mockGpuSplits, mockNodes } from '../mock/data';
import type { ClusterNode, ClusterGpu, ClusterGpuSplit, ScanResult } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const BRAND_COLORS: Record<string, string> = { NVIDIA: 'green', HYGON: 'purple', HUAWEI_ASCEND: 'magenta' };

export default function PhysicalClusterDetailPage() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [gpus, setGpus] = useState<ClusterGpu[]>([]);
  const [splits, setSplits] = useState<ClusterGpuSplit[]>([]);
  const [capacity, setCapacity] = useState<{ gpuSlots: number; cpu: string; memory: string } | null>(null);
  const [scanning, setScanning] = useState(false);
  const [loading, setLoading] = useState(true);
  const [scanResult, setScanResult] = useState<ScanResult | null>(null);

  const load = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [n, g, s, c] = await Promise.all([
        clustersApi.nodes(id),
        clustersApi.gpus(id),
        clustersApi.gpuSplits(id),
        clustersApi.capacity(id),
      ]);
      setNodes(n); setGpus(g); setSplits(s); setCapacity(c);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [id]);

  const handleScan = async () => {
    setScanning(true);
    try {
      const r = await clustersApi.scan(id!);
      setScanResult(r);
      message.success(`扫描完成：${r.nodeCount} 节点, ${r.gpuModelCount} GPU 型号, ${r.splitCount} 切分`);
    } catch (e: any) { message.error(e?.message || '扫描失败'); }
    finally { setScanning(false); }
  };

  if (loading) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;

  return (
    <div>
      <PageHeader
        title={id!}
        subtitle="物理集群详情：节点 + GPU + HAMi 切分 + 容量"
        tags={[{ label: 'active', color: 'green' }]}
        extra={
          <Space>
            <Button type="primary" icon={<ReloadOutlined />} onClick={handleScan} loading={scanning}
              style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
              Scan
            </Button>
            <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/clusters')}>返回</Button>
          </Space>
        }
      />
      {scanResult && (
        <Card style={{ marginBottom: 16, background: PSBC_COLORS.primaryLight, borderColor: PSBC_COLORS.primary }}>
          <Row gutter={16}>
            <Col span={6}><Statistic title="节点" value={scanResult.nodeCount} /></Col>
            <Col span={6}><Statistic title="GPU 型号" value={scanResult.gpuModelCount} /></Col>
            <Col span={6}><Statistic title="HAMi 切分" value={scanResult.splitCount} /></Col>
            <Col span={6}><Statistic title="扫描时间" value={scanResult.scannedAt?.slice(11, 19)} /></Col>
          </Row>
        </Card>
      )}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}><Card><Statistic title="GPU 卡总数" value={capacity?.gpuSlots ?? 0} valueStyle={{ color: PSBC_COLORS.primary }} /></Card></Col>
        <Col span={8}><Card><Statistic title="CPU" value={capacity?.cpu ?? 0} /></Card></Col>
        <Col span={8}><Card><Statistic title="内存" value={capacity?.memory ?? 0} /></Card></Col>
      </Row>

      <Tabs
        defaultActiveKey="nodes"
        items={[
          {
            key: 'nodes', label: `节点 (${nodes.length})`,
            children: (
              <Card style={{ borderRadius: 8 }}>
                <Table dataSource={nodes} rowKey="name" pagination={false} size="small" columns={[
                  { title: '节点名', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
                  { title: 'CPU', dataIndex: 'allocatable', width: 80, render: (a) => a?.cpu ?? '-' },
                  { title: '内存', dataIndex: 'allocatable', width: 100, render: (a) => a?.memory ?? '-' },
                  { title: 'NVIDIA', dataIndex: 'allocatable', width: 90, render: (a) => a?.['nvidia.com/gpu'] ?? '-' },
                  { title: 'DCU', dataIndex: 'allocatable', width: 70, render: (a) => a?.['amd.com/dcu'] ?? '-' },
                  { title: '状态', dataIndex: 'status', width: 80, render: (v) => <Tag color="green">{v}</Tag> },
                ]} />
              </Card>
            ),
          },
          {
            key: 'gpus', label: `GPU 型号 (${gpus.length})`,
            children: (
              <Card style={{ borderRadius: 8 }}>
                <Table dataSource={gpus} rowKey="model" pagination={false} size="small" columns={[
                  { title: '型号', dataIndex: 'model' },
                  { title: '显存', dataIndex: 'memoryMb', render: (v) => formatGpuMemory(v) },
                  { title: '节点数', dataIndex: 'nodeCount', width: 100 },
                  { title: '总卡数', dataIndex: 'totalCards', width: 100, render: (v) => <Tag color="green">{v}</Tag> },
                  { title: '所在节点', dataIndex: 'nodeNames', render: (v) => v.map((n: string) => <Tag key={n}>{n}</Tag>) },
                ]} />
              </Card>
            ),
          },
          {
            key: 'splits', label: `HAMi 切分 (${splits.length})`,
            children: (
              <Card style={{ borderRadius: 8 }}>
                <Table dataSource={splits} rowKey="poolLabel" pagination={false} size="small" columns={[
                  { title: '标签', dataIndex: 'poolLabel', render: (v) => <code className="mono">{v}</code> },
                  { title: '显存', dataIndex: 'memMb', render: (v) => formatGpuMemory(v) },
                  { title: '算力 %', dataIndex: 'coresPct', render: (v) => `${v}%` },
                  { title: '节点数', dataIndex: 'nodeCount', width: 100 },
                  { title: '所在节点', dataIndex: 'nodeNames', render: (v) => v.map((n: string) => <Tag key={n}>{n}</Tag>) },
                ]} />
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}

function formatGpuMemory(memoryMb: number | null | undefined) {
  if (memoryMb == null || memoryMb <= 0) return '-';
  return `${(memoryMb / 1024).toFixed(2)} GB`;
}
