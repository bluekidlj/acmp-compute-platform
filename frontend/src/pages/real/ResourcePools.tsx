import { useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import {
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
} from 'antd';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import { useNavigate } from 'react-router-dom';
import type {
  ClusterNode,
  GpuBrand,
  GpuDevice,
  PhysicalCluster,
  ResourcePool,
} from '../../types';

const BRAND_LABELS: Record<GpuBrand, string> = {
  NVIDIA: '英伟达',
  HYGON: '海光',
  HUAWEI_ASCEND: '华为',
};
const BRAND_OPTIONS = [
  { value: 'ALL', label: '全部品牌' },
  { value: 'NVIDIA', label: '英伟达' },
  { value: 'HYGON', label: '海光' },
  { value: 'HUAWEI_ASCEND', label: '华为' },
];

interface JoinNodeForm {
  name: string;
  displayName?: string;
  gpuShare?: '1/8' | '1/4' | '1/2';
  cpuCores: number;
  memoryGib: number;
  description?: string;
}

interface NodeWithGpus {
  node: ClusterNode;
  gpus: GpuDevice[];
}

export default function ResourcePoolsPage() {
  const navigate = useNavigate();
  const [pools, setPools] = useState<ResourcePool[]>([]);
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [poolNodes, setPoolNodes] = useState<Record<string, NodeWithGpus[]>>({});
  const [loading, setLoading] = useState(true);
  const [joining, setJoining] = useState(false);
  const [drawerPool, setDrawerPool] = useState<ResourcePool | null>(null);
  const [clusterId, setClusterId] = useState<string>();
  const [availableNodes, setAvailableNodes] = useState<NodeWithGpus[]>([]);
  const [selectedNode, setSelectedNode] = useState<NodeWithGpus | null>(null);
  const [joinBrand, setJoinBrand] = useState<GpuBrand | 'ALL'>('ALL');
  const [poolBrand, setPoolBrand] = useState<GpuBrand | 'ALL'>('ALL');
  const [form] = Form.useForm<JoinNodeForm>();

  function load() {
    setLoading(true);
    Promise.all([api.pools(), api.clusters()])
      .then(async function loadNodes(values) {
        const [nextPools, nextClusters] = values;
        const groups = await Promise.all(nextPools.map(async function loadPoolNodes(pool) {
          const nodes = await api.poolNodes(pool.id);
          return Promise.all(nodes.map(async function withGpus(node) {
            return {
              node,
              gpus: await api.nodeGpus(node.clusterId, node.id),
            };
          }));
        }));
        const map: Record<string, NodeWithGpus[]> = {};
        nextPools.forEach(function assign(pool, index) {
          map[pool.id] = groups[index];
        });
        setPools(nextPools);
        setClusters(nextClusters);
        setPoolNodes(map);
      })
      .catch(function handleError(exception) {
        message.error(exception.message);
      })
      .finally(function finish() {
        setLoading(false);
      });
  }

  useEffect(load, []);

  async function chooseCluster(id: string) {
    setClusterId(id);
    setSelectedNode(null);
    setJoinBrand('ALL');
    form.resetFields();
    try {
      const nodes = await api.nodes(id);
      const details = await Promise.all(nodes.map(async function loadNodeGpus(node) {
        return {
          node,
          gpus: await api.nodeGpus(id, node.id),
        };
      }));
      setAvailableNodes(details.filter(function canJoin(item) {
        return !item.node.resourcePoolId
          && !item.node.computeSpecId
          && item.gpus.length > 0
          && item.gpus.every(function gpuUnassigned(gpu) {
            return !gpu.resourcePoolId && !gpu.computeSpecId;
          });
      }));
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : 'Node 加载失败');
    }
  }

  function selectNode(item: NodeWithGpus) {
    if (!drawerPool || item.gpus.length === 0) {
      return;
    }
    const firstGpu = item.gpus[0];
    const modelName = (firstGpu.gpuModel || 'gpu')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
    const suffix = drawerPool.poolType === 'EXCLUSIVE' ? 'exclusive' : 'shared-quarter';

    setSelectedNode(item);
    form.setFieldsValue({
      name: `${modelName || 'gpu'}-${suffix}`,
      displayName: drawerPool.poolType === 'EXCLUSIVE'
        ? `${firstGpu.gpuModel || 'GPU'} 独享`
        : `${firstGpu.gpuModel || 'GPU'} 共享 1/4`,
      gpuShare: drawerPool.poolType === 'SHARED' ? '1/4' : undefined,
      cpuCores: drawerPool.poolType === 'EXCLUSIVE' ? 8 : 4,
      memoryGib: drawerPool.poolType === 'EXCLUSIVE' ? 32 : 16,
    });
  }

  function openJoinDrawer(pool: ResourcePool) {
    setDrawerPool(pool);
    setClusterId(undefined);
    setAvailableNodes([]);
    setSelectedNode(null);
    setJoinBrand('ALL');
    form.resetFields();
  }

  async function joinNode(values: JoinNodeForm) {
    if (!drawerPool || !selectedNode) {
      message.warning('请先选择一台未入池 Node');
      return;
    }
    setJoining(true);
    try {
      await api.joinPoolNode(drawerPool.id, selectedNode.node.id, values);
      message.success('Node 全部 GPU 已入池并写入 Kubernetes 调度标签');
      setDrawerPool(null);
      setSelectedNode(null);
      form.resetFields();
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : 'Node 入池失败');
    } finally {
      setJoining(false);
    }
  }

  if (loading) {
    return <Spin size="large" />;
  }

  return (
    <div>
      <div className="page-heading">
        <div>
          <h1>资源池</h1>
          <p>以 Kubernetes Node 为单位，将节点全部 GPU 一次性加入独享池或共享池</p>
        </div>
      </div>

      <div className="surface">
        <Tabs
          defaultActiveKey="EXCLUSIVE"
          tabBarStyle={{ padding: '0 20px', margin: 0 }}
          items={[
            {
              key: 'EXCLUSIVE',
              label: '独享池',
              children: renderPool(pools.find(function find(pool) {
                return pool.poolType === 'EXCLUSIVE';
              })),
            },
            {
              key: 'SHARED',
              label: '共享池',
              children: renderPool(pools.find(function find(pool) {
                return pool.poolType === 'SHARED';
              })),
            },
          ]}
        />
      </div>

      <Drawer
        title={drawerPool ? `整台 Node 加入资源池 · ${drawerPool.name}` : 'Node 入池'}
        open={Boolean(drawerPool)}
        width={760}
        onClose={function closeDrawer() {
          setDrawerPool(null);
        }}
      >
        <div className="join-gpu-section">
          <div className="section-label">1. 选择未入池 Node</div>
          <Select
            style={{ width: '100%', marginBottom: 14 }}
            value={clusterId}
            placeholder="选择 Kubernetes 集群"
            onChange={chooseCluster}
            options={clusters.map(function option(cluster) {
              return { value: cluster.id, label: cluster.name };
            })}
          />
          <Select
            style={{ width: '100%', marginBottom: 14 }}
            value={joinBrand}
            options={BRAND_OPTIONS}
            onChange={function filterBrand(value: GpuBrand | 'ALL') {
              setJoinBrand(value);
              setSelectedNode(null);
            }}
          />
          <Table
            rowKey={function key(item) {
              return item.node.id;
            }}
            size="small"
            pagination={false}
            dataSource={availableNodes.filter(function byBrand(item) {
              const brand = item.gpus[0]?.gpuBrand;
              return joinBrand === 'ALL' || brand === joinBrand;
            })}
            rowSelection={{
              type: 'radio',
              selectedRowKeys: selectedNode ? [selectedNode.node.id] : [],
              onSelect: selectNode,
            }}
            onRow={function buildRow(item) {
              return {
                onClick: function choose() {
                  selectNode(item);
                },
                style: { cursor: 'pointer' },
              };
            }}
            columns={[
              { title: 'Node', render: function render(_, item) { return item.node.name; } },
              { title: 'Internal IP', render: function render(_, item) { return item.node.internalIp || '-'; } },
              {
                title: '品牌',
                render: function render(_, item) {
                  const brand = item.gpus[0]?.gpuBrand;
                  return brand ? <Tag>{BRAND_LABELS[brand]}</Tag> : '-';
                },
              },
              { title: 'GPU 型号', render: function render(_, item) { return item.gpus[0]?.gpuModel || '-'; } },
              { title: 'GPU 数量', render: function render(_, item) { return item.gpus.length; } },
              { title: '状态', render: function render(_, item) { return <StatusBadge value={item.node.status} />; } },
            ]}
            locale={{
              emptyText: clusterId ? '该集群没有全部 GPU 均未入池的 Node' : '请先选择集群',
            }}
          />
        </div>

        <div className="join-gpu-section">
          <div className="section-label">2. 设置整台 Node 的统一算力规格</div>
          {selectedNode && (
            <div className="selected-gpu-summary">
              <strong>{selectedNode.node.name}</strong>
              <span>{selectedNode.node.internalIp || '无 Internal IP'}</span>
              <Tag>{selectedNode.gpus[0]?.gpuBrand
                ? BRAND_LABELS[selectedNode.gpus[0].gpuBrand as GpuBrand]
                : '品牌未知'}</Tag>
              <span>{selectedNode.gpus[0]?.gpuModel || '型号未知'}</span>
              <Tag>{selectedNode.gpus.length} 张 GPU</Tag>
            </div>
          )}

          <Form form={form} layout="vertical" onFinish={joinNode}>
            <Form.Item name="name" label="规格唯一名称" rules={[{ required: true }]}>
              <Input disabled={!selectedNode} placeholder="nvidia-v100-exclusive" />
            </Form.Item>
            <Form.Item name="displayName" label="展示名称">
              <Input disabled={!selectedNode} />
            </Form.Item>
            {drawerPool?.poolType === 'SHARED' && (
              <Form.Item name="gpuShare" label="GPU 切分比例" rules={[{ required: true }]}>
                <Select
                  disabled={!selectedNode}
                  options={[
                    { value: '1/8', label: '1/8 · 单卡提供 8 个规格节点' },
                    { value: '1/4', label: '1/4 · 单卡提供 4 个规格节点' },
                    { value: '1/2', label: '1/2 · 单卡提供 2 个规格节点' },
                  ]}
                />
              </Form.Item>
            )}
            <div className="form-grid-two">
              <Form.Item name="cpuCores" label="每副本 CPU Core" rules={[{ required: true }]}>
                <InputNumber disabled={!selectedNode} min={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="memoryGib" label="每副本内存 GiB" rules={[{ required: true }]}>
                <InputNumber disabled={!selectedNode} min={1} style={{ width: '100%' }} />
              </Form.Item>
            </div>
            <Form.Item name="description" label="描述">
              <Input.TextArea disabled={!selectedNode} rows={3} />
            </Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              loading={joining}
              disabled={!selectedNode}
              block
            >
              整台 Node 加入资源池
            </Button>
          </Form>
        </div>
      </Drawer>
    </div>
  );

  function renderPool(pool: ResourcePool | undefined) {
    if (!pool) {
      return <Empty description="资源池不存在" />;
    }
    const nodes = (poolNodes[pool.id] || []).filter(function byBrand(item) {
      return poolBrand === 'ALL' || item.gpus[0]?.gpuBrand === poolBrand;
    });

    return (
      <div>
        <div className="toolbar">
          <Space size={16}>
            <strong>{pool.name}</strong>
            <StatusBadge value={pool.status} />
            <span>Node 数量：<strong className="resource-value">{nodes.length}</strong></span>
            <span>GPU 数量：<strong className="resource-value">{pool.gpuCount}</strong></span>
            <span>算力规格：<strong className="resource-value">{pool.specs.length}</strong></span>
          </Space>
          <Space>
            <Select
              value={poolBrand}
              options={BRAND_OPTIONS}
              style={{ width: 130 }}
              onChange={function filter(value: GpuBrand | 'ALL') {
                setPoolBrand(value);
              }}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={function open() { openJoinDrawer(pool); }}>
              加入 Node
            </Button>
            <Button onClick={function detail() { navigate(`/resource-pools/${pool.id}`); }}>
              查看详情
            </Button>
          </Space>
        </div>

        <Table
          rowKey={function key(item) {
            return item.node.id;
          }}
          pagination={false}
          dataSource={nodes}
          onRow={function row(record) {
            return {
              onClick: function go() {
                navigate(`/clusters/${record.node.clusterId}/nodes/${record.node.id}`);
              },
              style: { cursor: 'pointer' },
            };
          }}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该资源池暂无 Node" />,
          }}
          columns={[
            { title: 'Kubernetes Node', render: function render(_, item) { return item.node.name; } },
            { title: 'Internal IP', render: function render(_, item) { return item.node.internalIp || '-'; } },
            {
              title: '品牌',
              render: function render(_, item) {
                const brand = item.gpus[0]?.gpuBrand;
                return brand ? <Tag>{BRAND_LABELS[brand]}</Tag> : '-';
              },
            },
            { title: 'GPU 型号', render: function render(_, item) { return item.gpus[0]?.gpuModel || '-'; } },
            { title: 'GPU 数量', render: function render(_, item) { return item.gpus.length; } },
            {
              title: '算力规格',
              render: function render(_, item) {
                const spec = pool.specs.find(function find(value) {
                  return value.id === item.node.computeSpecId;
                });
                return spec ? spec.displayName || spec.name : '-';
              },
            },
            {
              title: '标签状态',
              render: function render(_, item) {
                return item.node.resourcePoolId && item.node.computeSpecId
                  ? <Tag color="green">已标记</Tag>
                  : <Tag>未标记</Tag>;
              },
            },
            { title: 'Node 状态', render: function render(_, item) { return <StatusBadge value={item.node.status} />; } },
          ]}
        />
      </div>
    );
  }
}
