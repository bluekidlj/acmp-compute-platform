import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Tag, Typography, Spin, Table, Space, Button, Empty, Divider,
  Modal, Form, Input, InputNumber, message,
} from 'antd';
import {
  ArrowLeftOutlined, KeyOutlined, EyeOutlined,
} from '@ant-design/icons';
import { resourcePoolApi } from '../api/resourcePools';
import { workspaceApi } from '../api/workspaces';
import type { ResourcePool, SpecQuota, Workspace, IssueCredentialRequest } from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title, Text } = Typography;

const ResourcePoolDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { isAdmin } = useAuth();

  const [pool, setPool] = useState<ResourcePool | null>(null);
  const [loading, setLoading] = useState(true);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);

  // credential issue
  const [credOpen, setCredOpen] = useState(false);
  const [credForm] = Form.useForm();
  const [credResult, setCredResult] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    (async () => {
      try {
        const [poolRes, wsRes] = await Promise.all([
          resourcePoolApi.get(id),
          workspaceApi.list(),
        ]);
        setPool(poolRes.data);
        // filter workspaces belonging to this pool
        setWorkspaces(wsRes.data.filter((w) => w.resourcePoolId === id));
      } catch { /* handled */ }
      finally { setLoading(false); }
    })();
  }, [id]);

  const handleIssueCred = async () => {
    const values = await credForm.validateFields();
    try {
      const res = await resourcePoolApi.issueCredential(id!, values as IssueCredentialRequest);
      setCredResult(res.data.kubeconfig || JSON.stringify(res.data, null, 2));
      message.success('凭证已签发');
    } catch { /* handled */ }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 120 }} />;
  if (!pool) return <Empty description="资源池不存在" />;

  const quotaColumns = [
    {
      title: '规格名称', dataIndex: 'specName', key: 'specName',
      render: (v: string) => <Text code>{v}</Text>,
    },
    { title: '总节点数', dataIndex: 'totalNodes', key: 'totalNodes' },
    { title: '已分配', dataIndex: 'allocatedNodes', key: 'allocatedNodes' },
    {
      title: '可用', dataIndex: 'availableNodes', key: 'availableNodes',
      render: (v: number, record: SpecQuota) => (
        <Tag color={v > 0 ? 'green' : 'red'}>
          {v} / {record.totalNodes}
        </Tag>
      ),
    },
    {
      title: '使用率', key: 'usage',
      render: (_: unknown, record: SpecQuota) => {
        const pct = record.totalNodes > 0 ? Math.round((record.allocatedNodes / record.totalNodes) * 100) : 0;
        return (
          <div style={{ width: 120, height: 8, background: '#f0f0f0', borderRadius: 4 }}>
            <div
              style={{
                width: `${pct}%`,
                height: '100%',
                background: pct >= 100 ? '#ff4d4f' : pct >= 70 ? '#faad14' : '#52c41a',
                borderRadius: 4,
              }}
            />
          </div>
        );
      },
    },
  ];

  const wsColumns = [
    { title: '名称', dataIndex: 'name', ellipsis: true },
    {
      title: 'Namespace', dataIndex: 'namespace', ellipsis: true,
      render: (v: string) => <Text code className="mono">{v}</Text>,
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: string) => v === 'active' ? <Tag color="green">活跃</Tag> : <Tag color="red">停用</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: unknown, record: Workspace) => (
        <Button size="small" type="link" icon={<EyeOutlined />}
          onClick={() => navigate(`/workspaces/${record.id}`)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/resource-pools')}>
          返回列表
        </Button>
        {isAdmin && (
          <Button icon={<KeyOutlined />} onClick={() => { setCredOpen(true); credForm.resetFields(); setCredResult(null); }}>
            签发凭证
          </Button>
        )}
      </Space>

      <Card title={<Title level={4} style={{ margin: 0 }}>{pool.name}</Title>} style={{ borderRadius: 10 }}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label="ID"><Text code>{pool.id}</Text></Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={pool.status === 'active' ? 'green' : 'red'}>
              {pool.status === 'active' ? '正常' : '停用'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="部门编码">{pool.departmentCode}</Descriptions.Item>
          <Descriptions.Item label="部门名称">{pool.departmentName}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>{pool.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="关联物理集群" span={2}>
            {pool.physicalClusterIds?.map((cid) => (
              <Tag key={cid}>{cid}</Tag>
            ))}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间" span={2}>{pool.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Divider />

      <Card title="规格配额" style={{ borderRadius: 10, marginBottom: 16 }}>
        <Table
          columns={quotaColumns}
          dataSource={pool.specQuotas}
          rowKey="specId"
          pagination={false}
          size="small"
        />
      </Card>

      <Card title={`关联工作空间 (${workspaces.length})`} style={{ borderRadius: 10 }}>
        {workspaces.length === 0 ? (
          <Empty description="暂无工作空间" />
        ) : (
          <Table
            columns={wsColumns}
            dataSource={workspaces}
            rowKey="id"
            pagination={false}
            size="small"
          />
        )}
      </Card>

      {/* Credential Issue Modal */}
      <Modal
        title="签发资源池凭证"
        open={credOpen}
        onOk={handleIssueCred}
        onCancel={() => { setCredOpen(false); setCredResult(null); }}
        okText="签发"
      >
        <Form form={credForm} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input placeholder="zhangsan" />
          </Form.Item>
          <Form.Item name="expireDays" label="有效期（天）" rules={[{ required: true }]}>
            <InputNumber min={1} max={365} style={{ width: '100%' }} placeholder="30" />
          </Form.Item>
        </Form>
        {credResult && (
          <div style={{ marginTop: 12 }}>
            <Text strong>生成的 Kubeconfig：</Text>
            <pre style={{
              maxHeight: 200, overflow: 'auto', background: '#f5f5f5',
              padding: 8, borderRadius: 4, fontSize: 12, marginTop: 8,
            }}>
              {credResult}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default ResourcePoolDetailPage;
