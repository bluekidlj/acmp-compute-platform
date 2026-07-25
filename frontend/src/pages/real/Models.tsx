import { useEffect, useMemo, useState } from 'react';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
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
  id: 'DEEPSEEK' | 'QWEN' | 'GLM' | 'MINIMAX_M';
  name: string;
  publisher: string;
  mark: string;
  summary: string;
  capabilities: string[];
  theme: string;
}

const MODEL_FAMILY_OPTIONS = [
  { value: 'DEEPSEEK', label: 'DeepSeek 系列' },
  { value: 'QWEN', label: '阿里巴巴通义千问系列' },
  { value: 'GLM', label: '智谱 GLM 系列' },
  { value: 'MINIMAX_M', label: 'MiniMax M 系列' },
];
const MODEL_FAMILY_LABELS = Object.fromEntries(MODEL_FAMILY_OPTIONS.map(function toEntry(item) {
  return [item.value, item.label];
}));

const CATALOG_MODELS: CatalogModel[] = [
  {
    id: 'DEEPSEEK',
    name: 'DeepSeek 系列',
    publisher: 'DeepSeek',
    mark: 'DS',
    summary: '覆盖通用对话、代码和深度推理模型，登记时填写内网实际保存的具体模型版本。',
    capabilities: ['深度推理', '数学', '代码'],
    theme: 'blue',
  },
  {
    id: 'QWEN',
    name: '阿里巴巴通义千问系列',
    publisher: 'Alibaba Cloud',
    mark: 'QW',
    summary: '覆盖不同参数规模的通用、代码和多模态模型，适合中文企业应用与工具调用。',
    capabilities: ['中文增强', '工具调用', '多模态'],
    theme: 'orange',
  },
  {
    id: 'GLM',
    name: '智谱 GLM 系列',
    publisher: 'Zhipu AI',
    mark: 'GLM',
    summary: '面向中文对话、知识问答和智能体应用，登记具体 GLM 权重版本供内网部署。',
    capabilities: ['中文对话', '知识问答', '智能体'],
    theme: 'indigo',
  },
  {
    id: 'MINIMAX_M',
    name: 'MiniMax M 系列',
    publisher: 'MiniMax',
    mark: 'M',
    summary: '面向长上下文和复杂任务处理，登记内网具备权重文件的 MiniMax M 具体版本。',
    capabilities: ['长上下文', '复杂任务', '通用对话'],
    theme: 'red',
  },
];

export default function ModelsPage() {
  const [items, setItems] = useState<Model[]>([]);
  const [activeTab, setActiveTab] = useState('catalog');
  const [keyword, setKeyword] = useState('');
  const [open, setOpen] = useState(false);
  const [editingModel, setEditingModel] = useState<Model | null>(null);
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
        model.summary,
        ...model.capabilities,
      ].join(' ').toLowerCase();

      return searchableText.includes(normalizedKeyword);
    });
  }, [keyword]);

  function openBlankForm() {
    setEditingModel(null);
    form.resetFields();
    form.setFieldsValue({
      modelSource: 'with_weights',
      storageBackend: 'nfs',
      storagePath: '/data/acmp/models/',
    });
    setOpen(true);
  }

  function registerCatalogModel(model: CatalogModel) {
    setEditingModel(null);
    form.resetFields();
    form.setFieldsValue({
      modelFamily: model.id,
      modelSource: 'with_weights',
      storageBackend: 'nfs',
      storagePath: '/data/acmp/models/',
    });
    setOpen(true);
  }

  function editModel(model: Model) {
    setEditingModel(model);
    form.resetFields();
    form.setFieldsValue(model);
    setOpen(true);
  }

  async function submit(values: Partial<Model>) {
    try {
      if (editingModel) {
        await api.updateModel(editingModel.id, values);
        message.success('模型信息已更新');
      } else {
        await api.createModel(values);
        message.success('模型已登记');
      }
      setOpen(false);
      setEditingModel(null);
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
          <span>{filteredCatalog.length} 个模型系列</span>
        </div>

        {filteredCatalog.length === 0 ? (
          <div className="surface model-empty">
            <Empty description="没有匹配的模型" />
          </div>
        ) : (
          <div className="model-card-grid">
            {filteredCatalog.map(function renderModel(model) {
              return (
                <article className="model-card" key={model.id}>
                  <div className={`model-card-visual ${model.theme}`}>
                    <div className="model-logo">
                      <strong>{model.mark}</strong>
                    </div>
                    <span>模型系列</span>
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
                      type="primary"
                      icon={<PlusOutlined />}
                      onClick={function handleRegister() {
                        registerCatalogModel(model);
                      }}
                    >
                      登记该系列模型
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
                <div>
                  <Tag color="green">已登记</Tag>
                  <Tag>{model.modelFamily ? MODEL_FAMILY_LABELS[model.modelFamily] : '未归属系列'}</Tag>
                </div>
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

              <div style={{ display: 'flex', gap: 8 }}>
                <Button
                  icon={<EditOutlined />}
                  onClick={function handleEdit() {
                    editModel(model);
                  }}
                >
                  修改
                </Button>
                <Popconfirm
                  title="确认删除模型记录？"
                  onConfirm={function confirmDelete() {
                    remove(model.id);
                  }}
                >
                  <Button danger icon={<DeleteOutlined />}>删除记录</Button>
                </Popconfirm>
              </div>
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
            label: '模型系列',
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
        title={editingModel ? '修改已登记模型' : '登记模型'}
        open={open}
        width={540}
        onClose={function closeDrawer() {
          setOpen(false);
          setEditingModel(null);
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
            name="modelFamily"
            label="所属模型系列"
            rules={[{ required: true, message: '请选择所属模型系列' }]}
          >
            <Select options={MODEL_FAMILY_OPTIONS} placeholder="选择模型系列" />
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
            label="GPU 主机模型绝对目录"
            extra="示例：/data/acmp/models/Qwen2.5-3B-Instruct；目录中应直接包含 config.json 和模型权重文件。"
            rules={[
              { required: true, message: '请输入 GPU 主机模型绝对目录' },
              { pattern: /^\//, message: '请输入以 / 开头的 Linux 绝对路径' },
            ]}
          >
            <Input className="mono" placeholder="/data/acmp/models/Qwen2.5-3B-Instruct" />
          </Form.Item>

          <Form.Item name="fileSizeMb" label="文件大小 MiB">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>

          <Button type="primary" htmlType="submit" block>
            {editingModel ? '保存修改' : '保存模型'}
          </Button>
        </Form>
      </Drawer>
    </div>
  );
}
