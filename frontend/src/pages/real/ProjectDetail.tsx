import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Form, Input, InputNumber, message, Select, Space, Steps, Table, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ComputeSpec, DeploymentRequest, Model, ModelDeployment, Project, Tenant, TenantSpecQuota } from '../../types';

interface DeployForm {
  name: string;
  modelId: string;
  specId: string;
  image: string;
  port: number;
  replicas: number;
  modelPath: string;
  maxModelLength?: number;
}

export default function ProjectDetailPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const [project, setProject] = useState<Project | null>(null);
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [quotas, setQuotas] = useState<TenantSpecQuota[]>([]);
  const [specs, setSpecs] = useState<ComputeSpec[]>([]);
  const [models, setModels] = useState<Model[]>([]);
  const [deployments, setDeployments] = useState<ModelDeployment[]>([]);
  const [drawer, setDrawer] = useState(false);
  const [step, setStep] = useState(0);
  const [form] = Form.useForm<DeployForm>();

  function load() {
    api.project(projectId)
      .then(async function loadRelated(nextProject) {
        const values = await Promise.all([
          api.tenant(nextProject.tenantId),
          api.availableSpecs(projectId),
          api.specs(),
          api.models(),
          api.deployments({ projectId }),
        ]);
        setProject(nextProject);
        setTenant(values[0]);
        setQuotas(values[1]);
        setSpecs(values[2]);
        setModels(values[3]);
        setDeployments(values[4]);
      })
      .catch(function fail(exception) { message.error(exception.message); });
  }
  useEffect(load, [projectId]);

  function openDeploy() {
    setStep(0);
    form.resetFields();
    form.setFieldsValue({
      image: 'vllm/vllm-openai:0.10.0',
      port: 8000,
      replicas: 1,
      modelPath: '/models/Qwen2.5-3B-Instruct',
    });
    setDrawer(true);
  }

  async function submit(values: DeployForm) {
    const model = models.find(function find(item) { return item.id === values.modelId; });
    const spec = specs.find(function find(item) { return item.id === values.specId; });
    if (!model || !spec) {
      message.error('模型或算力规格不存在');
      return;
    }
    const argsParts = [
      `serve ${values.modelPath}`,
      `--served-model-name ${model.displayName || model.name}`,
      '--host 0.0.0.0',
      `--port ${values.port}`,
    ];
    if (values.maxModelLength) {
      argsParts.push(`--max-model-len ${values.maxModelLength}`);
    }
    const body: DeploymentRequest = {
      name: values.name,
      specName: spec.name,
      replicas: values.replicas,
      image: values.image,
      port: values.port,
      command: 'vllm',
      args: argsParts.join(' '),
      modelId: model.id,
      modelSource: model.modelSource,
      modelIdOrPath: values.modelPath,
      modelName: model.displayName || model.name,
    };
    try {
      const created = await api.createDeployment(projectId, body);
      message.success('推理服务已提交 Kubernetes');
      setDrawer(false);
      navigate(`/deployments/${projectId}/${created.id}`);
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '部署失败');
    }
  }

  if (!project) {
    return null;
  }
  const specMap = Object.fromEntries(specs.map(function map(item) { return [item.id, item]; }));

  return (
    <div>
      <div className="page-heading">
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={function back() { navigate('/projects'); }} style={{ padding: 0 }}>返回项目</Button>
          <h1>{project.name}</h1>
          <p>{tenant?.name} · {project.description || '推理项目'}</p>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openDeploy}>新建推理服务</Button>
      </div>
      <div className="surface" style={{ padding: 20, marginBottom: 16 }}>
        <Descriptions column={3}>
          <Descriptions.Item label="租户">{tenant?.name || project.tenantId}</Descriptions.Item>
          <Descriptions.Item label="状态"><StatusBadge value={project.status} /></Descriptions.Item>
          <Descriptions.Item label="创建者">{project.createdBy}</Descriptions.Item>
        </Descriptions>
      </div>
      <div className="metric-grid">
        {quotas.map(function quotaCard(quota) {
          const spec = specMap[quota.specId];
          return (
            <div className="surface metric" key={quota.id}>
              <div className="metric-label">{quota.specName}</div>
              <div className="metric-value">{quota.remaining} <span style={{ fontSize: 13 }}>可用节点</span></div>
              <div className="metric-hint">{spec ? `${spec.cpuCores} Core · ${spec.memoryGib} GiB · ${spec.gpuShare || '独享'}` : `已用 ${quota.used} / ${quota.total}`}</div>
            </div>
          );
        })}
      </div>
      <div className="surface data-table">
        <div className="toolbar"><strong>推理服务</strong><span>{deployments.length} 个部署</span></div>
        <Table
          rowKey="id"
          dataSource={deployments}
          pagination={false}
          onRow={function row(record) { return { onClick: function go() { navigate(`/deployments/${projectId}/${record.id}`); }, style: { cursor: 'pointer' } }; }}
          columns={[
            { title: '服务名称', dataIndex: 'name', render: function render(value) { return <strong>{value}</strong>; } },
            { title: '模型', dataIndex: 'modelName' },
            { title: '算力规格', dataIndex: 'specId', render: function render(value) { return specMap[value]?.displayName || specMap[value]?.name || value; } },
            { title: '端口', dataIndex: 'port', width: 90 },
            { title: '副本', render: function render(_, record: ModelDeployment) { return `${record.readyReplicas ?? 0} / ${record.replicas}`; } },
            { title: '状态', dataIndex: 'status', render: function render(value) { return <StatusBadge value={value} />; } },
          ]}
        />
      </div>

      <Drawer title="部署推理服务" open={drawer} width={620} onClose={function close() { setDrawer(false); }}>
        <Steps current={step} size="small" items={[{ title: '业务信息' }, { title: '算力规格' }, { title: '运行配置' }]} style={{ marginBottom: 26 }} />
        <Form form={form} layout="vertical" onFinish={submit}>
          <div style={{ display: step === 0 ? 'block' : 'none' }}>
            <Alert type="info" showIcon message={`所属租户：${tenant?.name}　所属项目：${project.name}`} style={{ marginBottom: 16 }} />
            <Form.Item name="name" label="服务名称" rules={[{ required: true }]}><Input placeholder="qwen25-3b-demo" /></Form.Item>
            <Form.Item name="modelId" label="模型" rules={[{ required: true }]}>
              <Select options={models.map(function option(model) { return { value: model.id, label: `${model.displayName || model.name} · ${model.storagePath}` }; })} />
            </Form.Item>
          </div>
          <div style={{ display: step === 1 ? 'block' : 'none' }}>
            <Form.Item name="specId" label="可用算力规格" rules={[{ required: true }]}>
              <Select
                optionLabelProp="label"
                options={quotas.map(function option(quota) {
                  const spec = specMap[quota.specId];
                  return {
                    value: quota.specId,
                    label: spec?.displayName || quota.specName,
                    disabled: quota.remaining <= 0,
                    title: `${spec?.cpuCores} Core · ${spec?.memoryGib} GiB · ${spec?.gpuShare || '独享'} · 剩余 ${quota.remaining}`,
                  };
                })}
                optionRender={function renderOption(option) {
                  return <div><strong>{option.data.label}</strong><div style={{ color: '#66756f', fontSize: 12 }}>{option.data.title}</div></div>;
                }}
              />
            </Form.Item>
            <Form.Item
              name="replicas"
              label="算力规格节点数"
              rules={[{ required: true, message: '请输入算力规格节点数' }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <div style={{ display: step === 2 ? 'block' : 'none' }}>
            <Form.Item name="image" label="vLLM 镜像" rules={[{ required: true }]}><Input className="mono" /></Form.Item>
            <Form.Item name="modelPath" label="容器内模型路径" rules={[{ required: true }]}><Input className="mono" /></Form.Item>
            <Space style={{ width: '100%' }} align="start">
              <Form.Item name="port" label="服务端口" rules={[{ required: true }]}><InputNumber min={1} max={65535} /></Form.Item>
              <Form.Item name="maxModelLength" label="最大上下文（可选）"><InputNumber min={512} step={512} /></Form.Item>
            </Space>
            <Alert type="warning" showIcon message="Docker Desktop 无真实 CUDA；真实 Qwen 3B 请部署到内网 Gpu 集群。" />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 24 }}>
            <Button disabled={step === 0} onClick={function previous() { setStep(step - 1); }}>上一步</Button>
            {step < 2 ? <Button type="primary" onClick={async function next() { try { await form.validateFields(step === 0 ? ['name', 'modelId'] : ['specId', 'replicas']); setStep(step + 1); } catch { return; } }}>下一步</Button> : <Button type="primary" htmlType="submit">提交部署</Button>}
          </div>
        </Form>
      </Drawer>
    </div>
  );
}
