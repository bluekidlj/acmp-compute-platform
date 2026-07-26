import { useEffect, useState } from 'react';
import {
  ApartmentOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { Button, Descriptions, Drawer, Empty, message, Popconfirm, Progress, Select, Space, Tabs, Tag } from 'antd';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ComputeSpec, GpuBrand } from '../../types';

const BRAND_LABELS: Record<GpuBrand, string> = {
  NVIDIA: '英伟达',
  HYGON: '海光',
  HUAWEI_ASCEND: '华为',
};

export default function SpecsPage() {
  const [items, setItems] = useState<ComputeSpec[]>([]);
  const [selected, setSelected] = useState<ComputeSpec | null>(null);
  const [brand, setBrand] = useState<GpuBrand | 'ALL'>('ALL');
  const [deletingId, setDeletingId] = useState<string | null>(null);

  function loadSpecs() {
    api.specs()
      .then(setItems)
      .catch(function handleFailure(exception) {
        message.error(exception.message);
      });
  }

  useEffect(loadSpecs, []);

  async function deleteSpec(spec: ComputeSpec) {
    setDeletingId(spec.id);
    try {
      await api.deleteSpec(spec.id);
      message.success('算力规格已删除');
      if (selected?.id === spec.id) {
        setSelected(null);
      }
      loadSpecs();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '算力规格删除失败');
    } finally {
      setDeletingId(null);
    }
  }

  const exclusive = items.filter(function isExclusive(spec) {
    return spec.specType === 'EXCLUSIVE' && (brand === 'ALL' || spec.gpuBrand === brand);
  });
  const shared = items.filter(function isShared(spec) {
    return spec.specType === 'SHARED' && (brand === 'ALL' || spec.gpuBrand === brand);
  });

  return (
    <div>
      <div className="page-heading">
        <div>
          <h1>算力规格</h1>
          <p>Node 整体入池时形成的只读算力套餐，点击卡片查看规格和来源 GPU</p>
        </div>
      </div>

      <div className="toolbar">
        <span>按品牌查看算力规格</span>
        <Select
          value={brand}
          style={{ width: 140 }}
          options={[
            { value: 'ALL', label: '全部品牌' },
            { value: 'NVIDIA', label: '英伟达' },
            { value: 'HYGON', label: '海光' },
            { value: 'HUAWEI_ASCEND', label: '华为' },
          ]}
          onChange={function filterBrand(value: GpuBrand | 'ALL') { setBrand(value); }}
        />
      </div>

      <Tabs
        defaultActiveKey="EXCLUSIVE"
        items={[
          {
            key: 'EXCLUSIVE',
            label: `独享规格 (${exclusive.length})`,
            children: renderCards(exclusive),
          },
          {
            key: 'SHARED',
            label: `共享规格 (${shared.length})`,
            children: renderCards(shared),
          },
        ]}
      />

      <Drawer
        title={selected ? selected.displayName || selected.name : '算力规格详情'}
        open={Boolean(selected)}
        width={620}
        onClose={function closeDrawer() {
          setSelected(null);
        }}
      >
        {selected && (
          <>
            <div className="spec-detail-hero">
              <div>
                <Tag color={selected.specType === 'EXCLUSIVE' ? 'green' : 'cyan'}>
                  {selected.specType === 'EXCLUSIVE' ? '独享规格' : '共享规格'}
                </Tag>
                <h2>{selected.displayName || selected.name}</h2>
                <code>{selected.name}</code>
              </div>
              <Space>
                <StatusBadge value={selected.status} />
                <Popconfirm
                  title="删除算力规格"
                  description="删除后会释放关联 Node 和 GPU 的入池归属。"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={function confirm() { deleteSpec(selected); }}
                >
                  <Button danger icon={<DeleteOutlined />} loading={deletingId === selected.id}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            </div>

            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label="所属资源池">
                {selected.resourcePoolName || selected.resourcePoolId}
              </Descriptions.Item>
              <Descriptions.Item label="品牌">{BRAND_LABELS[selected.gpuBrand]}</Descriptions.Item>
              <Descriptions.Item label="可提供节点">
                {selected.capacityNodes}
              </Descriptions.Item>
              <Descriptions.Item label="Gpu 型号" span={2}>
                {selected.gpuModel || '未发现型号'}
              </Descriptions.Item>
              <Descriptions.Item label="Gpu 数量">1</Descriptions.Item>
              <Descriptions.Item label="共享比例">
                {selected.gpuShare || '整卡独享'}
              </Descriptions.Item>
              <Descriptions.Item label="CPU">{selected.cpuCores} Core</Descriptions.Item>
              <Descriptions.Item label="内存">{selected.memoryGib} GiB</Descriptions.Item>
              <Descriptions.Item label="Kubernetes Node">
                {selected.sourceNodeName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="Gpu 编号">
                {selected.sourceGpuIndex ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="Gpu UUID" span={2}>
                <code>{selected.sourceGpuUuid || '-'}</code>
              </Descriptions.Item>
              <Descriptions.Item label="已分配配额">
                {selected.allocatedNodes}
              </Descriptions.Item>
              <Descriptions.Item label="实际使用节点">
                {selected.usedNodes}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间" span={2}>
                {new Date(selected.createdAt).toLocaleString('zh-CN')}
              </Descriptions.Item>
              <Descriptions.Item label="描述" span={2}>
                {selected.description || '暂无描述'}
              </Descriptions.Item>
            </Descriptions>
          </>
        )}
      </Drawer>
    </div>
  );

  function renderCards(specs: ComputeSpec[]) {
    if (specs.length === 0) {
      return (
        <div className="surface spec-empty">
          <Empty description="暂无算力规格，请先将 Gpu 加入对应资源池" />
        </div>
      );
    }

    return (
      <div className="spec-card-grid">
        {specs.map(function renderSpec(spec) {
          const percent = spec.capacityNodes > 0
            ? Math.min(100, Math.round((spec.allocatedNodes / spec.capacityNodes) * 100))
            : 0;

          return (
            <article
              className={`spec-card ${spec.specType.toLowerCase()}`}
              key={spec.id}
              onClick={function openDetail() {
                setSelected(spec);
              }}
            >
              <div className="spec-card-heading">
                <div className="spec-card-icon">
                  <ThunderboltOutlined />
                </div>
                <Tag color={spec.specType === 'EXCLUSIVE' ? 'green' : 'cyan'}>
                  {spec.specType === 'EXCLUSIVE' ? '独享' : `共享 ${spec.gpuShare}`}
                </Tag>
                <Tag>{BRAND_LABELS[spec.gpuBrand]}</Tag>
                <Popconfirm
                  title="删除算力规格"
                  description="删除后会释放关联 Node 和 GPU 的入池归属。"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={function confirm(event) {
                    event?.stopPropagation();
                    deleteSpec(spec);
                  }}
                  onCancel={function cancel(event) {
                    event?.stopPropagation();
                  }}
                >
                  <Button
                    danger
                    size="small"
                    icon={<DeleteOutlined />}
                    loading={deletingId === spec.id}
                    onClick={function stop(event) {
                      event.stopPropagation();
                    }}
                  />
                </Popconfirm>
              </div>

              <h3>{spec.displayName || spec.name}</h3>
              <code>{spec.name}</code>
              <p>{spec.gpuModel || 'Gpu 型号未发现'}</p>

              <div className="spec-resource-grid">
                <div>
                  <CloudServerOutlined />
                  <span>CPU</span>
                  <strong>{spec.cpuCores} Core</strong>
                </div>
                <div>
                  <DatabaseOutlined />
                  <span>内存</span>
                  <strong>{spec.memoryGib} GiB</strong>
                </div>
                <div>
                  <ApartmentOutlined />
                  <span>规格节点</span>
                  <strong>{spec.capacityNodes}</strong>
                </div>
              </div>

              <div className="spec-allocation">
                <div>
                  <span>租户配额分配</span>
                  <strong>{spec.allocatedNodes} / {spec.capacityNodes}</strong>
                </div>
                <Progress percent={percent} showInfo={false} strokeColor="#008a57" />
                <small>实际使用 {spec.usedNodes} 个节点</small>
              </div>
            </article>
          );
        })}
      </div>
    );
  }
}
