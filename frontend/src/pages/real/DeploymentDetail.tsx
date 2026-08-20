import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, DeleteOutlined, MessageOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, message, Popconfirm, Space, Spin, Steps, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ComputeSpec, ModelDeployment, Project, ResourcePool, Tenant } from '../../types';

export default function DeploymentDetailPage() {
  const { projectId = '', deploymentId = '' } = useParams();
  const navigate = useNavigate();
  const [deployment, setDeployment] = useState<ModelDeployment | null>(null);
  const [project, setProject] = useState<Project | null>(null);
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [spec, setSpec] = useState<ComputeSpec | null>(null);
  const [pool, setPool] = useState<ResourcePool | null>(null);
  const [loading, setLoading] = useState(true);

  async function load() {
    try {
      const nextDeployment = await api.deployment(projectId, deploymentId);
      const [nextProject, specs, pools] = await Promise.all([api.project(projectId), api.specs(), api.pools()]);
      const nextTenant = await api.tenant(nextProject.tenantId);
      setDeployment(nextDeployment);
      setProject(nextProject);
      setTenant(nextTenant);
      setSpec(specs.find(function find(item) { return item.id === nextDeployment.specId; }) || null);
      setPool(pools.find(function find(item) { return item.id === nextDeployment.resourcePoolId; }) || null);
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '部署加载失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(function pollStatus() {
    load();
    const timer = window.setInterval(function refresh() {
      api.deployment(projectId, deploymentId)
        .then(function update(next) {
          setDeployment(next);
          if (next.status === 'RUNNING' || next.status === 'FAILED') {
            window.clearInterval(timer);
          }
        })
        .catch(function ignorePollFailure() {
          window.clearInterval(timer);
        });
    }, 3000);
    return function cleanup() {
      window.clearInterval(timer);
    };
  }, [projectId, deploymentId]);

  async function remove() {
    try {
      await api.deleteDeployment(projectId, deploymentId);
      message.success('推理服务和 Kubernetes 对象已删除');
      navigate('/deployments');
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败');
    }
  }

  if (loading || !deployment) {
    return <Spin size="large" />;
  }

  const currentStep = deployment.status === 'PENDING' ? 0 : deployment.status === 'SUBMITTED' ? 1 : 2;
  const stepStatus = deployment.status === 'FAILED' ? 'error' : deployment.status === 'RUNNING' ? 'finish' : 'process';

  return (
    <div>
      <div className="page-heading">
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={function back() { navigate('/deployments'); }} style={{ padding: 0 }}>返回推理服务</Button>
          <h1>{deployment.name}</h1>
          <p>{tenant?.name} · {project?.name} · {deployment.modelName || '未命名模型'}</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<MessageOutlined />} disabled={deployment.status !== 'RUNNING'} onClick={function chat() { navigate(`/deployments/${projectId}/${deploymentId}/chat`); }}>测试对话</Button>
          <Popconfirm title="确认删除推理服务及其 Kubernetes 对象？" onConfirm={remove}>
            <Button danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      </div>

      <div className="surface" style={{ padding: 24, marginBottom: 16 }}>
        <Steps
          current={currentStep}
          status={stepStatus}
          items={[
            { title: '记录创建', description: 'PENDING' },
            { title: '提交 Kubernetes', description: 'SUBMITTED' },
            { title: '副本就绪', description: deployment.status },
          ]}
        />
      </div>

      {deployment.failureMessage && (
        <Alert type="error" showIcon message="部署失败" description={deployment.failureMessage} style={{ marginBottom: 16 }} />
      )}

      <div className="detail-grid">
        <div className="surface" style={{ padding: 22 }}>
          <Descriptions title="部署信息" column={2} size="small">
            <Descriptions.Item label="状态"><StatusBadge value={deployment.status} /></Descriptions.Item>
            <Descriptions.Item label="就绪副本">{deployment.readyReplicas ?? 0} / {deployment.replicas}</Descriptions.Item>
            <Descriptions.Item label="模型">{deployment.modelName || '-'}</Descriptions.Item>
            <Descriptions.Item label="端口">{deployment.port}</Descriptions.Item>
            <Descriptions.Item label="镜像" span={2}><code>{deployment.vllmImage || '-'}</code></Descriptions.Item>
            <Descriptions.Item label="模型路径" span={2}><code>{deployment.modelIdOrPath || '-'}</code></Descriptions.Item>
            <Descriptions.Item label="每副本 GPU 数">{deployment.gpuCountPerReplica ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="Tensor 并行度">{deployment.tensorParallelSize ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="GPU 内存利用率">{deployment.gpuMemoryUtilization ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="最大上下文">{deployment.maxModelLength ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="Service URL" span={2}><code>{deployment.serviceUrl || '-'}</code></Descriptions.Item>
            <Descriptions.Item label="Deployment" span={2}><code>{deployment.k8sDeploymentName}</code></Descriptions.Item>
            <Descriptions.Item label="Service" span={2}><code>{deployment.k8sServiceName}</code></Descriptions.Item>
            <Descriptions.Item label="集群"><code>{deployment.actualClusterId}</code></Descriptions.Item>
            <Descriptions.Item label="创建时间">{deployment.createdAt ? new Date(deployment.createdAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
          </Descriptions>
        </div>
        <div className="surface" style={{ padding: 22 }}>
          <Descriptions title="算力资源" column={1} size="small">
            <Descriptions.Item label="算力规格">{spec?.displayName || spec?.name || deployment.specId}</Descriptions.Item>
            <Descriptions.Item label="资源池">{pool?.name || deployment.resourcePoolId}</Descriptions.Item>
            <Descriptions.Item label="类型"><Tag>{spec?.specType === 'SHARED' ? '共享' : '独享'}</Tag></Descriptions.Item>
            <Descriptions.Item label="CPU">{spec ? `${spec.cpuCores} Core` : '-'}</Descriptions.Item>
            <Descriptions.Item label="内存">{spec ? `${spec.memoryGib} GiB` : '-'}</Descriptions.Item>
            <Descriptions.Item label="Gpu">{spec ? `${deployment.gpuCountPerReplica ?? spec.gpuCount} · ${spec.gpuShare || '整卡'}` : '-'}</Descriptions.Item>
          </Descriptions>
        </div>
      </div>
    </div>
  );
}
