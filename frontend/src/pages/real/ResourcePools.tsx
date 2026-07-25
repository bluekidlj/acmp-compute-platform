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
import type { GpuBrand, GpuDevice, PhysicalCluster, ResourcePool } from '../../types';

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

interface JoinGpuForm {
  name: string;
  displayName?: string;
  gpuShare?: '1/8' | '1/4' | '1/2';
  cpuCores: number;
  memoryGib: number;
  description?: string;
}

export default function ResourcePoolsPage() {
  const [pools, setPools] = useState<ResourcePool[]>([]);
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [poolGpus, setPoolGpus] = useState<Record<string, GpuDevice[]>>({});
  const [loading, setLoading] = useState(true);
  const [joining, setJoining] = useState(false);
  const [drawerPool, setDrawerPool] = useState<ResourcePool | null>(null);
  const [clusterId, setClusterId] = useState<string>();
  const [available, setAvailable] = useState<GpuDevice[]>([]);
  const [selectedGpu, setSelectedGpu] = useState<GpuDevice | null>(null);
  const [joinBrand, setJoinBrand] = useState<GpuBrand | 'ALL'>('ALL');
  const [poolBrand, setPoolBrand] = useState<GpuBrand | 'ALL'>('ALL');
  const [form] = Form.useForm<JoinGpuForm>();

  function load() {
    setLoading(true);

    Promise.all([api.pools(), api.clusters()])
      .then(async function loadGpuLists(values) {
        const [nextPools, nextClusters] = values;
        const groups = await Promise.all(nextPools.map(function loadPool(pool) {
          return api.poolGpus(pool.id);
        }));
        const map: Record<string, GpuDevice[]> = {};

        nextPools.forEach(function assign(pool, index) {
          map[pool.id] = groups[index];
        });

        setPools(nextPools);
        setClusters(nextClusters);
        setPoolGpus(map);
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
    setSelectedGpu(null);
    setJoinBrand('ALL');
    form.resetFields();

    try {
      const gpus = await api.gpus(id);
      setAvailable(gpus.filter(function isUnassigned(gpu) {
        return !gpu.resourcePoolId && !gpu.computeSpecId;
      }));
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : 'Gpu 加载失败');
    }
  }

  function selectGpu(gpu: GpuDevice) {
    if (!drawerPool) {
      return;
    }

    const modelName = (gpu.gpuModel || 'gpu')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
    const suffix = drawerPool.poolType === 'EXCLUSIVE' ? 'exclusive' : 'shared-quarter';

    setSelectedGpu(gpu);
    form.setFieldsValue({
      name: `${modelName || 'gpu'}-${gpu.gpuIndex}-${suffix}`,
      displayName: drawerPool.poolType === 'EXCLUSIVE'
        ? `${gpu.gpuModel || 'Gpu'} 独享单卡`
        : `${gpu.gpuModel || 'Gpu'} 共享 1/4`,
      gpuShare: drawerPool.poolType === 'SHARED' ? '1/4' : undefined,
      cpuCores: drawerPool.poolType === 'EXCLUSIVE' ? 8 : 4,
      memoryGib: drawerPool.poolType === 'EXCLUSIVE' ? 32 : 16,
    });
  }

  function openJoinDrawer(pool: ResourcePool) {
    setDrawerPool(pool);
    setClusterId(undefined);
    setAvailable([]);
    setSelectedGpu(null);
    setJoinBrand('ALL');
    form.resetFields();
  }

  async function joinGpu(values: JoinGpuForm) {
    if (!drawerPool || !selectedGpu) {
      message.warning('请先选择一张未入池 Gpu');
      return;
    }

    setJoining(true);

    try {
      await api.joinPoolGpu(drawerPool.id, selectedGpu.id, values);
      message.success('Gpu 已入池，算力规格已创建');
      setDrawerPool(null);
      setSelectedGpu(null);
      form.resetFields();
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : 'Gpu 入池失败');
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
          <p>Gpu 入池时同步创建单 Gpu 算力规格，0.1 版本不提供移出操作</p>
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
              children: renderPool(pools.find(function findExclusive(pool) {
                return pool.poolType === 'EXCLUSIVE';
              })),
            },
            {
              key: 'SHARED',
              label: '共享池',
              children: renderPool(pools.find(function findShared(pool) {
                return pool.poolType === 'SHARED';
              })),
            },
          ]}
        />
      </div>

      <Drawer
        title={drawerPool ? `Gpu 入池并创建规格 · ${drawerPool.name}` : 'Gpu 入池'}
        open={Boolean(drawerPool)}
        width={680}
        onClose={function closeDrawer() {
          setDrawerPool(null);
        }}
      >
        <div className="join-gpu-section">
          <div className="section-label">1. 选择未入池 Gpu</div>

          <Select
            style={{ width: '100%', marginBottom: 14 }}
            value={clusterId}
            placeholder="选择 Kubernetes 集群"
            onChange={chooseCluster}
            options={clusters.map(function toOption(cluster) {
              return {
                value: cluster.id,
                label: cluster.name,
              };
            })}
          />

          <Select
            style={{ width: '100%', marginBottom: 14 }}
            value={joinBrand}
            options={BRAND_OPTIONS}
            onChange={function filterBrand(value: GpuBrand | 'ALL') {
              setJoinBrand(value);
              setSelectedGpu(null);
            }}
          />

          <Table
            rowKey="id"
            size="small"
            dataSource={available.filter(function byBrand(gpu) {
              return joinBrand === 'ALL' || gpu.gpuBrand === joinBrand;
            })}
            pagination={false}
            rowSelection={{
              type: 'radio',
              selectedRowKeys: selectedGpu ? [selectedGpu.id] : [],
              onSelect: selectGpu,
            }}
            onRow={function buildRow(gpu) {
              return {
                onClick: function chooseGpu() {
                  selectGpu(gpu);
                },
                style: {
                  cursor: 'pointer',
                },
              };
            }}
            columns={[
              { title: 'Kubernetes Node', dataIndex: 'nodeName' },
              { title: '编号', dataIndex: 'gpuIndex', width: 64 },
              {
                title: '品牌',
                dataIndex: 'gpuBrand',
                width: 90,
                render: function renderBrand(value: GpuBrand | null) {
                  return value ? <Tag>{BRAND_LABELS[value]}</Tag> : '-';
                },
              },
              { title: 'Gpu 型号', dataIndex: 'gpuModel' },
            ]}
            locale={{
              emptyText: clusterId ? '该集群没有未入池 Gpu' : '请先选择集群',
            }}
          />
        </div>

        <div className="join-gpu-section">
          <div className="section-label">2. 填写算力规格</div>

          {selectedGpu && (
            <div className="selected-gpu-summary">
              <span>{selectedGpu.nodeName}</span>
              <strong>Gpu {selectedGpu.gpuIndex}</strong>
              <Tag>{selectedGpu.gpuBrand ? BRAND_LABELS[selectedGpu.gpuBrand] : '品牌未知'}</Tag>
              <span>{selectedGpu.gpuModel || '型号未知'}</span>
              <Tag>Gpu 数量固定为 1</Tag>
            </div>
          )}

          <Form form={form} layout="vertical" onFinish={joinGpu}>
            <Form.Item
              name="name"
              label="规格唯一名称"
              rules={[{ required: true, message: '请输入规格唯一名称' }]}
            >
              <Input disabled={!selectedGpu} placeholder="a100-shared-quarter" />
            </Form.Item>

            <Form.Item name="displayName" label="展示名称">
              <Input disabled={!selectedGpu} />
            </Form.Item>

            {drawerPool?.poolType === 'SHARED' && (
              <Form.Item
                name="gpuShare"
                label="Gpu 切分比例"
                rules={[{ required: true, message: '请选择切分比例' }]}
              >
                <Select
                  disabled={!selectedGpu}
                  options={[
                    { value: '1/8', label: '1/8 · 提供 8 个规格节点' },
                    { value: '1/4', label: '1/4 · 提供 4 个规格节点' },
                    { value: '1/2', label: '1/2 · 提供 2 个规格节点' },
                  ]}
                />
              </Form.Item>
            )}

            <div className="form-grid-two">
              <Form.Item
                name="cpuCores"
                label="CPU Core"
                rules={[{ required: true, message: '请输入 CPU Core' }]}
              >
                <InputNumber disabled={!selectedGpu} min={1} style={{ width: '100%' }} />
              </Form.Item>

              <Form.Item
                name="memoryGib"
                label="内存 GiB"
                rules={[{ required: true, message: '请输入内存' }]}
              >
                <InputNumber disabled={!selectedGpu} min={1} style={{ width: '100%' }} />
              </Form.Item>
            </div>

            <Form.Item name="description" label="描述">
              <Input.TextArea disabled={!selectedGpu} rows={3} />
            </Form.Item>

            <Button
              type="primary"
              htmlType="submit"
              loading={joining}
              disabled={!selectedGpu}
              block
            >
              确认入池并创建规格
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

    const gpus = (poolGpus[pool.id] || []).filter(function byBrand(gpu) {
      return poolBrand === 'ALL' || gpu.gpuBrand === poolBrand;
    });

    return (
      <div>
        <div className="toolbar">
          <Space size={16}>
            <strong>{pool.name}</strong>
            <StatusBadge value={pool.status} />
            <span>Gpu 数量：<strong className="resource-value">{pool.gpuCount}</strong></span>
            <span>算力规格：<strong className="resource-value">{pool.specs.length}</strong></span>
          </Space>

          <Space>
            <Select
              value={poolBrand}
              options={BRAND_OPTIONS}
              style={{ width: 130 }}
              onChange={function filterPoolBrand(value: GpuBrand | 'ALL') {
                setPoolBrand(value);
              }}
            />
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={function handleJoin() {
                openJoinDrawer(pool);
              }}
            >
              加入 Gpu
            </Button>
          </Space>
        </div>

        <Table
          rowKey="id"
          pagination={false}
          dataSource={gpus}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该资源池暂无 Gpu" />,
          }}
          columns={[
            { title: 'Kubernetes Node', dataIndex: 'nodeName' },
            { title: 'Gpu 编号', dataIndex: 'gpuIndex', width: 100 },
            {
              title: '品牌',
              dataIndex: 'gpuBrand',
              width: 100,
              render: function renderBrand(value: GpuBrand | null) {
                return value ? <Tag>{BRAND_LABELS[value]}</Tag> : '-';
              },
            },
            { title: 'Gpu 型号', dataIndex: 'gpuModel' },
            {
              title: '对应算力规格',
              dataIndex: 'computeSpecId',
              render: function renderSpec(specId) {
                const spec = pool.specs.find(function findSpec(item) {
                  return item.id === specId;
                });
                return spec ? spec.displayName || spec.name : '-';
              },
            },
            {
              title: 'Gpu 状态',
              dataIndex: 'status',
              width: 120,
              render: function renderStatus(value) {
                return <StatusBadge value={value} />;
              },
            },
            {
              title: '使用状态',
              dataIndex: 'usageStatus',
              width: 120,
              render: function renderUsage(value) {
                return <StatusBadge value={value} />;
              },
            },
          ]}
        />
      </div>
    );
  }
}
