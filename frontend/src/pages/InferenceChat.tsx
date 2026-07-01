import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Input, Button, Space, Spin, Tag, Avatar, Empty } from 'antd';
import { SendOutlined, ArrowLeftOutlined, RobotOutlined, UserOutlined } from '@ant-design/icons';
import { deploymentsApi } from '../api';
import type { ModelDeployment } from '../types';
import PageHeader from '../components/PageHeader';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  ts: string;
}

const MOCK_RESPONSES = [
  '你好！我是 ACMP 算力管理平台的演示 AI 助手（基于 vLLM）。当前部署在 K8s 上，推理服务运行正常。',
  'ACMP 异构算力管理平台支持 NVIDIA、Hygon DCU、华为昇腾 多品牌 GPU 池化，支持 1/4、1/2 等 HAMi 切分。',
  '您可以通过资源管理 → 加卡到池，将物理卡按规格加入资源池。1 张卡 + 1 规格 = N 个可调度节点。',
  '当前 mock 数据：1 个生产集群 (3 节点)，2 个工作空间 (ai-rd, cv-team, nlp-team)，3 个项目，4 个部署。',
];

export default function InferenceChatPage() {
  const { deploymentId } = useParams<{ deploymentId: string }>();
  const nav = useNavigate();
  const [dep, setDep] = useState<ModelDeployment | null>(null);
  const [messages, setMessages] = useState<Message[]>([
    { role: 'assistant', content: '你好！我是基于 vLLM 部署的推理服务。有什么可以帮你的？', ts: new Date().toISOString() },
  ]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const mockIdx = useRef(0);

  useEffect(() => {
    if (!deploymentId) return;
    deploymentsApi.get('proj-llm', deploymentId).then(setDep).catch(() => {});
  }, [deploymentId]);

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages]);

  const handleSend = () => {
    if (!input.trim() || loading) return;
    const userMsg: Message = { role: 'user', content: input, ts: new Date().toISOString() };
    setMessages((m) => [...m, userMsg]);
    setInput('');
    setLoading(true);
    setTimeout(() => {
      const reply = MOCK_RESPONSES[mockIdx.current % MOCK_RESPONSES.length];
      mockIdx.current += 1;
      setMessages((m) => [...m, { role: 'assistant', content: reply, ts: new Date().toISOString() }]);
      setLoading(false);
    }, 800);
  };

  return (
    <div>
      <PageHeader
        title={dep ? `对话：${dep.name}` : '对话'}
        subtitle={dep ? `${dep.modelName || '-'} · ${dep.specId} · ${dep.status}` : ''}
        tags={dep ? [{ label: dep.status, color: dep.status === 'running' ? 'green' : 'red' }] : []}
        extra={
          <Button icon={<ArrowLeftOutlined />} onClick={() => dep ? nav(`/logical/deployments/${dep.projectId}/${dep.id}`) : nav(-1)}>
            返回
          </Button>
        }
      />
      <Card
        style={{ borderRadius: 8, height: 'calc(100vh - 220px)', display: 'flex', flexDirection: 'column' }}
        bodyStyle={{ padding: 0, display: 'flex', flexDirection: 'column', height: '100%' }}
      >
        <div ref={listRef} style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
          {messages.length === 0 && <Empty description="开始对话..." />}
          {messages.map((m, i) => (
            <div
              key={i}
              style={{
                display: 'flex', gap: 12, marginBottom: 16,
                flexDirection: m.role === 'user' ? 'row-reverse' : 'row',
              }}
            >
              <Avatar
                icon={m.role === 'user' ? <UserOutlined /> : <RobotOutlined />}
                style={{ background: m.role === 'user' ? '#00754A' : '#52C41A' }}
              />
              <div
                style={{
                  background: m.role === 'user' ? '#E6F4ED' : '#F5F7F5',
                  padding: '10px 14px', borderRadius: 8, maxWidth: '70%',
                }}
              >
                <div style={{ whiteSpace: 'pre-wrap', fontSize: 14 }}>{m.content}</div>
                <div style={{ fontSize: 11, color: '#9CA8A0', marginTop: 4 }}>{m.ts.slice(11, 19)}</div>
              </div>
            </div>
          ))}
          {loading && (
            <div style={{ textAlign: 'center' }}>
              <Spin size="small" /> 思考中...
            </div>
          )}
        </div>
        <div style={{ borderTop: '1px solid #E5EBE7', padding: 12, display: 'flex', gap: 8 }}>
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="输入消息（演示模式）"
            autoSize={{ minRows: 1, maxRows: 4 }}
            onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); handleSend(); } }}
            style={{ resize: 'none' }}
          />
          <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={loading}
            style={{ background: '#00754A', borderColor: '#00754A' }}>
            发送
          </Button>
        </div>
      </Card>
    </div>
  );
}