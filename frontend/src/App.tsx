import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import RealLayout from './components/RealLayout';
import { useAuth } from './contexts/AuthContext';
import LoginPage from './pages/Login';
import AlertMonitoringPage from './pages/real/AlertMonitoring';
import ClusterDetailPage from './pages/real/ClusterDetail';
import ClustersPage from './pages/real/Clusters';
import NodeDetailPage from './pages/real/NodeDetail';
import DashboardPage from './pages/real/Dashboard';
import DeploymentDetailPage from './pages/real/DeploymentDetail';
import DeploymentsPage from './pages/real/Deployments';
import InferenceChatPage from './pages/real/InferenceChat';
import {
  DigitalTwinPage,
  StrategySimulationPage,
  WorkloadInsightPage,
} from './pages/real/InnovationLab';
import ModelsPage from './pages/real/Models';
import {
  ClusterMonitoringDetailPage,
  ClusterMonitoringListPage,
  DeploymentMonitoringDetailPage,
  DeploymentMonitoringListPage,
  NodeMonitoringPage,
} from './pages/real/Monitoring';
import ProjectDetailPage from './pages/real/ProjectDetail';
import ProjectsPage from './pages/real/Projects';
import ResourcePoolsPage from './pages/real/ResourcePools';
import ResourcePoolDetailPage from './pages/real/ResourcePoolDetail';
import SpecsPage from './pages/real/Specs';
import TenantDetailPage from './pages/real/TenantDetail';
import TenantsPage from './pages/real/Tenants';

function Protected({ children }: { children: ReactNode }) {
  const { username } = useAuth();
  if (!username) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<Protected><RealLayout /></Protected>}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/clusters" element={<ClustersPage />} />
          <Route path="/clusters/:clusterId" element={<ClusterDetailPage />} />
          <Route path="/clusters/:clusterId/nodes/:nodeId" element={<NodeDetailPage />} />
          <Route path="/resource-pools" element={<ResourcePoolsPage />} />
          <Route path="/resource-pools/:poolId" element={<ResourcePoolDetailPage />} />
          <Route path="/specs" element={<SpecsPage />} />
          <Route path="/tenants" element={<TenantsPage />} />
          <Route path="/tenants/:tenantId" element={<TenantDetailPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/deployments" element={<DeploymentsPage />} />
          <Route path="/deployments/:projectId/:deploymentId" element={<DeploymentDetailPage />} />
          <Route path="/deployments/:projectId/:deploymentId/chat" element={<InferenceChatPage />} />
          <Route path="/monitoring/deployments" element={<DeploymentMonitoringListPage />} />
          <Route path="/monitoring/deployments/:projectId/:deploymentId" element={<DeploymentMonitoringDetailPage />} />
          <Route path="/monitoring/clusters" element={<ClusterMonitoringListPage />} />
          <Route path="/monitoring/clusters/:clusterId" element={<ClusterMonitoringDetailPage />} />
          <Route path="/monitoring/clusters/:clusterId/nodes/:nodeId" element={<NodeMonitoringPage />} />
          <Route path="/monitoring/alerts" element={<AlertMonitoringPage />} />
          <Route path="/innovation-lab" element={<Navigate to="/innovation-lab/workload" replace />} />
          <Route path="/innovation-lab/workload" element={<WorkloadInsightPage />} />
          <Route path="/innovation-lab/twin" element={<DigitalTwinPage />} />
          <Route path="/innovation-lab/strategy" element={<StrategySimulationPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
