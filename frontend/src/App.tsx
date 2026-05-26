import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AuthProvider } from './contexts/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import AppLayout from './components/Layout';
import LoginPage from './pages/Login';
import DashboardPage from './pages/Dashboard';
import PhysicalClustersPage from './pages/PhysicalClusters';
import SpecsPage from './pages/Specs';
import ResourcePoolsPage from './pages/ResourcePools';
import ResourcePoolDetailPage from './pages/ResourcePoolDetail';
import WorkspacesPage from './pages/Workspaces';
import WorkspaceDetailPage from './pages/WorkspaceDetail';
import './styles/global.css';

const App: React.FC = () => (
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        colorPrimary: '#1677ff',
        borderRadius: 6,
      },
    }}
  >
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            element={
              <ProtectedRoute>
                <AppLayout />
              </ProtectedRoute>
            }
          >
            <Route path="/" element={<DashboardPage />} />
            <Route path="/physical-clusters" element={<PhysicalClustersPage />} />
            <Route path="/specs" element={<SpecsPage />} />
            <Route path="/resource-pools" element={<ResourcePoolsPage />} />
            <Route path="/resource-pools/:id" element={<ResourcePoolDetailPage />} />
            <Route path="/workspaces" element={<WorkspacesPage />} />
            <Route path="/workspaces/:id" element={<WorkspaceDetailPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </ConfigProvider>
);

export default App;
