import { useEffect, useState } from 'react';
import { ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Form, Input, InputNumber, message, Popconfirm, Select, Space, Steps, Table, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ComputeSpec, DeploymentRequest, Model, ModelDeployment, Project, Tenant, TenantSpecQuota } from '../../types';

interface DeployForm {
  name: string;
  runtimeMode: 'DEMO' | 'VLLM';
  modelId?: string;
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
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<DeployForm>();
  const runtimeMode = Form.useWatch('runtimeMode', form) || 'DEMO';
  const selectedSpecId = Form.useWatch('specId', form);
  const selectedModelId = Form.useWatch('modelId', form);

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
      runtimeMode: 'DEMO',
      image: 'hashicorp/http-echo:1.0.0',
      port: 5678,
      replicas: 1,
      modelPath: '/models',
    });
    setDrawer(true);
  }

  async function submit(values: DeployForm) {
    const spec = specs.find(function find(item) { return item.id === values.specId; });
    const model = models.find(function find(item) { return item.id === values.modelId; });
    if (!spec) {
      message.error('算力规格不存在');
      return;
    }
    if (values.runtimeMode === 'VLLM' && !model) {
      message.error('请选择真实推理使用的模型');
      return;
    }

    const demoMode = values.runtimeMode === 'DEMO';
    const modelName = demoMode ? 'acmp-demo-model' : (model?.displayName || model?.name || '');
    const argsParts = demoMode
      ? ['-listen=:5678', '-text=ACMP-demo-inference-service-ready']
      : [
          `serve ${values.modelPath}`,
          `--served-model-name ${modelName}`,
          '--host 0.0.0.0',
          `--port ${values.port}`,
        ];
    if (!demoMode && values.maxModelLength) {
      argsParts.push(`--max-model-len ${values.maxModelLength}`);
    }
    const body: DeploymentRequest = {
      name: values.name,
      specName: spec.name,
      replicas: values.replicas,
      image: values.image,
      port: values.port,
      command: demoMode ? '/http-echo' : 'vllm',
      args: argsParts.join(' '),
      modelId: demoMode ? undefined : model?.id,
      modelSource: demoMode ? 'without_weights' : model?.modelSource,
      modelIdOrPath: values.modelPath,
      modelName,
    };
    setSubmitting(true);
    try {
      const created = await api.createDeployment(projectId, body);
      message.success('推理服务已提交 Kubernetes');
      setDrawer(false);
      navigate(`/deployments/${projectId}/${created.id}`);
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '部署失败');
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmDeployment() {
    try {
      const values = await form.validateFields();
      await submit(values);
    } catch {
      // 校验错误由表单字段直接显示，不能触发部署接口。
    }
  }

  if (!project) {
    return null;
  }
  const specMap = Object.fromEntries(specs.map(function map(item) { return [item.id, item]; }));
  const selectedQuota = quotas.find(function findQuota(item) {
    return item.specId === selectedSpecId;
  });
  const selectedModel = models.find(function findModel(item) {
    return item.id === selectedModelId;
  });

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
        <Form form={form} layout="vertical">
          <div style={{ display: step === 0 ? 'block' : 'none' }}>
            <Alert type="info" showIcon message={`所属租户：${tenant?.name}　所属项目：${project.name}`} style={{ marginBottom: 16 }} />
            <Form.Item name="name" label="服务名称" rules={[{ required: true }]}><Input placeholder="qwen25-3b-demo" /></Form.Item>
            <Form.Item name="runtimeMode" label="运行模式" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'DEMO', label: '流程演示镜像（轻量，无需真实模型）' },
                  { value: 'VLLM', label: 'vLLM 真实推理（需要真实 GPU 和模型）' },
                ]}
                onChange={function changeRuntime(value: 'DEMO' | 'VLLM') {
                  if (value === 'DEMO') {
                    form.setFieldsValue({
                      image: 'hashicorp/http-echo:1.0.0',
                      port: 5678,
                      modelId: undefined,
                      modelPath: '/models',
                      maxModelLength: undefined,
                    });
                  } else {
                    form.setFieldsValue({
                      image: 'vllm/vllm-openai:0.10.0',
                      port: 8000,
                      modelPath: '/models/Qwen2.5-3B-Instruct',
                    });
                  }
                }}
              />
            </Form.Item>
            {runtimeMode === 'VLLM' && (
              <Form.Item name="modelId" label="模型" rules={[{ required: true }]}>
                <Select
                  options={models.map(function option(model) {
                    return {
                      value: model.id,
                      label: `${model.displayName || model.name} · ${model.storagePath}`,
                    };
                  })}
                  onChange={function changeModel(modelId: string) {
                    const model = models.find(function find(item) {
                      return item.id === modelId;
                    });
                    if (model) {
                      form.setFieldValue('modelPath', `/models/${model.name}`);
                    }
                  }}
                />
              </Form.Item>
            )}
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
              label="副本数"
              extra={selectedQuota
                ? `每个副本占用 1 个规格节点，当前最多可部署 ${selectedQuota.remaining} 个副本`
                : '请选择算力规格后设置副本数'}
              rules={[{ required: true, message: '请输入副本数' }]}
            >
              <InputNumber
                min={1}
                max={selectedQuota?.remaining}
                precision={0}
                disabled={!selectedQuota}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </div>
          <div style={{ display: step === 2 ? 'block' : 'none' }}>
            <Form.Item name="image" label="容器镜像" rules={[{ required: true }]}><Input className="mono" /></Form.Item>
            {runtimeMode === 'VLLM' && (
              <>
                <Form.Item
                  label="GPU 主机模型绝对目录"
                  extra="来自模型广场登记信息，将写入 Kubernetes volumes.hostPath.path。"
                >
                  <Input
                    className="mono"
                    readOnly
                    value={selectedModel?.storagePath || ''}
                    placeholder="/data/acmp/models/Qwen2.5-3B-Instruct"
                  />
                </Form.Item>
                <Form.Item
                  name="modelPath"
                  label="容器内模型路径"
                  extra="示例：/models/Qwen2.5-3B-Instruct；将写入 volumeMounts.mountPath 和 vllm serve 参数。"
                  rules={[
                    { required: true, message: '请输入容器内模型路径' },
                    { pattern: /^\//, message: '请输入以 / 开头的容器绝对路径' },
                  ]}
                >
                  <Input className="mono" placeholder="/models/Qwen2.5-3B-Instruct" />
                </Form.Item>
              </>
            )}
            <Space style={{ width: '100%' }} align="start">
              <Form.Item name="port" label="服务端口" rules={[{ required: true }]}><InputNumber min={1} max={65535} /></Form.Item>
              {runtimeMode === 'VLLM' && (
                <Form.Item name="maxModelLength" label="最大上下文（可选）"><InputNumber min={512} step={512} /></Form.Item>
              )}
            </Space>
            {runtimeMode === 'DEMO' ? (
              <Alert
                type="success"
                showIcon
                message="演示模式仍会申请所选 GPU 规格，并真实创建 Deployment、Pod 和 Service；不加载模型文件。"
              />
            ) : (
              <Alert type="warning" showIcon message="Docker Desktop 无真实 CUDA；真实模型请部署到内网 GPU 集群。" />
            )}
            <Alert
              type="info"
              showIcon
              message="请检查镜像、模型路径、端口和最大上下文，确认无误后再提交部署。"
              style={{ marginTop: 12 }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 24 }}>
            <Button
              htmlType="button"
              disabled={step === 0 || submitting}
              onClick={function previous() { setStep(step - 1); }}
            >
              上一步
            </Button>
            {step < 2 ? (
              <Button
                type="primary"
                htmlType="button"
                onClick={async function next() {
                  try {
                    const stepFields = step === 0
                      ? (runtimeMode === 'VLLM' ? ['name', 'runtimeMode', 'modelId'] : ['name', 'runtimeMode'])
                      : ['specId', 'replicas'];
                    await form.validateFields(stepFields);
                    setStep(step + 1);
                  } catch {
                    return;
                  }
                }}
              >
                下一步
              </Button>
            ) : (
              <Popconfirm
                title="确认提交推理服务？"
                description="提交后将创建 Kubernetes Deployment 和 Service。"
                okText="确认部署"
                cancelText="继续修改"
                onConfirm={confirmDeployment}
              >
                <Button type="primary" htmlType="button" loading={submitting}>
                  确认并部署
                </Button>
              </Popconfirm>
            )}
          </div>
        </Form>
      </Drawer>
    </div>
  );
}
