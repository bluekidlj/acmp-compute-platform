import { useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Drawer, Form, Input, Select, Table, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { Project, Tenant } from '../../types';

interface CreateProjectForm {
  tenantId: string;
  name: string;
  description?: string;
}

export default function ProjectsPage() {
  const navigate = useNavigate();
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [items, setItems] = useState<Project[]>([]);
  const [tenantId, setTenantId] = useState<string>();
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<CreateProjectForm>();

  function load() {
    api.tenants()
      .then(async function loadProjects(nextTenants) {
        setTenants(nextTenants);

        const groups = await Promise.all(nextTenants.map(function query(tenant) {
          return api.projects(tenant.id);
        }));

        setItems(groups.flat());
      })
      .catch(function handleFailure(exception) {
        message.error(exception.message);
      });
  }

  useEffect(function loadPage() {
    load();
  }, []);

  function openCreateDrawer() {
    form.resetFields();

    if (tenantId) {
      form.setFieldValue('tenantId', tenantId);
    }

    setCreateOpen(true);
  }

  async function createProject(values: CreateProjectForm) {
    setCreating(true);

    try {
      await api.createProject(values.tenantId, {
        name: values.name,
        description: values.description,
      });

      message.success('项目已创建');
      setCreateOpen(false);
      form.resetFields();
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '项目创建失败');
    } finally {
      setCreating(false);
    }
  }

  const tenantMap = Object.fromEntries(tenants.map(function mapTenant(tenant) {
    return [tenant.id, tenant.name];
  }));

  const filtered = tenantId
    ? items.filter(function match(project) {
      return project.tenantId === tenantId;
    })
    : items;

  return (
    <div>
      <div className="page-heading">
        <div>
          <h1>项目</h1>
          <p>项目继承所属租户的算力规格配额</p>
        </div>

        <div className="page-actions">
          <Select
            allowClear
            placeholder="按租户筛选"
            style={{ width: 240 }}
            value={tenantId}
            onChange={setTenantId}
            options={tenants.map(function toOption(tenant) {
              return {
                value: tenant.id,
                label: tenant.name,
              };
            })}
          />

          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={tenants.length === 0}
            onClick={openCreateDrawer}
          >
            新建项目
          </Button>
        </div>
      </div>

      <div className="surface data-table">
        <Table
          rowKey="id"
          dataSource={filtered}
          pagination={false}
          onRow={function buildRow(record) {
            return {
              onClick: function goToProject() {
                navigate(`/projects/${record.id}`);
              },
              style: {
                cursor: 'pointer',
              },
            };
          }}
          columns={[
            {
              title: '项目名称',
              dataIndex: 'name',
              render: function renderName(value) {
                return <strong>{value}</strong>;
              },
            },
            {
              title: '所属租户',
              dataIndex: 'tenantId',
              render: function renderTenant(value) {
                return tenantMap[value] || value;
              },
            },
            {
              title: '描述',
              dataIndex: 'description',
              render: function renderDescription(value) {
                return value || '-';
              },
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 120,
              render: function renderStatus(value) {
                return <StatusBadge value={value} />;
              },
            },
            {
              title: '创建者',
              dataIndex: 'createdBy',
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              render: function renderCreatedAt(value) {
                return new Date(value).toLocaleString('zh-CN');
              },
            },
          ]}
        />
      </div>

      <Drawer
        title="新建项目"
        open={createOpen}
        width={480}
        onClose={function closeDrawer() {
          setCreateOpen(false);
        }}
      >
        <Form form={form} layout="vertical" onFinish={createProject}>
          <Form.Item
            name="tenantId"
            label="所属租户"
            rules={[{ required: true, message: '请选择所属租户' }]}
          >
            <Select
              placeholder="请选择租户"
              options={tenants.map(function toOption(tenant) {
                return {
                  value: tenant.id,
                  label: tenant.name,
                };
              })}
            />
          </Form.Item>

          <Form.Item
            name="name"
            label="项目名称"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input placeholder="请输入项目名称" />
          </Form.Item>

          <Form.Item name="description" label="项目描述">
            <Input.TextArea rows={4} placeholder="请输入项目用途或说明" />
          </Form.Item>

          <Button type="primary" htmlType="submit" loading={creating} block>
            创建项目
          </Button>
        </Form>
      </Drawer>
    </div>
  );
}
