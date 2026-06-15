import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, Empty, Input, Spin, message,
} from 'antd';
import { MessageOutlined, ArrowLeftOutlined, SendOutlined, ClearOutlined } from '@ant-design/icons';
import { modelDeploymentApi } from '../api/modelDeployments';
import { workspaceApi } from '../api/workspaces';
import type { ModelDeployment, Workspace } from '../types';

const { Title, Text } = Typography;
const { TextArea } = Input;

interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

const InferenceChatPage: React.FC = () => {
  const [services, setServices] = useState<ModelDeployment[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedService, setSelectedService] = useState<ModelDeployment | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const loadServices = useCallback(async () => {
    setLoading(true);
    try {
      const wsRes = await workspaceApi.list();
      setWorkspaces(wsRes.data);

      const allDeploys: ModelDeployment[] = [];
      for (const ws of wsRes.data) {
        try {
          const depRes = await modelDeploymentApi.list(ws.id);
          allDeploys.push(...depRes.data.filter((d: ModelDeployment) => d.status === 'running'));
        } catch { /* skip */ }
      }
      setServices(allDeploys);
    } catch { /* handled */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { loadServices(); }, [loadServices]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSelectService = (service: ModelDeployment) => {
    setSelectedService(service);
    setMessages([]);
  };

  const handleBack = () => {
    setSelectedService(null);
    setMessages([]);
  };

  const handleSend = async () => {
    if (!inputValue.trim() || !selectedService || sending) return;
    if (!selectedService.serviceUrl) {
      message.error('服务地址不可用');
      return;
    }

    const userMessage: ChatMessage = { role: 'user', content: inputValue.trim() };
    setMessages((prev) => [...prev, userMessage]);
    setInputValue('');
    setSending(true);
    setStreaming(true);

    try {
      // Build the full URL - normalize the serviceUrl
      let baseUrl = selectedService.serviceUrl;
      if (baseUrl.startsWith('http://')) {
        baseUrl = baseUrl.substring(7);
      }
      // Remove cluster.local and path to get host:port
      const hostMatch = baseUrl.match(/^([^/]+)/);
      if (!hostMatch) throw new Error('无效的服务地址');
      const chatUrl = `http://${hostMatch[1]}/v1/chat/completions`;

      const response = await fetch(chatUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: selectedService.modelName || 'default',
          messages: [...messages, userMessage].map((m) => ({ role: m.role, content: m.content })),
          stream: false,
        }),
      });

      if (!response.ok) {
        const err = await response.text();
        throw new Error(`请求失败: ${response.status} ${err}`);
      }

      const data = await response.json();
      const assistantMessage: ChatMessage = {
        role: 'assistant',
        content: data.choices?.[0]?.message?.content || '（无响应）',
      };
      setMessages((prev) => [...prev, assistantMessage]);
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : String(err);
      message.error('发送失败: ' + errMsg);
      // remove the user message if request failed
      setMessages((prev) => prev.slice(0, -1));
    } finally {
      setSending(false);
      setStreaming(false);
    }
  };

  const handleClear = () => setMessages([]);

  const statusMap: Record<string, { color: string; text: string }> = {
    running: { color: 'green', text: '运行中' },
    pending: { color: 'orange', text: '等待中' },
    failed: { color: 'red', text: '失败' },
  };

  // Service list view
  const serviceListView = (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>推理对话</Title>
        <Button icon={<MessageOutlined />} onClick={loadServices}>刷新</Button>
      </div>

      {loading ? (
        <Spin size="large" style={{ display: 'block', marginTop: 80 }} />
      ) : services.length === 0 ? (
        <Empty description="暂无运行中的推理服务" style={{ marginTop: 80 }} />
      ) : (
        <Table
          dataSource={services}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          columns={[
            { title: '服务名称', dataIndex: 'name', width: 140 },
            {
              title: '状态', dataIndex: 'status', width: 90,
              render: (v: string) => {
                const s = statusMap[v] || { color: 'default', text: v };
                return <Tag color={s.color}>{s.text}</Tag>;
              },
            },
            { title: 'GPU/副本', key: 'gpu', width: 100,
              render: (_: unknown, r: ModelDeployment) => `${r.gpuPerReplica} × ${r.replicas}` },
            { title: '模型', dataIndex: 'modelName', ellipsis: true },
            {
              title: '服务地址', dataIndex: 'serviceUrl', ellipsis: true,
              render: (v: string) => v ? <Text code style={{ fontSize: 11 }}>{v}</Text> : '-',
            },
            {
              title: '操作', key: 'actions', width: 100,
              render: (_: unknown, record: ModelDeployment) => (
                <Button
                  type="primary"
                  size="small"
                  icon={<MessageOutlined />}
                  onClick={() => handleSelectService(record)}
                >
                  对话
                </Button>
              ),
            },
          ]}
        />
      )}
    </div>
  );

  // Chat view
  const chatView = selectedService ? (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Chat header */}
      <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0', background: '#fafafa' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} size="small" onClick={handleBack}>返回</Button>
          <Text strong>{selectedService.name}</Text>
          <Tag color={statusMap[selectedService.status]?.color}>{statusMap[selectedService.status]?.text}</Tag>
        </Space>
        <div style={{ marginTop: 8 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {selectedService.modelName} • GPU {selectedService.gpuPerReplica} × {selectedService.replicas} 副本
          </Text>
          <br />
          <Text type="secondary" style={{ fontSize: 12 }}>
            服务地址：<Text code style={{ fontSize: 11 }}>{selectedService.serviceUrl}</Text>
          </Text>
        </div>
      </div>

      {/* Messages area */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px', background: '#f5f5f5' }}>
        {messages.length === 0 ? (
          <Empty description="发送消息开始对话" style={{ marginTop: 80 }} />
        ) : (
          messages.map((msg, idx) => (
            <div
              key={idx}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start',
                marginBottom: 16,
              }}
            >
              <div
                style={{
                  maxWidth: '70%',
                  padding: '10px 14px',
                  borderRadius: 12,
                  background: msg.role === 'user' ? '#1677ff' : '#fff',
                  color: msg.role === 'user' ? '#fff' : '#000',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
                }}
              >
                {msg.content}
              </div>
            </div>
          ))
        )}
        {streaming && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#999' }}>
            <Spin size="small" /> <Text type="secondary" style={{ fontSize: 12 }}>thinking...</Text>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input area */}
      <div style={{ padding: '12px 16px', background: '#fff', borderTop: '1px solid #f0f0f0' }}>
        <Space.Compact style={{ width: '100%' }}>
          <TextArea
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            autoSize={{ minRows: 1, maxRows: 4 }}
            style={{ flex: 1 }}
          />
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={handleSend}
            loading={sending}
            disabled={!inputValue.trim()}
          >
            发送
          </Button>
          <Button icon={<ClearOutlined />} onClick={handleClear} title="清空对话" />
        </Space.Compact>
      </div>
    </div>
  ) : null;

  // Full page layout
  if (selectedService) {
    return (
      <div style={{ height: 'calc(100vh - 120px)', display: 'flex', flexDirection: 'column' }}>
        {chatView}
      </div>
    );
  }

  return serviceListView;
};

export default InferenceChatPage;