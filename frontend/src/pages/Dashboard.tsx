import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Empty, Spin } from 'antd';
import {
  CloudServerOutlined,
  ApartmentOutlined,
  RocketOutlined,
  AlertOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { clustersApi, deploymentsApi, alertsApi, projectsApi, workspacesApi } from '../api';
import type { PhysicalCluster, Project, Workspace, ModelDeployment } from '../types';
import PageHeader from '../components/PageHeader';
import { PSBC_COLORS } from '../theme';
import { authApi } from '../api/auth';
import { useAuth } from '../contexts/AuthContext';
import { mockAlerts, mockMonitoring } from '../mock/data';

export default function Dashboard() {
  const nav = useNavigate();
  const { setUser } = useAuth();
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [deployments, setDeployments] = useState<ModelDeployment[]>([]);
  const [loading, setLoading] = useState(true);
  const [loginChecking, setLoginChecking] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        setLoginChecking(true);
        // 演示场景下，自动注入 admin token（不再走登录页）
        const u = await authApi.login({ username: 'admin', password: 'admin123' });
        localStorage.setItem('token', u.token);
        setUser({ username: u.username, role: u.role });

        const [cs, ws, ds] = await Promise.all([
          clustersApi.list(),
          workspacesApi.list(),
          deploymentsApi.listByProject('proj-llm'),
        ]);
        setClusters(cs);
        setWorkspaces(ws);
        setDeployments(ds);

        const allProjects: Project[] = [];
        for (const w of ws) {
          const ps = await projectsApi.listByWorkspace(w.id);
          allProjects.push(...ps);
        }
        setProjects(allProjects);
      } finally {
        setLoginChecking(false);
        setLoading(false);
      }
    })();
  }, []);

  if (loading || loginChecking) {
    return (
      <div style={{ padding: 60, textAlign: 'center' }}>
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  const runningDeployments = deployments.filter((d) => d.status === 'running').length;
  const failedDeployments = deployments.filter((d) => d.status === 'failed').length;
  const firingAlerts = mockAlerts.filter((a) => a.status === 'firing').length;
  const mon = mockMonitoring.cluster;
  const cpuPct = Math.round((mon.usedCpuCores / mon.totalCpuCores) * 100);
  const memPct = Math.round((mon.usedMemoryGib / mon.totalMemoryGib) * 100);
  const gpuPct = Math.round((mon.usedGpuCards / mon.totalGpuCards) * 100);

  return (
    <div>
      <PageHeader
        title="平台概览"
        subtitle="ACMP 异构算力管理平台 · 全局运行视图"
      />

      <Row gutter={[16, 16]}>
        <Col span={6}>
          <Card hoverable onClick={() => nav('/clusters')}>
            <Statistic
              title={<span style={{ color: PSBC_COLORS.primary }}>物理集群</span>}
              value={clusters.length}
              prefix={<CloudServerOutlined />}
              valueStyle={{ color: PSBC_COLORS.primary, fontWeight: 700 }}
            />
            <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>
              节点 {clusters.reduce((s, c) => s + (c.maxCpuCores ?? 0) / 24, 0) | 0} 个
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => nav('/logical/workspaces')}>
            <Statistic
              title={<span style={{ color: PSBC_COLORS.primary }}>工作空间</span>}
              value={workspaces.length}
              prefix={<ApartmentOutlined />}
              valueStyle={{ color: PSBC_COLORS.primary, fontWeight: 700 }}
            />
            <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>
              项目 {projects.length} 个
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => nav('/logical/deployments/proj-llm')}>
            <Statistic
              title={<span style={{ color: PSBC_COLORS.primary }}>运行中部署</span>}
              value={runningDeployments}
              prefix={<RocketOutlined />}
              valueStyle={{ color: '#52C41A', fontWeight: 700 }}
              suffix={`/ ${deployments.length}`}
            />
            <div style={{ fontSize: 12, color: '#FAAD14', marginTop: 4 }}>
              失败 {failedDeployments} · 告警 {firingAlerts}
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => nav('/monitoring/alerts')}>
            <Statistic
              title={<span style={{ color: '#FAAD14' }}>活动告警</span>}
              value={firingAlerts}
              prefix={<AlertOutlined />}
              valueStyle={{ color: '#FAAD14', fontWeight: 700 }}
            />
            <div style={{ fontSize: 12, color: '#6B7768', marginTop: 4 }}>
              最近 24h 内
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={14}>
          <Card title="集群资源使用率" style={{ borderRadius: 8 }}>
            <Row gutter={32}>
              <Col span={8}>
                <div style={{ textAlign: 'center' }}>
                  <ProgressRing
                    percent={cpuPct}
                    label="CPU"
                    used={`${mon.usedCpuCores} / ${mon.totalCpuCores} cores`}
                  />
                </div>
              </Col>
              <Col span={8}>
                <div style={{ textAlign: 'center' }}>
                  <ProgressRing
                    percent={memPct}
                    label="内存"
                    used={`${mon.usedMemoryGib} / ${mon.totalMemoryGib} GiB`}
                  />
                </div>
              </Col>
              <Col span={8}>
                <div style={{ textAlign: 'center' }}>
                  <ProgressRing
                    percent={gpuPct}
                    label="GPU 卡"
                    used={`${mon.usedGpuCards} / ${mon.totalGpuCards} 张`}
                  />
                </div>
              </Col>
            </Row>
          </Card>

          <Card title="最近部署" style={{ borderRadius: 8, marginTop: 16 }}>
            {deployments.length === 0 ? (
              <Empty description="暂无部署" />
            ) : (
              deployments.slice(0, 5).map((d) => (
                <div
                  key={d.id}
                  onClick={() => nav(`/logical/deployments/${d.id}`)}
                  style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '10px 0', borderBottom: '1px solid #E5EBE7', cursor: 'pointer',
                  }}
                >
                  <div>
                    <div style={{ fontWeight: 500 }}>{d.name}</div>
                    <div style={{ fontSize: 12, color: '#6B7768' }}>
                      {d.modelName ?? '-'} · {d.poolType} · {d.replicas} 副本
                    </div>
                  </div>
                  <Tag color={d.status === 'running' ? 'green' : d.status === 'failed' ? 'red' : 'orange'}>
                    {d.status}
                  </Tag>
                </div>
              ))
            )}
          </Card>
        </Col>

        <Col span={10}>
          <Card title="最近告警" style={{ borderRadius: 8 }}>
            {mockAlerts.filter((a) => a.status === 'firing').slice(0, 5).map((a) => (
              <div
                key={a.id}
                onClick={() => nav('/monitoring/alerts')}
                style={{
                  padding: '8px 0', borderBottom: '1px solid #E5EBE7', cursor: 'pointer',
                }}
              >
                <Tag color={a.level === 'critical' ? 'red' : a.level === 'warning' ? 'orange' : 'blue'}>
                  {a.level}
                </Tag>
                <div style={{ fontSize: 13, marginTop: 4 }}>{a.message}</div>
                <div style={{ fontSize: 11, color: '#6B7768' }}>来源：{a.source}</div>
              </div>
            ))}
            {mockAlerts.filter((a) => a.status === 'firing').length === 0 && (
              <Empty description="无活动告警" />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}

function ProgressRing({ percent, label, used }: { percent: number; label: string; used: string }) {
  const color = percent > 90 ? '#FF4D4F' : percent > 70 ? '#FAAD14' : '#00754A';
  return (
    <div>
      <svg width={120} height={120} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={60} cy={60} r={50} stroke="#E5EBE7" strokeWidth={10} fill="none" />
        <circle
          cx={60} cy={60} r={50}
          stroke={color} strokeWidth={10} fill="none"
          strokeDasharray={`${2 * Math.PI * 50}`}
          strokeDashoffset={`${2 * Math.PI * 50 * (1 - percent / 100)}`}
          strokeLinecap="round"
        />
      </svg>
      <div style={{ marginTop: 8, fontSize: 24, fontWeight: 700, color }}>
        {percent}%
      </div>
      <div style={{ fontSize: 13, color: '#6B7768' }}>{label}</div>
      <div style={{ fontSize: 11, color: '#9CA8A0' }}>{used}</div>
    </div>
  );
}

import { Tag } from 'antd';
import { Progress } from 'antd';