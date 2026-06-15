import React, { useEffect, useState, useCallback } from 'react';
import {
  Table, Button, Modal, Form, Input, Select, InputNumber, Space, Tag, Typography,
  message, Card, Popconfirm, Empty,
} from 'antd';
import { PlusOutlined, ReloadOutlined, RocketOutlined, DeleteOutlined } from '@ant-design/icons';
import { modelDeploymentApi } from '../api/modelDeployments';
import { workspaceApi } from '../api/workspaces';
import { modelApi } from '../api/models';
import type { ModelDeployment, ModelDeploymentRequest, Workspace, Model } from '../types';

const { Title, Text } = Typography;

const GPU_TYPES = [
  { label: 'NVIDIA A100 80GB (1/4)', value: 'nvidia-a100-80g-1/4' },
  { label: 'NVIDIA A100 40GB (1/2)', value: 'nvidia-a100-40g-1/2' },
  { label: 'NVIDIA RTX 4090 24GB (1/4)', value: 'nvidia-rtx4090-24g-1/4' },
  { label: 'NVIDIA RTX 4090 24GB (1/2)', value: 'nvidia-rtx4090-24g-1/2' },
  { label: 'Hygon DCU 32GB (1/4)', value: 'hygon-dcu-32g-1/4' },
  { label: 'Hygon DCU 32GB (1/2)', value: 'hygon-dcu-32g-1/2' },
  { label: 'Huawei Ascend 910B (1/4)', value: 'huawei-ascend-910b-1/4' },
];

