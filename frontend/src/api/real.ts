import axios from 'axios';
import type {
  ChatCompletionResponse,
  ChatMessage,
  ClusterNode,
  ComputeSpec,
  DeploymentRequest,
  GpuDevice,
  LoginResponse,
  Model,
  ModelDeployment,
  PhysicalCluster,
  Project,
  ResourcePool,
  Tenant,
  TenantSpecQuota,
} from '../types';

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 120000,
  headers: {
    'Content-Type': 'application/json',
  },
});

client.interceptors.request.use(function addToken(config) {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  function passResponse(response) {
    return response;
  },
  function handleError(error) {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    const message =
      error.response?.data?.message ||
      error.response?.data?.error ||
      error.message ||
      '请求失败';
    return Promise.reject(new Error(message));
  },
);

export const api = {
  async login(username: string, password: string): Promise<LoginResponse> {
    const response = await client.post<LoginResponse>('/auth/login', { username, password });
    return response.data;
  },

  async clusters(): Promise<PhysicalCluster[]> {
    return (await client.get<PhysicalCluster[]>('/clusters')).data;
  },
  async cluster(id: string): Promise<PhysicalCluster> {
    return (await client.get<PhysicalCluster>(`/clusters/${id}`)).data;
  },
  async createCluster(body: { name: string; description?: string; kubeconfig: string }): Promise<PhysicalCluster> {
    return (await client.post<PhysicalCluster>('/clusters', body)).data;
  },
  async syncCluster(id: string): Promise<PhysicalCluster> {
    return (await client.post<PhysicalCluster>(`/clusters/${id}/sync`)).data;
  },
  async deleteCluster(id: string): Promise<void> {
    await client.delete(`/clusters/${id}`);
  },
  async nodes(clusterId: string): Promise<ClusterNode[]> {
    return (await client.get<ClusterNode[]>(`/clusters/${clusterId}/nodes`)).data;
  },
  async gpus(clusterId: string): Promise<GpuDevice[]> {
    return (await client.get<GpuDevice[]>(`/clusters/${clusterId}/gpus`)).data;
  },

  async pools(): Promise<ResourcePool[]> {
    return (await client.get<ResourcePool[]>('/resource-pools')).data;
  },
  async poolGpus(poolId: string): Promise<GpuDevice[]> {
    return (await client.get<GpuDevice[]>(`/resource-pools/${poolId}/gpus`)).data;
  },
  async joinPoolGpu(
    poolId: string,
    gpuId: string,
    body: {
      name: string;
      displayName?: string;
      gpuShare?: '1/8' | '1/4' | '1/2';
      cpuCores: number;
      memoryGib: number;
      description?: string;
    },
  ): Promise<ComputeSpec> {
    return (
      await client.post<ComputeSpec>(
        `/resource-pools/${poolId}/gpus/${gpuId}/join`,
        body,
      )
    ).data;
  },

  async specs(): Promise<ComputeSpec[]> {
    return (await client.get<ComputeSpec[]>('/specs')).data;
  },

  async tenants(): Promise<Tenant[]> {
    return (await client.get<Tenant[]>('/tenants')).data;
  },
  async tenant(id: string): Promise<Tenant> {
    return (await client.get<Tenant>(`/tenants/${id}`)).data;
  },
  async createTenant(body: { name: string; description?: string }): Promise<Tenant> {
    return (await client.post<Tenant>('/tenants', body)).data;
  },
  async updateTenant(id: string, body: { name: string; description?: string }): Promise<Tenant> {
    return (await client.put<Tenant>(`/tenants/${id}`, body)).data;
  },
  async deleteTenant(id: string): Promise<void> {
    await client.delete(`/tenants/${id}`);
  },
  async tenantQuotas(id: string): Promise<TenantSpecQuota[]> {
    return (await client.get<TenantSpecQuota[]>(`/tenants/${id}/spec-quotas`)).data;
  },
  async createTenantQuota(id: string, specId: string, total: number): Promise<TenantSpecQuota> {
    return (await client.post<TenantSpecQuota>(`/tenants/${id}/spec-quotas`, { specId, total })).data;
  },
  async updateTenantQuota(id: string, quotaId: string, total: number): Promise<TenantSpecQuota> {
    return (await client.patch<TenantSpecQuota>(`/tenants/${id}/spec-quotas/${quotaId}`, { total })).data;
  },
  async deleteTenantQuota(id: string, quotaId: string): Promise<void> {
    await client.delete(`/tenants/${id}/spec-quotas/${quotaId}`);
  },

  async projects(tenantId: string): Promise<Project[]> {
    return (await client.get<Project[]>(`/tenants/${tenantId}/projects`)).data;
  },
  async project(id: string): Promise<Project> {
    return (await client.get<Project>(`/projects/${id}`)).data;
  },
  async createProject(tenantId: string, body: { name: string; description?: string }): Promise<Project> {
    return (await client.post<Project>(`/tenants/${tenantId}/projects`, body)).data;
  },
  async availableSpecs(projectId: string): Promise<TenantSpecQuota[]> {
    return (await client.get<TenantSpecQuota[]>(`/projects/${projectId}/available-specs`)).data;
  },

  async models(): Promise<Model[]> {
    return (await client.get<Model[]>('/models')).data;
  },
  async createModel(body: Partial<Model>): Promise<Model> {
    return (await client.post<Model>('/models', body)).data;
  },
  async deleteModel(id: string): Promise<void> {
    await client.delete(`/models/${id}`);
  },

  async deployments(params?: { tenantId?: string; projectId?: string; status?: string }): Promise<ModelDeployment[]> {
    return (await client.get<ModelDeployment[]>('/deployments', { params })).data;
  },
  async deployment(projectId: string, id: string): Promise<ModelDeployment> {
    return (await client.get<ModelDeployment>(`/projects/${projectId}/deployments/${id}`)).data;
  },
  async createDeployment(projectId: string, body: DeploymentRequest): Promise<ModelDeployment> {
    return (await client.post<ModelDeployment>(`/projects/${projectId}/deployments`, body)).data;
  },
  async deleteDeployment(projectId: string, id: string): Promise<void> {
    await client.delete(`/projects/${projectId}/deployments/${id}`);
  },
  async chat(
    projectId: string,
    deploymentId: string,
    messages: ChatMessage[],
  ): Promise<ChatCompletionResponse> {
    const body = {
      messages,
      temperature: 0.7,
      topP: 0.8,
      repetitionPenalty: 1.05,
      maxTokens: 512,
    };
    return (
      await client.post<ChatCompletionResponse>(
        `/projects/${projectId}/deployments/${deploymentId}/chat/completions`,
        body,
      )
    ).data;
  },
};
