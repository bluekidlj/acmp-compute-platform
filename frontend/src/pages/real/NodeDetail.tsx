import { useEffect, useMemo, useState } from 'react';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Collapse, Descriptions, message, Modal, Space, Spin, Table, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ClusterNode, GpuBrand, GpuDevice, PhysicalCluster } from '../../types';

const BRAND_LABELS: Record<GpuBrand, string> = {
  NVIDIA: '英伟达',
  HYGON: '海光',
  HUAWEI_ASCEND: '华为昇腾',
};

export default function NodeDetailPage() {
  const { clusterId = '', nodeId = '' } = useParams();
  const navigate = useNavigate();
  const [cluster, setCluster] = useState<PhysicalCluster | null>(null);
  const [node, setNode] = useState<ClusterNode | null>(null);
  const [gpus, setGpus] = useState<GpuDevice[]>([]);
  const [selectedGpu, setSelectedGpu] = useState<GpuDevice | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(function load() {
    setLoading(true);
    Promise.all([api.cluster(clusterId), api.nodes(clusterId), api.nodeGpus(clusterId, nodeId)])
      .then(function setAll(values) {
        setCluster(values[0]);
        setNode(values[1].find((item) => item.id === nodeId) || null);
        setGpus(values[2]);
      })
      .catch(function handleError(exception) {
        message.error(exception instanceof Error ? exception.message : 'Node 详情加载失败');
      })
      .finally(function finish() {
        setLoading(false);
      });
  }, [clusterId, nodeId]);

  const role = useMemo(function nodeRole() {
    if (!node) return '-';
    if (isMaster(node)) return 'Master';
    return node.gpuCount > 0 ? 'GPU Worker' : 'Worker';
  }, [node]);

  if (loading) {
    return <Spin size="large" />;
  }
  if (!node) {
    return <div className="surface" style={{ padding: 32 }}>该集群中不存在此 Node。</div>;
  }

  return (
    <div>
      <div className="page-heading">
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={function back() { navigate(`/clusters/${clusterId}`); }} style={{ padding: 0 }}>
            返回 Node 拓扑
          </Button>
          <h1>{node.name}</h1>
          <p>{cluster?.name} · {role} · {node.internalIp || 'Internal IP 待同步'}</p>
        </div>
        <StatusBadge value={node.status} />
      </div>

      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <Descriptions column={3} size="small">
          <Descriptions.Item label="节点角色">{role}</Descriptions.Item>
          <Descriptions.Item label="Internal IP">{node.internalIp || '-'}</Descriptions.Item>
          <Descriptions.Item label="状态"><StatusBadge value={node.status} /></Descriptions.Item>
          <Descriptions.Item label="CPU">{node.cpuCores} Core</Descriptions.Item>
          <Descriptions.Item label="内存">{formatMemory(node.memoryBytes)}</Descriptions.Item>
          <Descriptions.Item label="GPU设备">{node.gpuCount}</Descriptions.Item>
          <Descriptions.Item label="最近同步" span={3}>
            {node.lastSyncAt ? new Date(node.lastSyncAt).toLocaleString('zh-CN') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="节点配置" span={3}>
            <NodeMetadata labelsJson={node.labelsJson} taintsJson={node.taintsJson} />
          </Descriptions.Item>
        </Descriptions>
      </div>

      <div className="surface data-table">
        <div className="toolbar">
          <div>
            <strong>GPU 设备</strong>
            <span style={{ marginLeft: 10, color: '#66756f' }}>仅展示当前 Node 的真实设备</span>
          </div>
          <Tag color="green">{gpus.length} 张</Tag>
        </div>
        <Table
          rowKey="id"
          dataSource={gpus}
          pagination={false}
          locale={{ emptyText: '该 Node 没有发现 GPU 设备' }}
          columns={[
            { title: '编号', dataIndex: 'gpuIndex', width: 70 },
            {
              title: '品牌',
              dataIndex: 'gpuBrand',
              render: function render(value: GpuBrand | null) {
                return value ? <Tag>{BRAND_LABELS[value]}</Tag> : <Tag>待识别</Tag>;
              },
            },
            { title: '型号', dataIndex: 'gpuModel', render: function render(value) { return value || '-'; } },
            { title: '显存', dataIndex: 'memoryMb', render: function render(value) { return value ? `${value} MiB` : '未上报'; } },
            { title: '状态', dataIndex: 'status', render: function render(value) { return <StatusBadge value={value} />; } },
            { title: '资源池', dataIndex: 'resourcePoolId', render: function render(value) { return value ? <Tag>{value}</Tag> : '未归池'; } },
            {
              title: '算力规格',
              render: function render(_, item) {
                return item.computeSpecDisplayName || item.computeSpecName || item.computeSpecId || '-';
              },
            },
            { title: '使用', dataIndex: 'usageStatus', render: function render(value) { return <StatusBadge value={value} />; } },
            {
              title: '操作',
              width: 90,
              render: function render(_, item) {
                return <Button size="small" onClick={function openDetail() { setSelectedGpu(item); }}>详情</Button>;
              },
            },
          ]}
        />
      </div>

      <Modal
        title="GPU 设备详情"
        open={!!selectedGpu}
        onCancel={function close() { setSelectedGpu(null); }}
        footer={null}
        width={680}
      >
        {selectedGpu ? <GpuDetail gpu={selectedGpu} /> : null}
      </Modal>
    </div>
  );
}

function GpuDetail({ gpu }: { gpu: GpuDevice }) {
  return (
    <Descriptions column={2} size="small" bordered>
      <Descriptions.Item label="编号">{gpu.gpuIndex}</Descriptions.Item>
      <Descriptions.Item label="品牌">{gpu.gpuBrand ? BRAND_LABELS[gpu.gpuBrand] : '待识别'}</Descriptions.Item>
      <Descriptions.Item label="型号">{gpu.gpuModel || '未上报'}</Descriptions.Item>
      <Descriptions.Item label="显存">{gpu.memoryMb ? `${gpu.memoryMb} MiB` : '未上报'}</Descriptions.Item>
      <Descriptions.Item label="Driver">{gpu.driverVersion || '未上报'}</Descriptions.Item>
      <Descriptions.Item label="CUDA">{gpu.cudaVersion || '未上报'}</Descriptions.Item>
      <Descriptions.Item label="资源池">{gpu.resourcePoolId || '未归池'}</Descriptions.Item>
      <Descriptions.Item label="算力规格">
        {gpu.computeSpecDisplayName || gpu.computeSpecName || gpu.computeSpecId || '未绑定'}
      </Descriptions.Item>
      <Descriptions.Item label="设备 UUID" span={2}>{gpu.uuid || '未上报'}</Descriptions.Item>
      <Descriptions.Item label="最近同步" span={2}>
        {gpu.lastSyncAt ? new Date(gpu.lastSyncAt).toLocaleString('zh-CN') : '-'}
      </Descriptions.Item>
    </Descriptions>
  );
}

function NodeMetadata({ labelsJson, taintsJson }: {
  labelsJson: string | null;
  taintsJson: string | null;
}) {
  const labels = parseObject(labelsJson);
  const taints = parseArray(taintsJson);
  return (
    <Collapse
      size="small"
      style={{ width: '100%' }}
      items={[
        {
          key: 'labels',
          label: `Labels（${Object.keys(labels).length}）`,
          children: (
            <Space size={[6, 8]} wrap>
              {Object.entries(labels).map(([key, value]) => (
                <Tag key={key} color="green">{key}{value ? `=${value}` : ''}</Tag>
              ))}
            </Space>
          ),
        },
        {
          key: 'taints',
          label: `Taints（${taints.length}）`,
          children: taints.length === 0 ? '无' : (
            <Space size={[6, 8]} wrap>
              {taints.map((taint, index) => (
                <Tag key={`${taint.key}-${index}`} color="orange">
                  {taint.key}{taint.value ? `=${taint.value}` : ''}{taint.effect ? `:${taint.effect}` : ''}
                </Tag>
              ))}
            </Space>
          ),
        },
      ]}
    />
  );
}

function formatMemory(bytes: number) {
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GiB`;
}

function isMaster(node: ClusterNode) {
  if (!node.labelsJson) return false;
  try {
    const labels = JSON.parse(node.labelsJson) as Record<string, string>;
    return Object.prototype.hasOwnProperty.call(labels, 'node-role.kubernetes.io/control-plane')
      || Object.prototype.hasOwnProperty.call(labels, 'node-role.kubernetes.io/master');
  } catch {
    return false;
  }
}

function parseObject(value: string | null): Record<string, string> {
  try {
    return value ? JSON.parse(value) as Record<string, string> : {};
  } catch {
    return {};
  }
}

function parseArray(value: string | null): Array<{ key: string; value?: string; effect?: string }> {
  try {
    return value ? JSON.parse(value) as Array<{ key: string; value?: string; effect?: string }> : [];
  } catch {
    return [];
  }
}
