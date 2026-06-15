import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Tabs, Card, Descriptions, Tag, Typography, Spin, Table, Space, Button, Empty,
  Modal, Form, Input, InputNumber, Select, message, Popconfirm,
} from 'antd';
import {
  ArrowLeftOutlined, UserAddOutlined, ReloadOutlined, RocketOutlined,
  ThunderboltOutlined, KeyOutlined, DeleteOutlined,
} from '@ant-design/icons';
import { workspaceApi } from '../api/workspaces';
import { modelDeploymentApi } from '../api/modelDeployments';
import { trainingJobApi } from '../api/trainingJobs';
import { specApi } from '../api/specs';
import type {
  Workspace, WorkspaceUpdateRequest, WorkspaceSpecQuota,
  ModelDeployment, ModelDeploymentRequest, ComputeSpec,
} from '../types';
import { useAuth } from '../contexts/AuthContext';

const { Title, Text } = Typography;

const WorkspaceDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { isAdmin } = useAuth();

  // workspace state
  const [ws, setWs] = useState<Workspace | null>(null);
  const [loading, setLoading] = useState(true);

  // members
  const [members, setMembers] = useState<string[]>([]);
  const [addMemberOpen, setAddMemberOpen] = useState(false);
  const [memberForm] = Form.useForm();

  // deployments
  const [deployments, setDeployments] = useState<ModelDeployment[]>([]);
  const [deployOpen, setDeployOpen] = useState(false);
  const [deployForm] = Form.useForm();
  const [deploying, setDeploying] = useState(false);

  // training
  const [trainingOpen, setTrainingOpen] = useState(false);
  const [trainingForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  // credential
  const [credOpen, setCredOpen] = useState(false);
  const [credForm] = Form.useForm();
  const [credResult, setCredResult] = useState<string | null>(null);

  // edit
  const [editOpen, setEditOpen] = useState(false);
  const [editForm] = Form.useForm();

  // specs for deploy/training form
  const [wsSpecs, setWsSpecs] = useState<ComputeSpec[]>([]);

  const loadAll = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [wsRes, memberRes, depRes] = await Promise.all([
        workspaceApi.get(id),
        workspaceApi.members(id),
        modelDeploymentApi.list(id),
      ]);
      setWs(wsRes.data);
      setMembers(memberRes.data);
      setDeployments(depRes.data);

      // load spec details for the workspace's specs
      const specNames = wsRes.data.specQuotas?.map((q: WorkspaceSpecQuota) => q.specName) || [];
      if (specNames.length > 0) {
        const allSpecs = await specApi.list();
        setWsSpecs(allSpecs.data.filter((s) => specNames.includes(s.name)));
      }
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, [id]);

  useEffect(() => { loadAll(); }, [loadAll]);

  // ─── Members ───
  const handleAddMember = async () => {
    const { userId } = await memberForm.validateFields();
    await workspaceApi.addMember(id!, { userId });
    message.success('成员已添加');
    setAddMemberOpen(false);
    memberForm.resetFields();
    const res = await workspaceApi.members(id!);
    setMembers(res.data);
  };

  const handleRemoveMember = async (userId: string) => {
    await workspaceApi.removeMember(id!, userId);
    message.success('成员已移除');
    setMembers(members.filter((m) => m !== userId));
  };

  // ─── Deploy ───
  const handleDeploy = async () => {
    const values = await deployForm.validateFields();
    setDeploying(true);
    try {
      const req: ModelDeploymentRequest = { ...values };
      await modelDeploymentApi.deploy(ws!.resourcePoolId, id!, req);
      message.success('推理服务部署成功');
      setDeployOpen(false);
      deployForm.resetFields();
      const depRes = await modelDeploymentApi.list(id!);
      setDeployments(depRes.data);
    } finally {
      setDeploying(false);
    }
  };

  const handleDeleteDeployment = async (depId: string) => {
    await modelDeploymentApi.delete(id!, depId);
    message.success('部署已删除，配额已归还');
    const [depRes] = await Promise.all([
      modelDeploymentApi.list(id!),
    ]);
    setDeployments(depRes.data);
    // reload workspace to see updated quotas
    const wsRes = await workspaceApi.get(id!);
    setWs(wsRes.data);
  };

  // ─── Training ───
  const handleSubmitTraining = async () => {
    const values = await trainingForm.validateFields();
    setSubmitting(true);
    try {
      await trainingJobApi.submit(id!, {
        ...values,
        command: values.command ? values.command.split('\n').filter(Boolean) : undefined,
      });
      message.success('训练任务已提交');
      setTrainingOpen(false);
      trainingForm.resetFields();
    } finally {
      setSubmitting(false);
    }
  };

  // ─── Credential ───
  const handleIssueCred = async () => {
    const values = await credForm.validateFields();
    const res = await workspaceApi.issueCredential(id!, values);
    setCredResult(res.data.kubeconfig || JSON.stringify(res.data, null, 2));
    message.success('凭证已签发');
  };

  // ─── Edit workspace ───
  const handleEdit = async () => {
    const values = await editForm.validateFields();
    await workspaceApi.update(id!, values as WorkspaceUpdateRequest);
    message.success('已更新');
    setEditOpen(false);
    const wsRes = await workspaceApi.get(id!);
    setWs(wsRes.data);
  };

  const handleDeleteWs = async () => {
    await workspaceApi.delete(id!);
    message.success('工作空间已删除');
    navigate('/workspaces', { replace: true });
  };

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 120 }} />;
  if (!ws) return <Empty description="工作空间不存在" />;

  const statusTag = ws.status === 'active' ? <Tag color="green">活跃</Tag> : <Tag color="red">停用</Tag>;

  // ── Tab: Overview ──
  const overviewTab = (
    <div>
      <Card
        title={<Title level={5} style={{ margin: 0 }}>基本信息</Title>}
        extra={
          <Space>
            {isAdmin && <Button size="small" onClick={() => { editForm.setFieldsValue(ws); setEditOpen(true); }}>编辑</Button>}
            {isAdmin && (
              <Popconfirm title="确定删除？这将级联删除 K8s Namespace 内所有资源" onConfirm={handleDeleteWs}>
                <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            )}
          </Space>
        }
        style={{ borderRadius: 10, marginBottom: 16 }}
      >
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label="ID"><Text code>{ws.id}</Text></Descriptions.Item>
          <Descriptions.Item label="状态">{statusTag}</Descriptions.Item>
          <Descriptions.Item label="所属资源池">{ws.resourcePoolName}</Descriptions.Item>
          <Descriptions.Item label="资源池 ID"><Text code>{ws.resourcePoolId}</Text></Descriptions.Item>
          <Descriptions.Item label="K8s Namespace"><Text code className="mono">{ws.namespace}</Text></Descriptions.Item>
          <Descriptions.Item label="Volcano Queue"><Text code className="mono">{ws.volcanoQueueName}</Text></Descriptions.Item>
          <Descriptions.Item label="主集群 ID"><Text code>{ws.primaryClusterId}</Text></Descriptions.Item>
          <Descriptions.Item label="最大 Pod 数">{ws.maxPods}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>{ws.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="创建者">{ws.createdBy}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{ws.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="规格配额" style={{ borderRadius: 10 }}>
        <Table
          dataSource={ws.specQuotas}
          rowKey="specId"
          pagination={false}
          size="small"
          columns={[
            { title: '规格', dataIndex: 'specName', render: (v: string) => <Text code>{v}</Text> },
            { title: '最大配额', dataIndex: 'maxQuota' },
            { title: '已使用', dataIndex: 'usedQuota' },
            {
              title: '可用', dataIndex: 'availableQuota',
              render: (v: number, r: WorkspaceSpecQuota) => (
                <Tag color={v > 0 ? 'green' : 'red'}>{v} / {r.maxNodes}</Tag>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );

  // ── Tab: Members ──
  const membersTab = (
    <Card
      title="工作空间成员"
      extra={
        isAdmin && (
          <Button type="primary" size="small" icon={<UserAddOutlined />} onClick={() => setAddMemberOpen(true)}>
            添加成员
          </Button>
        )
      }
      style={{ borderRadius: 10 }}
    >
      {members.length === 0 ? (
        <Empty description="暂无成员" />
      ) : (
        <Table
          dataSource={members.map((m) => ({ userId: m }))}
          rowKey="userId"
          pagination={false}
          size="small"
          columns={[
            { title: '用户 ID', dataIndex: 'userId', render: (v: string) => <Text code>{v}</Text> },
            {
              title: '操作', key: 'actions', width: 80,
              render: (_: unknown, record: { userId: string }) =>
                isAdmin && (
                  <Popconfirm title="确定移除该成员？" onConfirm={() => handleRemoveMember(record.userId)}>
                    <Button size="small" danger>移除</Button>
                  </Popconfirm>
                ),
            },
          ]}
        />
      )}

      <Modal
        title="添加成员"
        open={addMemberOpen}
        onOk={handleAddMember}
        onCancel={() => { setAddMemberOpen(false); memberForm.resetFields(); }}
      >
        <Form form={memberForm} layout="vertical">
          <Form.Item name="userId" label="用户 ID" rules={[{ required: true }]}>
            <Input placeholder="输入用户 UUID" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );

  // ── Tab: Deployments ──
  const deploymentsTab = (
    <Card
      title="推理服务部署"
      extra={
        <Space>
          <Button size="small" icon={<ReloadOutlined />} onClick={loadAll}>刷新</Button>
          <Button type="primary" size="small" icon={<RocketOutlined />} onClick={() => setDeployOpen(true)}>
            部署推理服务
          </Button>
        </Space>
      }
      style={{ borderRadius: 10 }}
    >
      <Table
        dataSource={deployments}
        rowKey="id"
        pagination={false}
        size="small"
        columns={[
          { title: '名称', dataIndex: 'name', width: 140 },
          { title: '模型', dataIndex: 'modelName', ellipsis: true },
          {
            title: '状态', dataIndex: 'status', width: 90,
            render: (v: string) => {
              const m: Record<string, { color: string; text: string }> = {
                running: { color: 'green', text: '运行中' },
                pending: { color: 'orange', text: '等待中' },
                failed: { color: 'red', text: '失败' },
              };
              const s = m[v] || { color: 'default', text: v };
              return <Tag color={s.color}>{s.text}</Tag>;
            },
          },
          { title: 'GPU', dataIndex: 'gpuPerReplica', width: 60 },
          { title: '副本', dataIndex: 'replicas', width: 60 },
          {
            title: '就绪', dataIndex: 'readyReplicas', width: 60,
            render: (v: number | undefined) => v ?? '-',
          },
          { title: '服务地址', dataIndex: 'serviceUrl', ellipsis: true, width: 280,
            render: (v: string) => v ? <Text code className="mono" copyable>{v}</Text> : '-',
          },
          {
            title: '操作', key: 'actions', width: 80,
            render: (_: unknown, record: ModelDeployment) => (
              <Popconfirm title="确定删除？配额将归还" onConfirm={() => handleDeleteDeployment(record.id)}>
                <Button size="small" danger>删除</Button>
              </Popconfirm>
            ),
          },
        ]}
      />

      {/* Deploy Modal */}
      <Modal
        title="部署 vLLM 推理服务"
        open={deployOpen}
        onOk={handleDeploy}
        onCancel={() => { setDeployOpen(false); deployForm.resetFields(); }}
        confirmLoading={deploying}
        okText="部署"
        width={600}
      >
        <Form form={deployForm} layout="vertical">
          <Form.Item name="name" label="部署名称" rules={[{ required: true }]}>
            <Input placeholder="qwen3-svc" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="gpuType" label="GPU 类型" rules={[{ required: true }]}>
              <Select placeholder="选择 GPU 类型" style={{ width: 200 }}>
                <Select.Option value="nvidia-a100-80g-1/4">NVIDIA A100 80GB (1/4)</Select.Option>
                <Select.Option value="nvidia-rtx4090-24g-1/4">NVIDIA RTX 4090 24GB (1/4)</Select.Option>
                <Select.Option value="hygon-dcu-32g-1/4">Hygon DCU 32GB (1/4)</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="gpuCount" label="GPU 数量" initialValue={1}>
              <InputNumber min={1} max={8} style={{ width: 80 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="cpuCores" label="CPU 核数" initialValue={4}>
              <InputNumber min={1} max={64} style={{ width: 80 }} />
            </Form.Item>
            <Form.Item name="memoryGib" label="内存 (GiB)" initialValue={16}>
              <InputNumber min={1} max={512} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="replicas" label="副本数" initialValue={1}>
              <InputNumber min={1} max={10} style={{ width: 80 }} />
            </Form.Item>
          </Space>
          <Form.Item name="image" label="vLLM 镜像" initialValue="vllm/vllm-openai:latest">
            <Input placeholder="vllm/vllm-openai:latest" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="modelSource" label="模型来源" initialValue="with_weights">
              <Select style={{ width: 140 }}>
                <Select.Option value="with_weights">带权重</Select.Option>
                <Select.Option value="without_weights">不带权重</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="modelName" label="模型名称">
              <Input placeholder="Qwen3-7B-Instruct" style={{ width: 180 }} />
            </Form.Item>
          </Space>
          <Form.Item name="modelIdOrPath" label="模型路径" initialValue="/models">
            <Input placeholder="/models/qwen3" />
          </Form.Item>
          <Form.Item name="command" label="启动命令（可选）">
            <Input placeholder="python -m vllm.entrypoints.openai.api_server" />
          </Form.Item>
          <Form.Item name="args" label="启动参数（可选）">
            <Input placeholder="--model /models --host 0.0.0.0 --port 8000" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );

  // ── Tab: Training ──
  const trainingTab = (
    <Card
      title="训练任务"
      extra={
        <Button type="primary" size="small" icon={<ThunderboltOutlined />} onClick={() => setTrainingOpen(true)}>
          提交训练任务
        </Button>
      }
      style={{ borderRadius: 10 }}
    >
      <Empty description="暂无训练任务记录" />

      <Modal
        title="提交 VolcanoJob 训练任务"
        open={trainingOpen}
        onOk={handleSubmitTraining}
        onCancel={() => { setTrainingOpen(false); trainingForm.resetFields(); }}
        confirmLoading={submitting}
        okText="提交"
        width={560}
      >
        <Form form={trainingForm} layout="vertical">
          <Form.Item name="jobName" label="任务名称" rules={[{ required: true }]}>
            <Input placeholder="qwen3-finetune-01" />
          </Form.Item>
          <Form.Item name="image" label="训练镜像" rules={[{ required: true }]}>
            <Input placeholder="registry.local/training:torch-2.1" />
          </Form.Item>
          <Form.Item name="specName" label="算力规格" rules={[{ required: true }]}>
            <Select placeholder="选择规格">
              {wsSpecs.map((s) => (
                <Select.Option key={s.id} value={s.name}>{s.displayName}</Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="replicas" label="副本数" rules={[{ required: true }]} initialValue={1}>
            <InputNumber min={1} max={10} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="command" label="命令（每行一个）">
            <Input.TextArea rows={3} placeholder="python&#10;train.py&#10;--epochs 3" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );

  // ── Tab: Credential (admin only) ──
  const credentialTab = isAdmin ? (
    <Card
      title="凭证签发"
      extra={
        <Button type="primary" size="small" icon={<KeyOutlined />} onClick={() => setCredOpen(true)}>
          签发 kubeconfig
        </Button>
      }
      style={{ borderRadius: 10 }}
    >
      {credResult ? (
        <div>
          <Text strong>签发结果：</Text>
          <pre style={{ maxHeight: 400, overflow: 'auto', background: '#f5f5f5', padding: 12, borderRadius: 6, fontSize: 12 }}>
            {credResult}
          </pre>
        </div>
      ) : (
        <Empty description="尚未签发凭证" />
      )}

      <Modal
        title="签发 kubeconfig"
        open={credOpen}
        onOk={handleIssueCred}
        onCancel={() => { setCredOpen(false); credForm.resetFields(); }}
        okText="签发"
      >
        <Form form={credForm} layout="vertical">
          <Form.Item name="username" label="目标用户" rules={[{ required: true }]}>
            <Input placeholder="zhangsan" />
          </Form.Item>
          <Form.Item name="expireDays" label="有效期（天）" rules={[{ required: true }]} initialValue={30}>
            <InputNumber min={1} max={365} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  ) : null;

  // ── Edit Modal ──
  const editModal = (
    <Modal
      title="编辑工作空间"
      open={editOpen}
      onOk={handleEdit}
      onCancel={() => setEditOpen(false)}
      okText="保存"
    >
      <Form form={editForm} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item name="resourcePoolId" label="资源池 ID">
          <Input disabled />
        </Form.Item>
      </Form>
    </Modal>
  );

  const tabItems = [
    { key: 'overview', label: '概览', children: overviewTab },
    { key: 'members', label: `成员 (${members.length})`, children: membersTab },
    { key: 'deployments', label: `推理服务 (${deployments.length})`, children: deploymentsTab },
    { key: 'training', label: '训练任务', children: trainingTab },
  ];
  if (isAdmin) {
    tabItems.push({ key: 'credential', label: '凭证', children: credentialTab! });
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workspaces')}>返回列表</Button>
        <Title level={4} style={{ margin: 0 }}>{ws.name}</Title>
        {statusTag}
      </Space>

      <Tabs defaultActiveKey="overview" items={tabItems} />
      {editModal}
    </div>
  );
};

export default WorkspaceDetailPage;
