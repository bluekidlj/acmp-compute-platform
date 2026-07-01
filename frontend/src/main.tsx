import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { PSBC_GREEN } from './theme';
import { AuthProvider } from './contexts/AuthContext';
import App from './App';

export default function Root() {
  return (
    <ConfigProvider locale={zhCN} theme={PSBC_GREEN}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </ConfigProvider>
  );
}