const DeploymentsPage: React.FC = () => {
  const [deployments, setDeployments] = useState<ModelDeployment[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [models, setModels] = useState<Model[]>([]);
  const [loading, setLoading] = useState(false);
  const [deployOpen, setDeployOpen] = useState(false);
  const [deployForm] = Form.useForm();
  const [deploying, setDeploying] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [wsRes, modelRes] = await Promise.all([workspaceApi.list(), modelApi.list()]);
      setWorkspaces(wsRes.data);
      setModels(modelRes.data);

      // collect all deployments across all workspaces
      const allDeploys: ModelDeployment[] = [];
      for (const ws of wsRes.data) {
        try {
          const depRes = await modelDeploymentApi.list(ws.id);
          allDeploys.push(...depRes.data);
        } catch { /* skip */ }
      }
      setDeployments(allDeploys);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleDeploy = async () => {
    const values = await deployForm.validateFields();
    setDeploying(true);
    try {
      const req: ModelDeploymentRequest = { ...values };
      const ws = workspaces.find((w) => w.id === values.workspaceId);
      if (!ws) throw new Error('工作空间不存在');
      await modelDeploymentApi.deploy(ws.resourcePoolId, values.workspaceId, req);
      message.success('推理服务部署成功');
      setDeployOpen(false);
      deployForm.resetFields();
      load();
    } catch { /* handled */ }
    finally { setDeploying(false); }
  };

  const handleDelete = async (workspaceId: string, id: string) => {
    await modelDeploymentApi.delete(workspaceId, id);
    message.success('部署已删除');
    load();
  };

  const statusMap: Record<string, { color: string; text: string }> = {
    running: { color: 'green', text: '运行中' },
    pending: { color: 'orange', text: '等待中' },
    failed: { color: 'red', text: '失败' },
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name', width: 140 },
    { title: '工作空间', dataIndex: 'workspaceId', key: 'workspace', width: 120,
      render: (id: string) => workspaces.find((w) => w.id === id)?.name || id },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => {
        const s = statusMap[v] || { color: 'default', text: v };
        return <Tag color={s.color}>{s.text}</Tag>;
      },
    },
    { title: 'GPU/副本', dataIndex: 'gpuPerReplica', key: 'gpu', width: 80,
      render: (v: number, r: ModelDeployment) => `${v} × ${r.replicas}` },
    { title: '镜像', dataIndex: 'vllmImage', ellipsis: true,
      render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    { title: '模型', dataIndex: 'modelName', ellipsis: true },
    {
      title: '服务地址', dataIndex: 'serviceUrl', ellipsis: true, width: 260,
      render: (v: string) => v ? <Text code className="mono" copyable style={{ fontSize: 11 }}>{v}</Text> : '-',
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v: string) => new Date(v).toLocaleString(),
    },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: unknown, record: ModelDeployment) => (
        <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.workspaceId, record.id)}>
          <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>推理服务部署</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<RocketOutlined />} onClick={() => setDeployOpen(true)}>
            部署推理服务
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={deployments}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 10 }}
      />

      <Modal
        title="部署推理服务"
        open={deployOpen}
        onOk={handleDeploy}
        onCancel={() => { setDeployOpen(false); deployForm.resetFields(); }}
        confirmLoading={deploying}
        okText="部署"
        width={640}
      >
        <Form form={deployForm} layout="vertical">
          <Form.Item name="name" label="部署名称" rules={[{ required: true }]}>
            <Input placeholder="qwen3-svc" />
          </Form.Item>
          <Form.Item name="workspaceId" label="所属工作空间" rules={[{ required: true }]}>
            <Select placeholder="选择工作空间">
              {workspaces.map((w) => (
                <Select.Option key={w.id} value={w.id}>{w.name}</Select.Option>
              ))}
            </Select>
          </Form.Item>

          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="gpuType" label="GPU 类型" rules={[{ required: true }]}>
              <Select placeholder="选择 GPU 类型" style={{ width: 220 }}>
                {GPU_TYPES.map((t) => (
                  <Select.Option key={t.value} value={t.value}>{t.label}</Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item name="gpuCount" label="GPU 数量" rules={[{ required: true }]} initialValue={1}>
              <InputNumber min={1} max={8} style={{ width: 80 }} />
            </Form.Item>
          </Space>

          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="cpuCores" label="CPU 核数" rules={[{ required: true }]} initialValue={4}>
              <InputNumber min={1} max={64} style={{ width: 80 }} />
            </Form.Item>
            <Form.Item name="memoryGib" label="内存 (GiB)" rules={[{ required: true }]} initialValue={16}>
              <InputNumber min={1} max={512} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="replicas" label="副本数" rules={[{ required: true }]} initialValue={1}>
              <InputNumber min={1} max={10} style={{ width: 80 }} />
            </Form.Item>
          </Space>

          <Form.Item name="image" label="vLLM 镜像" rules={[{ required: true }]} initialValue="vllm/vllm-openai:latest">
            <Input placeholder="vllm/vllm-openai:latest" />
          </Form.Item>

          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="modelSource" label="模型来源" rules={[{ required: true }]} initialValue="with_weights">
              <Select style={{ width: 140 }}>
                <Select.Option value="with_weights">带权重</Select.Option>
                <Select.Option value="without_weights">不带权重</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="modelId" label="从模型广场选择">
              <Select
                placeholder="选择模型（可选）"
                style={{ width: 220 }}
                allowClear
                onChange={(val) => {
                  if (val) {
                    const m = models.find((m) => m.id === val);
                    if (m) {
                      deployForm.setFieldsValue({
                        modelName: m.displayName || m.name,
                        modelIdOrPath: m.storagePath,
                      });
                    }
                  }
                }}
              >
                {models.map((m) => (
                  <Select.Option key={m.id} value={m.id}>
                    {m.displayName || m.name} ({m.name})
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
          </Space>

          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="modelName" label="模型名称">
              <Input placeholder="Qwen3-7B-Instruct" style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="modelIdOrPath" label="模型路径">
              <Input placeholder="/models/qwen3" style={{ width: 220 }} />
            </Form.Item>
          </Space>

          <Form.Item name="command" label="启动命令（可选）">
            <Input placeholder="python -m vllm.entrypoints.openai.api_server" />
          </Form.Item>

          <Form.Item name="args" label="启动参数（可选）">
            <Input placeholder="--model /models --host 0.0.0.0 --port 8000" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DeploymentsPage;