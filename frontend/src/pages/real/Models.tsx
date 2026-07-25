import { useEffect, useMemo, useState } from 'react';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Popconfirm,
  Select,
  Tabs,
  Tag,
} from 'antd';
import { api } from '../../api/real';
import type { Model } from '../../types';

interface CatalogModel {
  id: string;
  name: string;
  publisher: string;
  parameterSize: string;
  summary: string;
  capabilities: string[];
  logo: string;
  theme: string;
}

const CATALOG_MODELS: CatalogModel[] = [
  {
    id: 'Qwen3-8B',
    name: 'Qwen3 8B',
    publisher: 'Alibaba Cloud',
    parameterSize: '8B',
    summary: '兼顾推理与通用对话的开源模型，适合中文问答、工具调用和企业知识助手。',
    capabilities: ['中文增强', '推理', '工具调用'],
    logo: '/model-logos/qwen.svg',
    theme: 'orange',
  },
  {
    id: 'DeepSeek-R1-Distill-Qwen-7B',
    name: 'DeepSeek R1 Distill',
    publisher: 'DeepSeek',
    parameterSize: '7B',
    summary: '面向复杂推理场景的蒸馏模型，在较小算力规格上提供数学与逻辑推理能力。',
    capabilities: ['深度推理', '数学', '代码'],
    logo: '/model-logos/deepseek.svg',
    theme: 'blue',
  },
  {
    id: 'Meta-Llama-3.1-8B-Instruct',
    name: 'Llama 3.1 Instruct',
    publisher: 'Meta',
    parameterSize: '8B',
    summary: '成熟稳定的通用指令模型，生态完整，适合作为英文对话和应用开发基座。',
    capabilities: ['通用对话', '多语言', '生态丰富'],
    logo: '/model-logos/meta.svg',
    theme: 'indigo',
  },
  {
    id: 'Mistral-Small-3.2-24B-Instruct',
    name: 'Mistral Small 3.2',
    publisher: 'Mistral AI',
    parameterSize: '24B',
    summary: '高效的开放权重模型，支持长上下文、函数调用以及文档理解类任务。',
    capabilities: ['长上下文', '函数调用', '文档理解'],
    logo: '/model-logos/mistral.svg',
    theme: 'red',
  },
  {
    id: 'google-gemma-3-12b-it',
    name: 'Gemma 3',
    publisher: 'Google',
    parameterSize: '12B',
    summary: '面向本地部署的轻量多模态模型，适合文本与视觉内容理解场景。',
    capabilities: ['多模态', '多语言', '轻量部署'],
    logo: '/model-logos/google.svg',
    theme: 'cyan',
  },
];

export default function ModelsPage() {
  const [items, setItems] = useState<Model[]>([]);
  const [activeTab, setActiveTab] = useState('catalog');
  const [keyword, setKeyword] = useState('');
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  function load() {
    api.models()
      .then(setItems)
      .catch(function handleFailure(exception) {
        message.error(exception.message);
      });
  }

  useEffect(load, []);

  const filteredCatalog = useMemo(function filterCatalog() {
    const normalizedKeyword = keyword.trim().toLowerCase();

    if (!normalizedKeyword) {
      return CATALOG_MODELS;
    }

    return CATALOG_MODELS.filter(function matches(model) {
      const searchableText = [
        model.name,
        model.publisher,
        model.parameterSize,
        model.summary,
        ...model.capabilities,
      ].join(' ').toLowerCase();

      return searchableText.includes(normalizedKeyword);
    });
  }, [keyword]);

  function openBlankForm() {
    form.resetFields();
    form.setFieldsValue({
      modelSource: 'with_weights',
      storageBackend: 'nfs',
      storagePath: '/models',
    });
    setOpen(true);
  }

  function registerCatalogModel(model: CatalogModel) {
    form.setFieldsValue({
      name: model.id,
      displayName: model.name,
      modelSource: 'with_weights',
      storageBackend: 'nfs',
      storagePath: `/models/${model.id}`,
      description: model.summary,
    });
    setOpen(true);
  }

  function isRegistered(model: CatalogModel) {
    return items.some(function matches(item) {
      return item.name === model.id;
    });
  }

  async function submit(values: Partial<Model>) {
    try {
      await api.createModel(values);
      message.success('模型已登记');
      setOpen(false);
      form.resetFields();
      load();
      setActiveTab('registered');
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '保存失败');
    }
  }

  async function remove(id: string) {
    try {
      await api.deleteModel(id);
      message.success('模型已删除');
      load();
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败');
    }
  }

  function renderCatalog() {
    return (
      <>
        <div className="model-toolbar">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索模型、厂商或能力"
            value={keyword}
            onChange={function handleSearch(event) {
              setKeyword(event.target.value);
            }}
          />
          <span>精选 {filteredCatalog.length} 个开放模型</span>
        </div>

        {filteredCatalog.length === 0 ? (
          <div className="surface model-empty">
            <Empty description="没有匹配的模型" />
          </div>
        ) : (
          <div className="model-card-grid">
            {filteredCatalog.map(function renderModel(model) {
              const registered = isRegistered(model);

              return (
                <article className="model-card" key={model.id}>
                  <div className={`model-card-visual ${model.theme}`}>
                    <div className="model-logo">
                      <img src={model.logo} alt={`${model.publisher} 标识`} />
                    </div>
                    <span>{model.parameterSize}</span>
                  </div>

                  <div className="model-card-body">
                    <div className="model-publisher">{model.publisher}</div>
                    <h3>{model.name}</h3>
                    <p>{model.summary}</p>

                    <div className="model-capabilities">
                      {model.capabilities.map(function renderCapability(capability) {
                        return <Tag key={capability}>{capability}</Tag>;
                      })}
                    </div>

                    <Button
                      type={registered ? 'default' : 'primary'}
                      icon={registered ? <CheckCircleOutlined /> : <PlusOutlined />}
                      disabled={registered}
                      onClick={function handleRegister() {
                        registerCatalogModel(model);
                      }}
                    >
                      {registered ? '已登记' : '登记到平台'}
                    </Button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </>
    );
  }

  function renderRegisteredModels() {
    if (items.length === 0) {
      return (
        <div className="surface model-empty">
          <Empty description="尚未登记模型">
            <Button type="primary" onClick={openBlankForm}>登记模型</Button>
          </Empty>
        </div>
      );
    }

    return (
      <div className="registered-model-grid">
        {items.map(function renderModel(model) {
          return (
            <article className="registered-model-card" key={model.id}>
              <div className="registered-model-heading">
                <div>
                  <h3>{model.displayName || model.name}</h3>
                  <code>{model.name}</code>
                </div>
                <Tag color="green">已登记</Tag>
              </div>

              <p>{model.description || '暂无模型描述'}</p>

              <dl>
                <div>
                  <dt>存储</dt>
                  <dd>{model.storageBackend || '-'}</dd>
                </div>
                <div>
                  <dt>大小</dt>
                  <dd>{model.fileSizeMb ? `${(model.fileSizeMb / 1024).toFixed(2)} GiB` : '-'}</dd>
                </div>
              </dl>

              <div className="registered-model-path">
                <span>模型路径</span>
                <code>{model.storagePath || '-'}</code>
              </div>

              <Popconfirm
                title="确认删除模型记录？"
                onConfirm={function confirmDelete() {
                  remove(model.id);
                }}
              >
                <Button danger icon={<DeleteOutlined />}>删除记录</Button>
              </Popconfirm>
            </article>
          );
        })}
      </div>
    );
  }

  return (
    <div>
      <div className="page-heading model-page-heading">
        <div>
          <h1>模型广场</h1>
          <p>发现适合内网部署的开放模型，并登记已有的模型权重与存储位置</p>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openBlankForm}>
          登记模型
        </Button>
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'catalog',
            label: '精选模型',
            children: renderCatalog(),
          },
          {
            key: 'registered',
            label: `已登记模型 (${items.length})`,
            children: renderRegisteredModels(),
          },
        ]}
      />

      <Drawer
        title="登记模型"
        open={open}
        width={540}
        onClose={function closeDrawer() {
          setOpen(false);
        }}
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item
            name="name"
            label="模型唯一名称"
            rules={[{ required: true, message: '请输入模型唯一名称' }]}
          >
            <Input />
          </Form.Item>

          <Form.Item name="displayName" label="展示名称">
            <Input />
          </Form.Item>

          <Form.Item
            name="modelSource"
            label="模型来源"
            rules={[{ required: true, message: '请选择模型来源' }]}
          >
            <Select
              options={[
                { value: 'with_weights', label: '内网已有权重' },
                { value: 'without_weights', label: '仅模型标识' },
              ]}
            />
          </Form.Item>

          <Form.Item
            name="storageBackend"
            label="存储后端"
            rules={[{ required: true, message: '请选择存储后端' }]}
          >
            <Select options={[{ value: 'nfs', label: 'NFS / 本地挂载' }]} />
          </Form.Item>

          <Form.Item
            name="storagePath"
            label="模型存储路径"
            rules={[{ required: true, message: '请输入模型存储路径' }]}
          >
            <Input className="mono" />
          </Form.Item>

          <Form.Item name="fileSizeMb" label="文件大小 MiB">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>

          <Button type="primary" htmlType="submit" block>
            保存模型
          </Button>
        </Form>
      </Drawer>
    </div>
  );
}
