import React, { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, Select, InputNumber, Space, Tag, Typography, Descriptions, message } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { specApi } from '../api/specs';
import type { ComputeSpec, SpecCreateRequest, GpuBrand } from '../types';
import { GPU_BRAND_LABELS } from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title, Text } = Typography;

const SpecsPage: React.FC = () => {
  const { isAdmin } = useAuth();
  const [specs, setSpecs] = useState<ComputeSpec[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await specApi.list();
      setSpecs(res.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    await specApi.create(values as SpecCreateRequest);
    message.success('规格创建成功');
    setCreateOpen(false);
    form.resetFields();
    load();
  };

  const gpuBrandTag = (brand: GpuBrand) => {
    const colors: Record<GpuBrand, string> = { NVIDIA: 'green', HYGON: 'orange', HUAWEI_ASCEND: 'purple' };
    return <Tag color={colors[brand]}>{GPU_BRAND_LABELS[brand]}</Tag>;
  };

  const columns = [
    { title: '规格名', dataIndex: 'name', key: 'name', width: 180, ellipsis: true,
      render: (v: string) => <Text code>{v}</Text>,
    },
    { title: '显示名', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
    { title: 'GPU 品牌', dataIndex: 'gpuBrand', key: 'gpuBrand', width: 120,
      render: (v: GpuBrand) => gpuBrandTag(v),
    },
    { title: '显存', dataIndex: 'memoryGb', key: 'memoryGb', width: 80,
      render: (v: number) => `${v}GB`,
    },
    { title: 'GPU 数', dataIndex: 'defaultGpuCount', key: 'defaultGpuCount', width: 80 },
    { title: 'CPU 核', dataIndex: 'defaultCpuCores', key: 'defaultCpuCores', width: 80 },
    { title: '内存', dataIndex: 'defaultMemoryGib', key: 'defaultMemoryGib', width: 80,
      render: (v: number) => `${v}Gi`,
    },
    {
      title: 'ResourceQuota Key', dataIndex: 'resourceQuotaKey', key: 'resourceQuotaKey', width: 200, ellipsis: true,
      render: (v: string) => <Text code className="mono">{v}</Text>,
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>算力规格管理</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              创建规格
            </Button>
          )}
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={specs}
        rowKey="id"
        loading={loading}
        pagination={false}
        expandable={{
          expandedRowRender: (record: ComputeSpec) => (
            <Descriptions size="small" column={2}>
              {record.gpumemMb && <Descriptions.Item label="GPU 内存(MB)">{record.gpumemMb}</Descriptions.Item>}
              {record.gpucores && <Descriptions.Item label="GPU 核心数">{record.gpucores}</Descriptions.Item>}
              {record.description && <Descriptions.Item label="描述" span={2}>{record.description}</Descriptions.Item>}
              {record.nodeSelector && (
                <Descriptions.Item label="nodeSelector" span={2}>
                  <Text code className="mono">{record.nodeSelector}</Text>
                </Descriptions.Item>
              )}
              {record.tolerations && (
                <Descriptions.Item label="tolerations" span={2}>
                  <Text code className="mono">{record.tolerations}</Text>
                </Descriptions.Item>
              )}
            </Descriptions>
          ),
        }}
      />

      <Modal
        title="创建算力规格"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); form.resetFields(); }}
        okText="创建"
        width={500}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="规格名称" rules={[{ required: true }]}
            tooltip="命名规范：{brand}-{model}-{memory}，如 nvidia-rtx4090-24g">
            <Input placeholder="nvidia-rtx4090-24g" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
            <Input placeholder="NVIDIA RTX 4090 24GB" />
          </Form.Item>
          <Form.Item name="gpuBrand" label="GPU 品牌" rules={[{ required: true }]}>
            <Select>
              <Select.Option value="NVIDIA">NVIDIA</Select.Option>
              <Select.Option value="HYGON">海光 DCU</Select.Option>
              <Select.Option value="HUAWEI_ASCEND">华为昇腾</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="memoryGb" label="显存 (GB)" rules={[{ required: true }]}>
            <InputNumber min={1} max={320} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default SpecsPage;
