import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Descriptions, Drawer, Form, Input, InputNumber, message, Popconfirm, Select, Space, Table, Tabs } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ComputeSpec, Project, Tenant, TenantSpecQuota } from '../../types';

export default function TenantDetailPage() {
  const { tenantId = '' } = useParams();
  const navigate = useNavigate();
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [quotas, setQuotas] = useState<TenantSpecQuota[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [specs, setSpecs] = useState<ComputeSpec[]>([]);
  const [quotaOpen, setQuotaOpen] = useState(false);
  const [projectOpen, setProjectOpen] = useState(false);
  const [quotaForm] = Form.useForm();
  const [projectForm] = Form.useForm();

  function load() {
    Promise.all([api.tenant(tenantId), api.tenantQuotas(tenantId), api.projects(tenantId), api.specs()])
      .then(function setAll(values) {
        setTenant(values[0]);
        setQuotas(values[1]);
        setProjects(values[2]);
        setSpecs(values[3]);
      })
      .catch(function fail(exception) { message.error(exception.message); });
  }
  useEffect(load, [tenantId]);

  async function createQuota(values: { specId: string; total: number }) {
    try {
      await api.createTenantQuota(tenantId, values.specId, values.total);
      message.success('算力规格配额已分配');
      setQuotaOpen(false);
      quotaForm.resetFields();
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '分配失败');
    }
  }

  async function updateQuota(record: TenantSpecQuota, total: number | null) {
    if (total === null) {
      return;
    }
    try {
      await api.updateTenantQuota(tenantId, record.id, total);
      message.success('配额已更新');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '更新失败');
    }
  }

  async function deleteQuota(id: string) {
    try {
      await api.deleteTenantQuota(tenantId, id);
      message.success('配额已删除');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败');
    }
  }

  async function createProject(values: { name: string; description?: string }) {
    try {
      await api.createProject(tenantId, values);
      message.success('项目已创建');
      setProjectOpen(false);
      projectForm.resetFields();
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '创建失败');
    }
  }

  if (!tenant) {
    return null;
  }
  const allocatedSpecIds = new Set(quotas.map(function id(item) { return item.specId; }));
  const assignableSpecs = specs.filter(function canAssign(spec) {
    return !allocatedSpecIds.has(spec.id)
      && spec.allocatedNodes < spec.capacityNodes;
  });

  function renderQuotaTable(poolType: 'EXCLUSIVE' | 'SHARED') {
    const data = quotas.filter(function matchPool(quota) {
      return quota.poolType === poolType;
    });

    return (
      <Table
        rowKey="id"
        dataSource={data}
        pagination={false}
        locale={{
          emptyText: poolType === 'EXCLUSIVE' ? '暂无独享规格配额' : '暂无共享规格配额',
        }}
        columns={[
          {
            title: '算力规格',
            dataIndex: 'specName',
            render: function renderSpecName(value, record: TenantSpecQuota) {
              return (
                <div>
                  <strong>{record.specDisplayName || value}</strong>
                  <div className="mono quota-spec-name">{value}</div>
                </div>
              );
            },
          },
          {
            title: 'Gpu 型号',
            dataIndex: 'gpuModel',
            render: function renderGpuModel(value) {
              return value || '-';
            },
          },
          {
            title: '共享比例',
            dataIndex: 'gpuShare',
            width: 100,
            render: function renderShare(value) {
              return value || '整卡';
            },
          },
          {
            title: 'CPU / 内存',
            width: 150,
            render: function renderResources(_, record: TenantSpecQuota) {
              return `${record.cpuCores} Core / ${record.memoryGib} GiB`;
            },
          },
          {
            title: '规格容量',
            dataIndex: 'capacityNodes',
            width: 100,
          },
          {
            title: '节点总量',
            dataIndex: 'total',
            width: 150,
            render: function renderTotal(value, record: TenantSpecQuota) {
              return (
                <InputNumber
                  min={record.used}
                  max={record.capacityNodes}
                  defaultValue={value}
                  onPressEnter={function updateOnEnter(event) {
                    updateQuota(record, Number(event.currentTarget.value));
                  }}
                />
              );
            },
          },
          {
            title: '已使用节点',
            dataIndex: 'used',
            width: 110,
          },
          {
            title: '剩余节点',
            dataIndex: 'remaining',
            width: 110,
            render: function renderRemaining(value) {
              return <span className="resource-value">{value}</span>;
            },
          },
          {
            title: '操作',
            width: 90,
            render: function renderAction(_, record: TenantSpecQuota) {
              return (
                <Popconfirm
                  title="已使用配额不能删除，确认继续？"
                  onConfirm={function confirmDelete() {
                    deleteQuota(record.id);
                  }}
                >
                  <Button danger size="small">删除</Button>
                </Popconfirm>
              );
            },
          },
        ]}
      />
    );
  }

  return (
    <div>
      <div className="page-heading">
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={function back() { navigate('/tenants'); }} style={{ padding: 0 }}>返回租户</Button>
          <h1>{tenant.name}</h1>
          <p>{tenant.description || '租户资源与项目'}</p>
        </div>
        <StatusBadge value={tenant.status} />
      </div>
      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <Descriptions column={3}>
          <Descriptions.Item label="租户 ID"><code>{tenant.id}</code></Descriptions.Item>
          <Descriptions.Item label="创建者">{tenant.createdBy}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{new Date(tenant.createdAt).toLocaleString('zh-CN')}</Descriptions.Item>
        </Descriptions>
      </div>
      <div className="surface data-table">
        <Tabs
          tabBarStyle={{ padding: '0 16px', margin: 0 }}
          items={[
            {
              key: 'quotas',
              label: `算力资源配额 (${quotas.length})`,
              children: (
                <>
                  <div className="toolbar">
                    <span>配额单位为算力规格节点数</span>
                    <Button
                      type="primary"
                      size="small"
                      icon={<PlusOutlined />}
                      disabled={assignableSpecs.length === 0}
                      onClick={function openQuotaDrawer() {
                        setQuotaOpen(true);
                      }}
                    >
                      分配规格节点
                    </Button>
                  </div>
                  <Tabs
                    className="quota-pool-tabs"
                    defaultActiveKey="EXCLUSIVE"
                    items={[
                      {
                        key: 'EXCLUSIVE',
                        label: '独享规格',
                        children: renderQuotaTable('EXCLUSIVE'),
                      },
                      {
                        key: 'SHARED',
                        label: '共享规格',
                        children: renderQuotaTable('SHARED'),
                      },
                    ]}
                  />
                </>
              ),
            },
            {
              key: 'projects',
              label: `项目 (${projects.length})`,
              children: (
                <>
                  <div className="toolbar"><span>项目继承租户的可用算力规格</span><Button type="primary" size="small" icon={<PlusOutlined />} onClick={function open() { setProjectOpen(true); }}>新建项目</Button></div>
                  <Table
                    rowKey="id"
                    dataSource={projects}
                    pagination={false}
                    onRow={function goRow(record) { return { onClick: function go() { navigate(`/projects/${record.id}`); }, style: { cursor: 'pointer' } }; }}
                    columns={[
                      { title: '项目名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
                      { title: '描述', dataIndex: 'description', render: function render(value) { return value || '-'; } },
                      { title: '状态', dataIndex: 'status', render: function render(value) { return <StatusBadge value={value} />; } },
                      { title: '创建者', dataIndex: 'createdBy' },
                    ]}
                  />
                </>
              ),
            },
          ]}
        />
      </div>
      <Drawer title="分配算力规格节点" open={quotaOpen} width={520} onClose={function close() { setQuotaOpen(false); }}>
        <Form form={quotaForm} layout="vertical" onFinish={createQuota}>
          <Form.Item name="specId" label="算力规格" rules={[{ required: true }]}>
            <Select
              placeholder="选择尚有可分配节点的规格"
              options={assignableSpecs.map(function option(spec) {
                const availableNodes = spec.capacityNodes - spec.allocatedNodes;
                return {
                  value: spec.id,
                  label: `${spec.specType === 'EXCLUSIVE' ? '独享' : `共享 ${spec.gpuShare}`} · ${spec.displayName || spec.name} · 可分配 ${availableNodes}`,
                };
              })}
            />
          </Form.Item>
          <Form.Item name="total" label="规格节点数" rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>确认分配规格节点</Button>
        </Form>
      </Drawer>
      <Drawer title="新建项目" open={projectOpen} width={480} onClose={function close() { setProjectOpen(false); }}>
        <Form form={projectForm} layout="vertical" onFinish={createProject}>
          <Form.Item name="name" label="项目名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={4} /></Form.Item>
          <Button type="primary" htmlType="submit" block>创建项目</Button>
        </Form>
      </Drawer>
    </div>
  );
}
