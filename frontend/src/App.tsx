import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './contexts/AuthContext';
import AppLayout from './components/Layout';
import LoginPage from './pages/Login';
import DashboardPage from './pages/Dashboard';
import SpecsPage from './pages/Specs';
import PhysicalPoolsPage from './pages/PhysicalPools';
import PhysicalPoolDetailPage from './pages/PhysicalPoolDetail';
import WorkspacesPage from './pages/Workspaces';
import LogicalPoolDetailPage from './pages/LogicalPoolDetail';
import DeploymentsListPage from './pages/DeploymentsList';
import DeploymentDetailPage from './pages/DeploymentDetail';
import ModelMallPage from './pages/ModelMall';
import TrainingPage from './pages/Training';
import InferenceServicesPage from './pages/InferenceServices';
import InferenceChatPage from './pages/InferenceChat';
import LabDashboardPage from './pages/LabDashboard';
import DigitalTwinPage from './pages/DigitalTwin';
import StrategyLabPage from './pages/StrategyLab';
import WorkloadInsightPage from './pages/WorkloadInsight';
import DataGovernancePage from './pages/DataGovernance';
import ScreenDashboardPage from './pages/ScreenDashboard';
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
        <Route path="/screen" element={<ScreenDashboardPage />} />
        <Route element={<Protected><AppLayout /></Protected>}>
          {/* 智算运营 */}
          <Route path="/" element={<DashboardPage />} />
          <Route path="/inference" element={<InferenceServicesPage />} />
          <Route path="/inference/:deploymentId/chat" element={<InferenceChatPage />} />
          <Route path="/projects" element={<WorkspacesPage />} />
          <Route path="/projects/:wsId" element={<LogicalPoolDetailPage />} />
          <Route path="/projects/:wsId/deployments/:projectId" element={<DeploymentsListPage />} />
          <Route path="/projects/:wsId/deployments/:projectId/:deploymentId" element={<DeploymentDetailPage />} />
          <Route path="/resources/specs" element={<SpecsPage />} />
          <Route path="/resources/pools" element={<PhysicalPoolsPage />} />
          <Route path="/resources/pools/:wsId/:poolId" element={<PhysicalPoolDetailPage />} />
          <Route path="/models" element={<ModelMallPage />} />
          <Route path="/training" element={<TrainingPage />} />
          {/* 创新实验室 */}
          <Route path="/lab" element={<LabDashboardPage />} />
          <Route path="/lab/digital-twin" element={<DigitalTwinPage />} />
          <Route path="/lab/strategy-lab" element={<StrategyLabPage />} />
          <Route path="/lab/workload" element={<WorkloadInsightPage />} />
          <Route path="/lab/governance" element={<DataGovernancePage />} />
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