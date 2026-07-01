import { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Modal, Form, Input, Select, InputNumber, message, Card } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ComputeSpec, SpecType, GpuBrand } from '../types';
import { specsApi } from '../api/specs';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const TYPE_LABELS: Record<SpecType, string> = { PHYSICAL: '物理', VIRTUAL: '虚拟', OVERSELL: '超分' };
const POOL_LABELS = { EXCLUSIVE: '独占', SHARED: '共享', OVERSELL: '超分' } as const;
const BRAND_LABELS: Record<GpuBrand, string> = { NVIDIA: 'NVIDIA', HYGON: '海光 DCU', HUAWEI_ASCEND: '华为昇腾' };

export default function SpecsPage() {
  const [specs, setSpecs] = useState<ComputeSpec[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterType, setFilterType] = useState<SpecType | undefined>(undefined);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setSpecs(await specsApi.list(filterType ? { poolType: filterType } : undefined));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [filterType]);

  const handleCreate = async () => {
    const v = await form.validateFields();
    try {
      const resourceQuotaKey = v.resourceQuotaKey || `platform.io/${v.name}`;
      await specsApi.create({
        name: v.name,
        displayName: v.displayName,
        gpuBrand: v.gpuBrand,
        specType: v.specType,
        defaultGpuCount: v.defaultGpuCount,
        defaultCpuCores: v.defaultCpuCores,
        defaultMemoryGib: v.defaultMemoryGib,
        defaultGpumemMb: v.defaultGpumemMb,
        defaultGpucores: v.defaultGpucores,
        resourceQuotaKey,
        memoryGb: v.memoryGb,
        description: v.description,
      });
      message.success('规格创建成功');
      setOpen(false);
      form.resetFields();
      load();
    } catch (e: any) {
      message.error(e?.message || '创建失败');
    }
  };

  const handleDelete = async (id: string) => {
    Modal.confirm({
      title: '确认删除？',
      content: '该操作不可撤销',
      onOk: async () => {
        try {
          await specsApi.remove(id);
          message.success('已删除');
          load();
        } catch (e: any) {
          message.error(e?.message || '删除失败');
        }
      },
    });
  };

  return (
    <div>
      <PageHeader
        title="算力规格"
        subtitle="全局规格库：7 条预置 + 用户可创建"
        extra={
          <Space>
            <Select
              placeholder="按池类型筛选"
              allowClear
              style={{ width: 140 }}
              value={filterType}
              onChange={setFilterType}
              options={[
                { value: 'PHYSICAL', label: '物理 PHYSICAL' },
                { value: 'VIRTUAL', label: '虚拟 VIRTUAL' },
                { value: 'OVERSELL', label: '超分 OVERSELL' },
              ]}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}
              style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
              新建规格
            </Button>
          </Space>
        }
      />
      <Card style={{ borderRadius: 8 }}>
        <Table
          loading={loading}
          dataSource={specs}
          rowKey="id"
          pagination={false}
          size="middle"
          columns={[
            { title: '名称', dataIndex: 'name', render: (v) => <code className="mono">{v}</code> },
            { title: '显示名', dataIndex: 'displayName' },
            { title: '品牌', dataIndex: 'gpuBrand', width: 100,
              render: (v) => v ? <Tag color="green">{BRAND_LABELS[v as GpuBrand]}</Tag> : '-' },
            { title: '类型', dataIndex: 'specType', width: 90,
              render: (v) => <Tag color="blue">{TYPE_LABELS[v as SpecType]}</Tag> },
            { title: '池', dataIndex: 'poolType', width: 80,
              render: (v) => <Tag>{POOL_LABELS[v as keyof typeof POOL_LABELS]}</Tag> },
            { title: 'GPU', dataIndex: 'defaultGpuCount', width: 60 },
            { title: 'gpumem MB', dataIndex: 'defaultGpumemMb', width: 100,
              render: (v) => v ?? '-' },
            { title: 'cores', dataIndex: 'defaultGpucores', width: 70,
              render: (v) => v ? `${v}%` : '-' },
            { title: 'CPU', dataIndex: 'defaultCpuCores', width: 60 },
            { title: 'Mem(GiB)', dataIndex: 'defaultMemoryGib', width: 90 },
            { title: '操作', key: 'op', width: 80, fixed: 'right',
              render: (_, r) => (
                <Button danger size="small" onClick={() => handleDelete(r.id)}>删除</Button>
              ),
            },
          ]}
        />
      </Card>

      <Modal title="新建算力规格" open={open} onOk={handleCreate} onCancel={() => setOpen(false)} okText="创建" width={640}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称（唯一）" rules={[{ required: true }]}>
            <Input placeholder="e.g. shared-hami-h100-1/4" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名" rules={[{ required: true }]}>
            <Input placeholder="e.g. H100 80GB 1/4 卡 (HAMi 切分)" />
          </Form.Item>
          <Space>
            <Form.Item name="specType" label="类型" rules={[{ required: true }]}>
              <Select options={[
                { value: 'PHYSICAL', label: '物理' }, { value: 'VIRTUAL', label: '虚拟' }, { value: 'OVERSELL', label: '超分' },
              ]} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="gpuBrand" label="品牌" rules={[{ required: true }]}>
              <Select options={[
                { value: 'NVIDIA', label: 'NVIDIA' }, { value: 'HYGON', label: '海光 DCU' }, { value: 'HUAWEI_ASCEND', label: '华为昇腾' },
              ]} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="defaultGpuCount" label="GPU" rules={[{ required: true }]} initialValue={1}>
              <InputNumber min={1} max={8} style={{ width: 80 }} />
            </Form.Item>
          </Space>
          <Space>
            <Form.Item name="defaultCpuCores" label="CPU" rules={[{ required: true }]} initialValue={2}>
              <InputNumber min={1} max={64} style={{ width: 80 }} />
            </Form.Item>
            <Form.Item name="defaultMemoryGib" label="Mem(GiB)" rules={[{ required: true }]} initialValue={8}>
              <InputNumber min={1} max={512} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="memoryGb" label="总显存(GB)" rules={[{ required: true }]} initialValue={20}>
              <InputNumber min={1} max={200} style={{ width: 100 }} />
            </Form.Item>
          </Space>
          <Space>
            <Form.Item name="defaultGpumemMb" label="gpumem MB (切分)" tooltip="VIRTUAL 用">
              <InputNumber min={0} max={81920} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="defaultGpucores" label="cores%" tooltip="VIRTUAL 用">
              <InputNumber min={0} max={100} style={{ width: 80 }} />
            </Form.Item>
          </Space>
          <Form.Item name="resourceQuotaKey" label="ResourceQuota Key（留空自动生成）" tooltip="K8s ResourceQuota hard 键名">
            <Input placeholder="默认 platform.io/{name}" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}