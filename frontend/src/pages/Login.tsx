import { useState } from 'react';
import { Form, Input, Button, Card, Typography, Alert } from 'antd';
import { UserOutlined, LockOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { useAuth } from '../contexts/AuthContext';
import { PSBC_GREEN, PSBC_COLORS } from '../theme';

const { Title, Text } = Typography;

export default function LoginPage() {
  const nav = useNavigate();
  const { setUser } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form] = Form.useForm();

  const handleLogin = async (values: { username: string; password: string }) => {
    setLoading(true);
    setError(null);
    try {
      const r = await authApi.login(values);
      localStorage.setItem('token', r.token);
      setUser({ username: r.username, role: r.role });
      nav('/');
    } catch (e: any) {
      setError(e?.message || '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        background: `linear-gradient(135deg, ${PSBC_GREEN.token.colorPrimary} 0%, ${PSBC_COLORS.primaryActive} 100%)`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        padding: 20,
      }}
    >
      <Card
        style={{
          width: 420, borderRadius: 12, boxShadow: '0 8px 32px rgba(0,0,0,0.12)',
        }}
        bodyStyle={{ padding: 40 }}
      >
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <ThunderboltOutlined style={{ fontSize: 48, color: PSBC_GREEN.token.colorPrimary }} />
          <Title level={3} style={{ marginTop: 12, marginBottom: 4, color: PSBC_GREEN.token.colorPrimary }}>
            ACMP
          </Title>
          <Text type="secondary">异构算力管理平台</Text>
        </div>

        {error && (
          <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon />
        )}

        <Form form={form} onFinish={handleLogin} layout="vertical" size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              style={{ background: PSBC_GREEN.token.colorPrimary, borderColor: PSBC_GREEN.token.colorPrimary }}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}