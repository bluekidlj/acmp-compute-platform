import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Descriptions, message, Space, Spin, Table, Tabs, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ClusterNode, GpuDevice, PhysicalCluster } from '../../types';

export default function ClusterDetailPage() {
  const { clusterId = '' } = useParams();
  const navigate = useNavigate();
  const [cluster, setCluster] = useState<PhysicalCluster | null>(null);
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [gpus, setGpus] = useState<GpuDevice[]>([]);
  const [loading, setLoading] = useState(true);

  function load() {
    setLoading(true);
    Promise.all([api.cluster(clusterId), api.nodes(clusterId), api.gpus(clusterId)])
      .then(function setAll(values) {
        setCluster(values[0]);
        setNodes(values[1]);
        setGpus(values[2]);
      })
      .catch(function handleError(exception) {
        message.error(exception.message);
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
          <p>{cluster.description || 'Kubernetes 集群资源清单'}</p>
        </div>
        <Button type="primary" icon={<ReloadOutlined />} onClick={sync}>立即同步</Button>
      </div>

      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <Descriptions column={4} size="small">
          <Descriptions.Item label="状态"><StatusBadge value={cluster.status} /></Descriptions.Item>
          <Descriptions.Item label="Kubernetes">{cluster.kubernetesVersion || '-'}</Descriptions.Item>
          <Descriptions.Item label="Node">{cluster.nodeCount}</Descriptions.Item>
          <Descriptions.Item label="Gpu">{cluster.gpuCount}</Descriptions.Item>
          <Descriptions.Item label="最近同步" span={2}>{cluster.lastSyncAt ? new Date(cluster.lastSyncAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
          <Descriptions.Item label="同步信息" span={2}>{cluster.syncMessage || '-'}</Descriptions.Item>
        </Descriptions>
      </div>

      <div className="surface data-table">
        <Tabs
          defaultActiveKey="nodes"
          tabBarStyle={{ padding: '0 16px', margin: 0 }}
          items={[
            {
              key: 'nodes',
              label: `Node (${nodes.length})`,
              children: (
                <Table
                  rowKey="id"
                  dataSource={nodes}
                  pagination={false}
                  columns={[
                    { title: '节点名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
                    { title: 'CPU Core', dataIndex: 'cpuCores', width: 110 },
                    {
                      title: '内存',
                      dataIndex: 'memoryBytes',
                      width: 130,
                      render: function render(value: number) { return `${(value / 1024 / 1024 / 1024).toFixed(2)} GiB`; },
                    },
                    { title: 'Gpu', dataIndex: 'gpuCount', width: 80 },
                    { title: '状态', dataIndex: 'status', width: 110, render: function render(value) { return <StatusBadge value={value} />; } },
                    { title: 'Labels', dataIndex: 'labelsJson', ellipsis: true, render: function render(value) { return <code>{value || '{}'}</code>; } },
                    { title: 'Taints', dataIndex: 'taintsJson', ellipsis: true, render: function render(value) { return <code>{value || '[]'}</code>; } },
                  ]}
                />
              ),
            },
            {
              key: 'gpus',
              label: `Gpu (${gpus.length})`,
              children: (
                <Table
                  rowKey="id"
                  dataSource={gpus}
                  pagination={false}
                  columns={[
                    { title: '编号', dataIndex: 'gpuIndex', width: 70 },
                    { title: 'Node', dataIndex: 'nodeName' },
                    { title: '型号', dataIndex: 'gpuModel', render: function render(value) { return value || '-'; } },
                    { title: '显存', dataIndex: 'memoryMb', render: function render(value) { return value ? `${value} MiB` : '-'; } },
                    { title: 'Driver', dataIndex: 'driverVersion', render: function render(value) { return value || '-'; } },
                    { title: 'CUDA', dataIndex: 'cudaVersion', render: function render(value) { return value || '-'; } },
                    { title: '状态', dataIndex: 'status', render: function render(value) { return <StatusBadge value={value} />; } },
                    { title: '资源池', dataIndex: 'resourcePoolId', render: function render(value) { return value ? <Tag>{value}</Tag> : '未归池'; } },
                    { title: '使用', dataIndex: 'usageStatus', render: function render(value) { return <StatusBadge value={value} />; } },
                  ]}
                />
              ),
            },
          ]}
        />
      </div>
    </div>
  );
}
