import { mockResponse } from './index';
import { mockWorkspaces, mockMembers } from './data';
import type {
  Workspace, WorkspaceCreateRequest, WorkspaceUpdateRequest,
  AddMemberRequest, IssueCredentialRequest, IssueCredentialResponse,
} from '../types';

export const mockWorkspaceApi = {
  list: () => mockResponse<Workspace[]>(mockWorkspaces),

  get: (id: string) => {
    const ws = mockWorkspaces.find((w) => w.id === id);
    return ws
      ? mockResponse<Workspace>(ws)
      : Promise.reject({ response: { status: 404, data: { message: '工作空间不存在' } } });
  },

  create: async (data: WorkspaceCreateRequest) => {
    const poolName = data.resourcePoolId === 'pool-algo-uuid' ? '算法部资源池'
      : data.resourcePoolId === 'pool-infra-uuid' ? '基础架构部资源池'
      : 'AI Lab 资源池';
    const newWs: Workspace = {
      id: 'ws-' + Math.random().toString(36).slice(2, 10),
      name: data.name,
      description: data.description,
      resourcePoolId: data.resourcePoolId,
      resourcePoolName: poolName,
      namespace: 'ws-' + data.name.toLowerCase().replace(/[^a-z0-9]/g, '-') + '-' + Math.random().toString(36).slice(2, 10),
      volcanoQueueName: 'queue-ws-' + data.name.toLowerCase().replace(/[^a-z0-9]/g, '-'),
      primaryClusterId: 'c1-nvidia-uuid',
      maxPods: data.maxPods || 50,
      createdBy: 'admin',
      status: 'active',
      specQuotas: (data.specQuotas || []).map((q) => ({
        specId: 'spec-' + q.specName,
        specName: q.specName,
        maxNodes: q.maxQuota,
        usedNodes: 0,
        availableNodes: q.maxQuota,
      })),
      createdAt: new Date().toISOString(),
    };
    mockWorkspaces.push(newWs);
    mockMembers[newWs.id] = [];
    return mockResponse<Workspace>(newWs);
  },

  update: (id: string, data: WorkspaceUpdateRequest) => {
    const ws = mockWorkspaces.find((w) => w.id === id);
    if (!ws) return Promise.reject({ response: { status: 404, data: { message: '工作空间不存在' } } });
    if (data.name) ws.name = data.name;
    if (data.description !== undefined) ws.description = data.description;
    return mockResponse<Workspace>(ws);
  },

  delete: (id: string) => {
    const idx = mockWorkspaces.findIndex((w) => w.id === id);
    if (idx >= 0) mockWorkspaces.splice(idx, 1);
    delete mockMembers[id];
    return mockResponse<{ message: string }>({ message: '已删除' });
  },

  members: (id: string) => {
    return mockResponse<string[]>(mockMembers[id] || []);
  },

  addMember: (id: string, data: AddMemberRequest) => {
    if (!mockMembers[id]) mockMembers[id] = [];
    if (!mockMembers[id].includes(data.userId)) {
      mockMembers[id].push(data.userId);
    }
    return mockResponse<{ message: string }>({ message: '成员已添加' });
  },

  removeMember: (workspaceId: string, userId: string) => {
    if (mockMembers[workspaceId]) {
      mockMembers[workspaceId] = mockMembers[workspaceId].filter((u) => u !== userId);
    }
    return mockResponse<{ message: string }>({ message: '成员已移除' });
  },

  issueCredential: (_workspaceId: string, data: IssueCredentialRequest) => {
    return mockResponse<IssueCredentialResponse>({
      kubeconfig: `apiVersion: v1\nkind: Config\nclusters:\n- cluster:\n    server: https://k8s-mock.example.com\n  name: mock-cluster\nusers:\n- name: ${data.username}\n`,
      namespace: 'ws-llm-training-a1b2c3d4',
      clusterName: 'beijing-nvidia-01',
      serviceAccountName: 'sa-ws-llm-training-a1b2c3d4',
      message: `凭证已生成，有效期 ${data.expireDays} 天，用户: ${data.username}`,
    });
  },
};
