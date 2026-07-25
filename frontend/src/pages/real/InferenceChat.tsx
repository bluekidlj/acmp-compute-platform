import { useEffect, useRef, useState } from 'react';
import { ArrowLeftOutlined, DeleteOutlined, RobotOutlined, SendOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Avatar, Button, Input, message, Space, Spin } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/real';
import StatusBadge from '../../components/StatusBadge';
import type { ChatMessage, ModelDeployment } from '../../types';

const systemMessage: ChatMessage = {
  role: 'system',
  content: 'You are Qwen, created by Alibaba Cloud. You are a helpful assistant.',
};

export default function InferenceChatPage() {
  const { projectId = '', deploymentId = '' } = useParams();
  const navigate = useNavigate();
  const [deployment, setDeployment] = useState<ModelDeployment | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([systemMessage]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [usage, setUsage] = useState<string>();
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(function loadDeployment() {
    api.deployment(projectId, deploymentId)
      .then(setDeployment)
      .catch(function fail(exception) { message.error(exception.message); });
  }, [projectId, deploymentId]);

  useEffect(function scrollMessages() {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [messages, sending]);

  async function send() {
    const content = input.trim();
    if (!content || sending || deployment?.status !== 'RUNNING') {
      return;
    }
    const userMessage: ChatMessage = { role: 'user', content };
    const requestMessages = [...messages, userMessage];
    setMessages(requestMessages);
    setInput('');
    setSending(true);
    try {
      const response = await api.chat(projectId, deploymentId, requestMessages);
      const answer = response.choices[0]?.message;
      if (!answer) {
        throw new Error('推理服务没有返回 choices[0].message');
      }
      setMessages(function append(items) {
        return [...items, { role: 'assistant', content: answer.content }];
      });
      if (response.usage) {
        setUsage(`Prompt ${response.usage.prompt_tokens} · Completion ${response.usage.completion_tokens} · Total ${response.usage.total_tokens} tokens`);
      }
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '对话请求失败');
    } finally {
      setSending(false);
    }
  }

  function clear() {
    setMessages([systemMessage]);
    setUsage(undefined);
  }

  const visibleMessages = messages.filter(function excludeSystem(item) {
    return item.role !== 'system';
  });

  return (
    <div className="surface chat-shell">
      <div className="chat-status">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
          <Space>
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={function back() { navigate(`/deployments/${projectId}/${deploymentId}`); }} style={{ color: '#dff7ec' }}>返回</Button>
            <div>
              <strong>{deployment?.name || '推理服务'}</strong>
              <div style={{ color: 'rgba(226, 248, 237, .58)', fontSize: 11 }}>{deployment?.modelName} · OpenAI compatible</div>
            </div>
          </Space>
          <Space>
            <StatusBadge value={deployment?.status} />
            <Button type="text" icon={<DeleteOutlined />} onClick={clear} style={{ color: '#dff7ec' }}>清空对话</Button>
          </Space>
        </div>
      </div>
      {deployment && deployment.status !== 'RUNNING' && (
        <Alert type="warning" showIcon message={`服务当前状态为 ${deployment.status}，运行就绪后才能对话。`} />
      )}
      <div className="chat-messages" ref={listRef}>
        {visibleMessages.length === 0 && (
          <div style={{ maxWidth: 640, margin: '80px auto', textAlign: 'center', color: '#66756f' }}>
            <RobotOutlined style={{ fontSize: 40, color: '#007D4C' }} />
            <h2 style={{ fontWeight: 500, color: '#17231f' }}>开始测试模型服务</h2>
            <p>消息将通过 ACMP 后端安全代理发送到 Kubernetes 内的 vLLM OpenAI 兼容接口。</p>
          </div>
        )}
        {visibleMessages.map(function renderMessage(item, index) {
          return (
            <div className={`chat-message ${item.role}`} key={`${item.role}-${index}`}>
              <Avatar icon={item.role === 'user' ? <UserOutlined /> : <RobotOutlined />} style={{ background: item.role === 'user' ? '#007D4C' : '#071D17' }} />
              <div className="chat-bubble">{item.content}</div>
            </div>
          );
        })}
        {sending && <div style={{ textAlign: 'center', color: '#66756f' }}><Spin size="small" /> 模型生成中</div>}
      </div>
      <div className="chat-input">
        <div style={{ maxWidth: 920, margin: '0 auto' }}>
          <div style={{ display: 'flex', gap: 10 }}>
            <Input.TextArea
              value={input}
              disabled={deployment?.status !== 'RUNNING'}
              placeholder="输入消息，Enter 发送，Shift+Enter 换行"
              autoSize={{ minRows: 1, maxRows: 5 }}
              onChange={function change(event) { setInput(event.target.value); }}
              onPressEnter={function enter(event) {
                if (!event.shiftKey) {
                  event.preventDefault();
                  send();
                }
              }}
            />
            <Button type="primary" icon={<SendOutlined />} loading={sending} disabled={!input.trim() || deployment?.status !== 'RUNNING'} onClick={send}>发送</Button>
          </div>
          <div style={{ minHeight: 20, marginTop: 5, color: '#84928d', fontSize: 11 }}>{usage || '非流式请求 · temperature 0.7 · top_p 0.8'}</div>
        </div>
      </div>
    </div>
  );
}
