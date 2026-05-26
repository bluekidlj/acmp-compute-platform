import apiClient from './client';
import type {
  Workspace,
  WorkspaceCreateRequest,
  WorkspaceUpdateRequest,
  AddMemberRequest,
  IssueCredentialRequest,
  IssueCredentialResponse,
} from '../types';
import { USE_MOCK } from '../mock';
import { mockWorkspaceApi } from '../mock/workspaces';

const realApi = {
  list: () => apiClient.get<Workspace[]>('/workspaces'),
  get: (id: string) => apiClient.get<Workspace>(`/workspaces/${id}`),
  create: (data: WorkspaceCreateRequest) => apiClient.post<Workspace>('/workspaces', data),
  update: (id: string, data: WorkspaceUpdateRequest) =>
    apiClient.put<Workspace>(`/workspaces/${id}`, data),
  delete: (id: string) => apiClient.delete<{ message: string }>(`/workspaces/${id}`),

  members: (id: string) => apiClient.get<string[]>(`/workspaces/${id}/members`),
  addMember: (id: string, data: AddMemberRequest) =>
    apiClient.post<{ message: string }>(`/workspaces/${id}/members`, data),
  removeMember: (workspaceId: string, userId: string) =>
    apiClient.delete<{ message: string }>(`/workspaces/${workspaceId}/members/${userId}`),

  issueCredential: (workspaceId: string, data: IssueCredentialRequest) =>
    apiClient.post<IssueCredentialResponse>(`/admin/workspaces/${workspaceId}/issue-credential`, data),
};

export const workspaceApi = USE_MOCK ? mockWorkspaceApi : realApi;
