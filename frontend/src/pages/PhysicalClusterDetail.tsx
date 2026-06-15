import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Tabs, Card, Descriptions, Tag, Typography, Spin, Table, Space, Button, Empty,
} from 'antd';
import {
  ArrowLeftOutlined, CloudServerOutlined,
} from '@ant-design/icons';
import { physicalClusterApi } from '../api/physicalClusters';
import type { PhysicalCluster, PhysicalClusterCapacity, ClusterNodeInfo } from '../types';

const { Title, Text } = Typography;

const PhysicalClusterDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [cluster, setCluster] = useState<PhysicalCluster | null>(null);
  const [capacity, setCapacity] = useState<PhysicalClusterCapacity | null>(null);
  const [nodes, setNodes] = useState<ClusterNodeInfo[]>([]);
  const [totalNodes, setTotalNodes] = useState(0);
  const [readyNodes, setReadyNodes] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    (async () => {
      try {
        // We don't have a single GET endpoint for cluster detail, use list and filter
        const listRes = await physicalClusterApi.list();
        const found = listRes.data.find((c) => c.id === id);
        if (found) setCluster(found);

        const [capRes, nodesRes] = await Promise.all([
          physicalClusterApi.capacity(id),
          physicalClusterApi.nodes(id),
        ]);
        setCapacity(capRes.data);
        setNodes(nodesRes.data.nodes || []);
        setTotalNodes(nodesRes.data.totalNodes);
        setReadyNodes(nodesRes.data.readyNodes);
      } catch { /* handled */ }
      finally { setLoading(false); }
    })();
  }, [id]);

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 120 }} />;
  if (!cluster) return <Empty description="集群不存在" />;

  const gpuResourceKey = cluster.gpuTypes === 'HYGON' ? 'amd.com/dcu'
    : cluster.gpuTypes === 'HUAWEI_ASCEND' ? 'huawei.com/ascend910'
    : 'nvidia.com/gpu';

  const nodeColumns = [
    { title: '节点名称', dataIndex: 'name', key: 'name', render: (v: string) => <Text code>{v}</Text> },
    {
      title: '状态', key: 'status', width: 80,
      render: (_: unknown, record: ClusterNodeInfo) => {
        const ready = record.conditions?.some((c) => c.type === 'Ready' && c.status === 'True');
        return <Tag color={ready ? 'green' : 'red'}>{ready ? '就绪' : '未就绪'}</Tag>;
      },
    },
    {
      title: 'GPU', key: 'gpu', width: 100,
      render: (_: unknown, record: ClusterNodeInfo) =>
        record.allocatable?.[gpuResourceKey] || '-',
    },
    {
      title: 'CPU', key: 'cpu', width: 80,
      render: (_: unknown, record: ClusterNodeInfo) => record.allocatable?.cpu || '-',
    },
    {
      title: '内存', key: 'memory', width: 120,
      render: (_: unknown, record: ClusterNodeInfo) => record.allocatable?.memory || '-',
    },
    {
      title: '标签', dataIndex: 'labels', key: 'labels', ellipsis: true,
      render: (labels: Record<string, string>) =>
        labels ? Object.entries(labels).map(([k, v]) => (
          <Tag key={k}>{k}={v}</Tag>
        )) : '-',
    },
  ];

  const overviewTab = (
    <div>
      <Card title={<Title level={5} style={{ margin: 0 }}>集群基本信息</Title>} style={{ borderRadius: 10, marginBottom: 16 }}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label="集群 ID"><Text code>{cluster.id}</Text></Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={cluster.status === 'active' ? 'green' : 'red'}>
              {cluster.status === 'active' ? '正常' : '停用'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="GPU 类型">{cluster.gpuTypes}</Descriptions.Item>
          <Descriptions.Item label="位置">{cluster.location}</Descriptions.Item>
          <Descriptions.Item label="GPU 总槽位">{cluster.totalGpuSlots}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{cluster.createdAt}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>{cluster.description || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {capacity && (
        <Card title="实时容量" style={{ borderRadius: 10, marginBottom: 16 }}>
          <Descriptions column={3} bordered size="small">
            <Descriptions.Item label="GPU 槽位">{capacity.gpuSlots}</Descriptions.Item>
            <Descriptions.Item label="CPU 核心">{capacity.cpu}</Descriptions.Item>
            <Descriptions.Item label="内存 (bytes)">{capacity.memory}</Descriptions.Item>
          </Descriptions>
        </Card>
      )}
    </div>
  );

  const nodesTab = (
    <Card
      title={
        <Space>
          <CloudServerOutlined />
          <span>节点列表</span>
          <Tag color="blue">共 {totalNodes} 个</Tag>
          <Tag color="green">{readyNodes} 个就绪</Tag>
        </Space>
      }
      style={{ borderRadius: 10 }}
    >
      <Table
        columns={nodeColumns}
        dataSource={nodes}
        rowKey="name"
        pagination={false}
        size="small"
      />
    </Card>
  );

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/physical-clusters')}>
          返回列表
        </Button>
      </Space>

      <Card title={<Title level={4} style={{ margin: 0 }}>{cluster.name}</Title>} style={{ borderRadius: 10, marginBottom: 16 }}>
        <Tabs
          items={[
            { key: 'overview', label: '概览', children: overviewTab },
            { key: 'nodes', label: '节点列表', children: nodesTab },
          ]}
        />
      </Card>
    </div>
  );
};

export default PhysicalClusterDetailPage;
