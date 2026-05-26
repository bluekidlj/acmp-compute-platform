import React, { useEffect, useState } from 'react';
import { Row, Col, Card, Statistic, Typography, Spin, Space } from 'antd';
import {
  CloudServerOutlined,
  AppstoreOutlined,
  HddOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { physicalClusterApi } from '../api/physicalClusters';
import { resourcePoolApi } from '../api/resourcePools';
import { workspaceApi } from '../api/workspaces';
import { useNavigate } from 'react-router-dom';

const { Title } = Typography;

const DashboardPage: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState({ clusters: 0, pools: 0, workspaces: 0, deployments: 0 });
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [clusters, pools, workspaces] = await Promise.all([
          physicalClusterApi.list(),
          resourcePoolApi.list(),
          workspaceApi.list(),
        ]);
        if (!cancelled) {
          setStats({
            clusters: clusters.data.length,
            pools: pools.data.length,
            workspaces: workspaces.data.length,
            deployments: 0,
          });
        }
      } catch {
        // ignore
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => { cancelled = true; };
  }, []);

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 120 }} />;

  const cards = [
    {
      title: '物理集群',
      value: stats.clusters,
      icon: <CloudServerOutlined style={{ fontSize: 32, color: '#1677ff' }} />,
      path: '/physical-clusters',
      color: '#e6f4ff',
    },
    {
      title: '逻辑资源池',
      value: stats.pools,
      icon: <AppstoreOutlined style={{ fontSize: 32, color: '#52c41a' }} />,
      path: '/resource-pools',
      color: '#f6ffed',
    },
    {
      title: '工作空间',
      value: stats.workspaces,
      icon: <HddOutlined style={{ fontSize: 32, color: '#722ed1' }} />,
      path: '/workspaces',
      color: '#f9f0ff',
    },
    {
      title: '在线推理服务',
      value: stats.deployments,
      icon: <RocketOutlined style={{ fontSize: 32, color: '#fa8c16' }} />,
      path: '/workspaces',
      color: '#fff7e6',
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>平台概览</Title>
      </div>

      <Row gutter={[16, 16]}>
        {cards.map((card) => (
          <Col xs={24} sm={12} lg={6} key={card.title}>
            <Card
              hoverable
              onClick={() => navigate(card.path)}
              style={{ borderRadius: 10, background: card.color, border: 'none' }}
            >
              <Statistic
                title={card.title}
                value={card.value}
                prefix={card.icon}
                valueStyle={{ fontWeight: 700 }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      <Card style={{ marginTop: 24, borderRadius: 10 }}>
        <Title level={5}>快速入门</Title>
        <Space direction="vertical" size="small">
          <p>✅ <strong>平台管理员</strong>：先注册物理集群 → 查看算力规格 → 创建逻辑资源池 → 创建工作空间并分配配额</p>
          <p>✅ <strong>部门管理员</strong>：在已有资源池下创建工作空间 → 添加成员 → 签发凭证</p>
          <p>✅ <strong>训练/推理用户</strong>：进入工作空间 → 部署推理服务 / 提交训练任务</p>
        </Space>
      </Card>
    </div>
  );
};

export default DashboardPage;
