import { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Modal, Form, Input, Select, InputNumber, message, Card } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ComputeSpec, SpecType, GpuBrand } from '../types';
import { specsApi } from '../api/specs';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';

const TYPE_LABELS: Record<SpecType, string> = { PHYSICAL: '独占', VIRTUAL: '虚拟', OVERSELL: '超分' };
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
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [filterType]);

  const handleCreate = async () => {
    const v = await form.validateFields();
    try {
      const resourceQuotaKey = v.resourceQuotaKey || `platform.io/${v.name}`;
      await specsApi.create({
        name: v.name, displayName: v.displayName, gpuBrand: v.gpuBrand,
        specType: v.specType, defaultGpuCount: v.defaultGpuCount,
        defaultCpuCores: v.defaultCpuCores, defaultMemoryGib: v.defaultMemoryGib,
        defaultGpumemMb: v.defaultGpumemMb, defaultGpucores: v.defaultGpucores,
        resourceQuotaKey, memoryGb: v.memoryGb, description: v.description,
      });
      message.success('规格创建成功');
      setOpen(false); form.resetFields(); load();
    } catch (e: any) { message.error(e?.message || '创建失败'); }
  };

  const handleDelete = async (id: string) => {
    Modal.confirm({
      title: '确认删除？', content: '该操作不可撤销',
      onOk: async () => { try { await specsApi.remove(id); message.success('已删除'); load(); } catch (e: any) { message.error(e?.message || '删除失败'); } },
    });
  };

  return (
    <div>
      <PageHeader
        title="算力规格"
        subtitle="全局规格库 · 定义 GPU 切分方案和资源配额键"
        tags={[{ label: `${specs.length} 规格`, color: 'cyan' }]}
        extra={
          <Space>
            <Select
              placeholder="按类型筛选" allowClear style={{ width: 140 }}
              value={filterType} onChange={setFilterType}
              options={[
                { value: 'PHYSICAL', label: '独占 PHYSICAL' },
                { value: 'VIRTUAL', label: '虚拟 VIRTUAL' },
                { value: 'OVERSELL', label: '超分 OVERSELL' },
              ]}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}
              style={{ background: PSBC_COLORS.primary, borderColor: PSBC_COLORS.primary }}>
              新增规格
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
            { title: '规格名称', dataIndex: 'displayName', width: 200 },
            {
              title: '规格类型', dataIndex: 'specType', width: 100,
              render: (v) => <Tag color={v === 'PHYSICAL' ? 'blue' : v === 'VIRTUAL' ? 'green' : 'orange'}>{TYPE_LABELS[v as SpecType]}</Tag>,
            },
            {
              title: '规格详情', key: 'detail',
              render: (_, r) => {
                const parts = [
                  r.gpuBrand ? BRAND_LABELS[r.gpuBrand as GpuBrand] : '通用',
                  `${r.defaultGpuCount} GPU`,
                  r.defaultGpumemMb ? `显存 ${(r.defaultGpumemMb / 1024).toFixed(0)}GB` : null,
                  r.defaultGpucores ? `算力 ${r.defaultGpucores}%` : null,
                  `CPU ${r.defaultCpuCores}核`,
                  `${r.defaultMemoryGib}GiB 内存`,
                ].filter(Boolean);
                return (
                  <Space size={4}>
                    {parts.map((p, i) => (
                      <Tag key={i} color="default" style={{ fontSize: 11 }}>{p}</Tag>
                    ))}
                  </Space>
                );
              },
            },
            {
              title: '操作', key: 'op', width: 80,
              render: (_, r) => <Button danger size="small" onClick={() => handleDelete(r.id)}>删除</Button>,
            },
          ]}
        />
      </Card>

      <Modal title="新增算力规格" open={open} onOk={handleCreate} onCancel={() => setOpen(false)} okText="创建" width={640}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称（唯一）" rules={[{ required: true }]}>
            <Input placeholder="e.g. shared-hami-h100-1/4" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名" rules={[{ required: true }]}>
            <Input placeholder="e.g. H100 80GB 1/4 卡 (HAMi 切分)" />
          </Form.Item>
          <Space>
            <Form.Item name="specType" label="类型" rules={[{ required: true }]}>
              <Select style={{ width: 140 }} options={[
                { value: 'PHYSICAL', label: '独占 PHYSICAL' },
                { value: 'VIRTUAL', label: '虚拟 VIRTUAL' },
                { value: 'OVERSELL', label: '超分 OVERSELL' },
              ]} />
            </Form.Item>
            <Form.Item name="gpuBrand" label="品牌" rules={[{ required: true }]}>
              <Select style={{ width: 140 }} options={[
                { value: 'NVIDIA', label: 'NVIDIA' }, { value: 'HYGON', label: '海光 DCU' }, { value: 'HUAWEI_ASCEND', label: '华为昇腾' },
              ]} />
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
          <Form.Item name="resourceQuotaKey" label="ResourceQuota Key（留空自动生成）">
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
