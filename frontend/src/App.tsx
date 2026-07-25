import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import RealLayout from './components/RealLayout';
import { useAuth } from './contexts/AuthContext';
import LoginPage from './pages/Login';
import ClusterDetailPage from './pages/real/ClusterDetail';
import ClustersPage from './pages/real/Clusters';
import DashboardPage from './pages/real/Dashboard';
import DeploymentDetailPage from './pages/real/DeploymentDetail';
import DeploymentsPage from './pages/real/Deployments';
import InferenceChatPage from './pages/real/InferenceChat';
import InnovationLabPage from './pages/real/InnovationLab';
import ModelsPage from './pages/real/Models';
import ProjectDetailPage from './pages/real/ProjectDetail';
import ProjectsPage from './pages/real/Projects';
import ResourcePoolsPage from './pages/real/ResourcePools';
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
          <Route path="/resource-pools" element={<ResourcePoolsPage />} />
          <Route path="/specs" element={<SpecsPage />} />
          <Route path="/tenants" element={<TenantsPage />} />
          <Route path="/tenants/:tenantId" element={<TenantDetailPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/deployments" element={<DeploymentsPage />} />
          <Route path="/deployments/:projectId/:deploymentId" element={<DeploymentDetailPage />} />
          <Route path="/deployments/:projectId/:deploymentId/chat" element={<InferenceChatPage />} />
          <Route path="/innovation-lab" element={<InnovationLabPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
