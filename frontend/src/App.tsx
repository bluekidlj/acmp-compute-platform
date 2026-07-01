import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './contexts/AuthContext';
import AppLayout from './components/Layout';
import LoginPage from './pages/Login';
import DashboardPage from './pages/Dashboard';
import SpecsPage from './pages/Specs';
import PhysicalPoolsPage from './pages/PhysicalPools';
import PhysicalPoolDetailPage from './pages/PhysicalPoolDetail';
import PoolCardsManagePage from './pages/PoolCardsManage';
import WorkspacesPage from './pages/Workspaces';
import LogicalPoolDetailPage from './pages/LogicalPoolDetail';
import DeploymentsListPage from './pages/DeploymentsList';
import DeploymentDetailPage from './pages/DeploymentDetail';
import ModelMallPage from './pages/ModelMall';
import TrainingPage from './pages/Training';
import MonitoringPage from './pages/Monitoring';
import AlertsPage from './pages/Alerts';
import AlertRulesPage from './pages/AlertRules';
import PhysicalClustersPage from './pages/PhysicalClusters';
import PhysicalClusterDetailPage from './pages/PhysicalClusterDetail';
import WorkloadsPage from './pages/Workloads';
import StoragePage from './pages/Storage';
import { Spin } from 'antd';

function Protected({ children }: { children: React.ReactNode }) {
  const { username } = useAuth();
  if (!username) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<Protected><AppLayout /></Protected>}>
          {/* 智算运营 */}
          <Route path="/" element={<DashboardPage />} />
          <Route path="/resources/specs" element={<SpecsPage />} />
          <Route path="/resources/pools" element={<PhysicalPoolsPage />} />
          <Route path="/resources/pools/:wsId/:poolId" element={<PhysicalPoolDetailPage />} />
          <Route path="/resources/cards" element={<PoolCardsManagePage />} />
          <Route path="/logical/workspaces" element={<WorkspacesPage />} />
          <Route path="/logical/workspaces/:wsId" element={<LogicalPoolDetailPage />} />
          <Route path="/logical/deployments/:projectId" element={<DeploymentsListPage />} />
          <Route path="/logical/deployments/:projectId/:deploymentId" element={<DeploymentDetailPage />} />
          <Route path="/models" element={<ModelMallPage />} />
          <Route path="/training" element={<TrainingPage />} />
          {/* 监控预警 */}
          <Route path="/monitoring" element={<MonitoringPage />} />
          <Route path="/monitoring/alerts" element={<AlertsPage />} />
          <Route path="/monitoring/rules" element={<AlertRulesPage />} />
          {/* 集群运维 */}
          <Route path="/clusters" element={<PhysicalClustersPage />} />
          <Route path="/clusters/:id" element={<PhysicalClusterDetailPage />} />
          <Route path="/workloads" element={<WorkloadsPage />} />
          <Route path="/storage" element={<StoragePage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;