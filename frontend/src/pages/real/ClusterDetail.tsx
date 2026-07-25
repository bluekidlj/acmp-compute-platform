import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Descriptions, message, Spin, Table, Tabs, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ClusterNode, PhysicalCluster } from '../../types';

export default function ClusterDetailPage() {
  const { clusterId = '' } = useParams();
  const navigate = useNavigate();
  const [cluster, setCluster] = useState<PhysicalCluster | null>(null);
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [loading, setLoading] = useState(true);

  function load() {
    setLoading(true);
    Promise.all([api.cluster(clusterId), api.nodes(clusterId)])
      .then(function setAll(values) {
        setCluster(values[0]);
        setNodes(values[1]);
      })
      .catch(function handleError(exception) {
        message.error(exception instanceof Error ? exception.message : '集群详情加载失败');
      })
      .finally(function finish() {
        setLoading(false);
      });
  }

  useEffect(load, [clusterId]);

  async function sync() {
    try {
      await api.syncCluster(clusterId);
      message.success('同步完成');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '同步失败');
    }
  }

  if (loading || !cluster) {
    return <Spin size="large" />;
  }

  return (
    <div>
      <div className="page-heading">
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={function back() { navigate('/clusters'); }} style={{ padding: 0 }}>
            返回集群
          </Button>
          <h1>{cluster.name}</h1>
          <p>Kubernetes API 实际发现的 Node 列表</p>
        </div>
        <Button type="primary" icon={<ReloadOutlined />} onClick={sync}>立即同步</Button>
      </div>

      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <Descriptions column={4} size="small">
          <Descriptions.Item label="状态"><StatusBadge value={cluster.status} /></Descriptions.Item>
          <Descriptions.Item label="Kubernetes">{cluster.kubernetesVersion || '同步后获取'}</Descriptions.Item>
          <Descriptions.Item label="节点数">{cluster.nodeCount}</Descriptions.Item>
          <Descriptions.Item label="GPU设备数">{cluster.gpuCount}</Descriptions.Item>
          <Descriptions.Item label="最近同步" span={4}>
            {cluster.lastSyncAt ? new Date(cluster.lastSyncAt).toLocaleString('zh-CN') : '-'}
          </Descriptions.Item>
        </Descriptions>
      </div>

      <div className="surface data-table">
        <Tabs
          defaultActiveKey="list"
          tabBarStyle={{ padding: '0 16px', margin: 0 }}
          items={[
            {
              key: 'list',
              label: `Node 列表 (${nodes.length})`,
              children: (
                <Table
                  rowKey="id"
                  dataSource={nodes}
                  pagination={false}
                  onRow={function rowNavigation(node) {
                    return {
                      onClick: function openNode() {
                        navigate(`/clusters/${clusterId}/nodes/${node.id}`);
                      },
                      style: { cursor: 'pointer' },
                    };
                  }}
                  columns={[
            { title: '节点名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
            {
              title: '角色',
              render: function renderRole(_, node: ClusterNode) {
                const role = isMaster(node) ? 'Master' : node.gpuCount > 0 ? 'GPU Worker' : 'Worker';
                return <Tag color={isMaster(node) ? 'blue' : node.gpuCount > 0 ? 'gold' : 'green'}>{role}</Tag>;
              },
            },
            { title: 'Internal IP', dataIndex: 'internalIp', render: function render(value) { return <code>{value || '-'}</code>; } },
            { title: '状态', dataIndex: 'status', render: function render(value) { return <StatusBadge value={value} />; } },
            { title: 'CPU Core', dataIndex: 'cpuCores', width: 110 },
            {
              title: '内存',
              dataIndex: 'memoryBytes',
              render: function render(value: number) { return `${(value / 1024 / 1024 / 1024).toFixed(2)} GiB`; },
            },
            { title: 'GPU设备数', dataIndex: 'gpuCount', width: 110 },
            {
              title: '操作',
              width: 100,
              render: function renderAction(_, node: ClusterNode) {
                return (
                  <Button
                    size="small"
                    onClick={function open(event) {
                      event.stopPropagation();
                      navigate(`/clusters/${clusterId}/nodes/${node.id}`);
                    }}
                  >
                    详情
                  </Button>
                );
              },
            },
                  ]}
                />
              ),
            },
            {
              key: 'topology',
              label: '拓扑图',
              children: (
                <NodeTopology
                  nodes={nodes}
                  onOpen={function openNode(node) {
                    navigate(`/clusters/${clusterId}/nodes/${node.id}`);
                  }}
                />
              ),
            },
          ]}
        />
      </div>
    </div>
  );
}

function NodeTopology({ nodes, onOpen }: { nodes: ClusterNode[]; onOpen: (node: ClusterNode) => void }) {
  const masters = nodes.filter(isMaster);
  const workers = nodes.filter((node) => !isMaster(node));
  return (
    <div className="topology-stage">
      {masters.length > 0 && (
        <div className="topology-tier">
          <div className="topology-tier-label">MASTER</div>
          <div className="topology-node-grid">
            {masters.map((node) => <ServerNode key={node.id} node={node} role="Master" onOpen={onOpen} />)}
          </div>
        </div>
      )}
      {masters.length > 0 && workers.length > 0 && <div className="topology-connector"><span /></div>}
      {workers.length > 0 && (
        <div className="topology-tier">
          <div className="topology-tier-label">WORKER</div>
          <div className="topology-node-grid">
            {workers.map((node) => (
              <ServerNode key={node.id} node={node} role={node.gpuCount > 0 ? 'GPU Worker' : 'Worker'} onOpen={onOpen} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function ServerNode({ node, role, onOpen }: {
  node: ClusterNode;
  role: string;
  onOpen: (node: ClusterNode) => void;
}) {
  const ready = node.status === 'READY';
  return (
    <button
      type="button"
      className={`server-node ${ready ? 'ready' : 'offline'} ${node.gpuCount > 0 ? 'gpu-node' : ''}`}
      onClick={function open() { onOpen(node); }}
    >
      <span className="server-node-lights"><i /><i /><i /></span>
      <span className="server-node-role">{role}</span>
      <strong>{node.name}</strong>
      <span className="server-node-ip">{node.internalIp || 'Internal IP 待同步'}</span>
      <span className="server-node-metrics">
        <span className={`node-state-dot ${ready ? 'ready' : 'offline'}`} />
        {node.status}
        <b>GPU {node.gpuCount}</b>
      </span>
      <span className="server-node-action">查看节点详情 →</span>
    </button>
  );
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
