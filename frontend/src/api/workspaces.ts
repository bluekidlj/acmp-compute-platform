import apiClient from './client';
import type {
  Workspace,
  WorkspaceCreateRequest,
  WorkspaceUpdateRequest,
  AddMemberRequest,
  IssueCredentialRequest,
  IssueCredentialResponse,
} from '../types';

export const workspaceApi = {
  list: () => apiClient.get<Workspace[]>('/workspaces'),
  get: (id: string) => apiClient.get<Workspace>(`/workspaces/${id}`),
  create: (data: WorkspaceCreateRequest) => apiClient.post<Workspace>('/workspaces', data),
  update: (id: string, data: WorkspaceUpdateRequest) =>
    apiClient.put<Workspace>(`/workspaces/${id}`, data),
  delete: (id: string) => apiClient.delete<{ message: string }>(`/workspaces/${id}`),

  // 成员管理
  members: (id: string) => apiClient.get<string[]>(`/workspaces/${id}/members`),
  addMember: (id: string, data: AddMemberRequest) =>
    apiClient.post<{ message: string }>(`/workspaces/${id}/members`, data),
  removeMember: (workspaceId: string, userId: string) =>
    apiClient.delete<{ message: string }>(`/workspaces/${workspaceId}/members/${userId}`),

  // 凭证发放
  issueCredential: (workspaceId: string, data: IssueCredentialRequest) =>
    apiClient.post<IssueCredentialResponse>(`/admin/workspaces/${workspaceId}/issue-credential`, data),
};
