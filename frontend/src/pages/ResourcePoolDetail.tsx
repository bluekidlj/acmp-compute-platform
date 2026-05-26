import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Tag, Typography, Spin, Table, Space, Button, Empty, Divider,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { resourcePoolApi } from '../api/resourcePools';
import type { ResourcePool, SpecQuota } from '../types';

const { Title, Text } = Typography;

const ResourcePoolDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [pool, setPool] = useState<ResourcePool | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    (async () => {
      try {
        const res = await resourcePoolApi.get(id);
        setPool(res.data);
      } catch { /* handled */ }
      finally { setLoading(false); }
    })();
  }, [id]);

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 120 }} />;
  if (!pool) return <Empty description="资源池不存在" />;

  const quotaColumns = [
    {
      title: '规格名称', dataIndex: 'specName', key: 'specName',
      render: (v: string) => <Text code>{v}</Text>,
    },
    { title: '总配额', dataIndex: 'totalQuota', key: 'totalQuota' },
    { title: '已分配', dataIndex: 'allocatedQuota', key: 'allocatedQuota' },
    {
      title: '可用', dataIndex: 'availableQuota', key: 'availableQuota',
      render: (v: number, record: SpecQuota) => (
        <Tag color={v > 0 ? 'green' : 'red'}>
          {v} / {record.totalQuota}
        </Tag>
      ),
    },
    {
      title: '使用率', key: 'usage',
      render: (_: unknown, record: SpecQuota) => {
        const pct = record.totalQuota > 0 ? Math.round((record.allocatedQuota / record.totalQuota) * 100) : 0;
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

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/resource-pools')}>
          返回列表
        </Button>
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

      <Card title="规格配额" style={{ borderRadius: 10 }}>
        <Table
          columns={quotaColumns}
          dataSource={pool.specQuotas}
          rowKey="specId"
          pagination={false}
          size="small"
        />
      </Card>
    </div>
  );
};

export default ResourcePoolDetailPage;
