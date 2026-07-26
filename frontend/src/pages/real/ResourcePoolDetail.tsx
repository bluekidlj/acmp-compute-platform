import { useEffect, useState } from 'react';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Descriptions, Empty, message, Space, Spin, Table, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ClusterNode, GpuBrand, ResourcePool } from '../../types';

const BRAND_LABELS: Record<GpuBrand, string> = {
  NVIDIA: '英伟达',
  HYGON: '海光',
  HUAWEI_ASCEND: '华为',
};

export default function ResourcePoolDetailPage() {
  const { poolId = '' } = useParams();
  const navigate = useNavigate();
  const [pool, setPool] = useState<ResourcePool | null>(null);
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(function load() {
    setLoading(true);
    Promise.all([api.pool(poolId), api.poolNodes(poolId)])
      .then(function apply(values) {
        setPool(values[0]);
        setNodes(values[1]);
      })
      .catch(function handleError(exception) {
        message.error(exception instanceof Error ? exception.message : '资源池详情加载失败');
      })
      .finally(function finish() {
        setLoading(false);
      });
  }, [poolId]);

  if (loading) {
    return <Spin size="large" />;
  }
  if (!pool) {
    return <div className="surface" style={{ padding: 32 }}><Empty description="资源池不存在" /></div>;
  }

  return (
    <div>
      <div className="page-heading">
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={function back() { navigate('/resource-pools'); }} style={{ padding: 0 }}>
            返回资源池
          </Button>
          <h1>{pool.name}</h1>
          <p>{pool.poolType === 'EXCLUSIVE' ? '整卡独占资源池' : 'HAMi 虚拟 GPU 共享资源池'}</p>
        </div>
        <StatusBadge value={pool.status} />
      </div>

      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <Descriptions column={4} size="small">
          <Descriptions.Item label="资源池 ID">{pool.id}</Descriptions.Item>
          <Descriptions.Item label="类型">{pool.poolType === 'EXCLUSIVE' ? '独享池' : '共享池'}</Descriptions.Item>
          <Descriptions.Item label="GPU 数量">{pool.gpuCount}</Descriptions.Item>
          <Descriptions.Item label="算力规格">{pool.specs.length}</Descriptions.Item>
          <Descriptions.Item label="描述" span={4}>{pool.description || '-'}</Descriptions.Item>
        </Descriptions>
      </div>

      <div className="surface data-table" style={{ marginBottom: 16 }}>
        <div className="toolbar">
          <strong>算力规格</strong>
          <Tag color="green">{pool.specs.length} 个</Tag>
        </div>
        <Table
          rowKey="id"
          dataSource={pool.specs}
          pagination={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无算力规格" /> }}
          columns={[
            { title: '规格名称', dataIndex: 'displayName', render: function render(value, item) { return value || item.name; } },
            {
              title: '品牌',
              dataIndex: 'gpuBrand',
              render: function render(value: GpuBrand) { return <Tag>{BRAND_LABELS[value] || value}</Tag>; },
            },
            { title: '共享比例', dataIndex: 'gpuShare', render: function render(value) { return value || '整卡'; } },
            { title: '总规格节点数', dataIndex: 'totalNodes' },
            { title: '可用规格节点数', dataIndex: 'availableNodes' },
          ]}
        />
      </div>

      <div className="surface data-table">
        <div className="toolbar">
          <strong>已入池 Node</strong>
          <Tag color="green">{nodes.length} 台</Tag>
        </div>
        <Table
          rowKey="id"
          dataSource={nodes}
          pagination={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已入池 Node" /> }}
          onRow={function row(record) {
            return {
              onClick: function go() {
                navigate(`/clusters/${record.clusterId}/nodes/${record.id}`);
              },
              style: { cursor: 'pointer' },
            };
          }}
          columns={[
            { title: 'Kubernetes Node', dataIndex: 'name' },
            { title: 'Internal IP', dataIndex: 'internalIp', render: function render(value) { return value || '-'; } },
            { title: 'CPU', dataIndex: 'cpuCores', render: function render(value) { return `${value} Core`; } },
            { title: '内存', dataIndex: 'memoryBytes', render: formatMemory },
            { title: 'GPU 数量', dataIndex: 'gpuCount' },
            { title: 'Node 状态', dataIndex: 'status', render: function render(value) { return <StatusBadge value={value} />; } },
            {
              title: '操作',
              render: function render(_, item) {
                return (
                  <Space>
                    <Button size="small" onClick={function detail(event) {
                      event.stopPropagation();
                      navigate(`/clusters/${item.clusterId}/nodes/${item.id}`);
                    }}>
                      查看 Node
                    </Button>
                  </Space>
                );
              },
            },
          ]}
        />
      </div>
    </div>
  );
}

function formatMemory(bytes: number) {
  if (!bytes) {
    return '-';
  }
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GiB`;
}
