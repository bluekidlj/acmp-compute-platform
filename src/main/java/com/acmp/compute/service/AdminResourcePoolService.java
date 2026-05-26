package com.acmp.compute.service;

import com.acmp.compute.dto.IssueCredentialRequest;
import com.acmp.compute.dto.IssueCredentialResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;

/**
 * 管理员凭证发放服务：为工作空间成员签发 kubeconfig。
 *
 * 新设计：namespace / SA / RBAC 都在工作空间维度，凭证以 workspaceId 寻址。
 * 旧 API（/admin/resource-pools/{poolId}/issue-credential）保留为兼容层：
 * 通过 poolId 寻找其下唯一工作空间，否则抛错要求改用 workspaceId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminResourcePoolService {

    private final WorkspaceMapper workspaceMapper;
    private final PhysicalClusterMapper physicalClusterMapper;
    private final KubernetesClientManager clientManager;
    private final EncryptionService encryptionService;

    /**
     * 给指定工作空间签发 kubeconfig。
     */
    public IssueCredentialResponse issueCredentialForWorkspace(String workspaceId, IssueCredentialRequest request) {
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));

        if (ws.getPrimaryClusterId() == null || ws.getNamespace() == null || ws.getServiceAccountName() == null) {
            throw new IllegalStateException("工作空间未关联物理集群或缺少 SA: " + workspaceId);
        }

        PhysicalCluster cluster = physicalClusterMapper.findById(ws.getPrimaryClusterId())
                .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + ws.getPrimaryClusterId()));

        Map<String, String> credentials = clientManager.extractServiceAccountCredentials(
                ws.getPrimaryClusterId(), ws.getNamespace(), ws.getServiceAccountName());

        String token = credentials.get("token");
        String caCrt = credentials.get("ca-crt");
        String decryptedKubeconfig = encryptionService.decrypt(cluster.getKubeconfigBase64Encrypted());

        String kubeconfig = buildKubeconfigWithToken(
                decryptedKubeconfig, ws.getNamespace(), request.getUsername(), token, caCrt);

        log.info("✓ 已为用户 {} 在工作空间 {} (ns={}) 签发 kubeconfig",
                request.getUsername(), workspaceId, ws.getNamespace());

        return IssueCredentialResponse.builder()
                .kubeconfig(kubeconfig)
                .namespace(ws.getNamespace())
                .clusterName(cluster.getName())
                .serviceAccountName(ws.getServiceAccountName())
                .message(String.format("凭证已生成，有效期 %d 天，用户: %s",
                        request.getExpireDays(), request.getUsername()))
                .build();
    }

    /**
     * 兼容入口：仅当 poolId 下只有一个工作空间时可用。
     */
    public IssueCredentialResponse issueCredential(String poolId, IssueCredentialRequest request) {
        java.util.List<Workspace> wsList = workspaceMapper.findByResourcePoolId(poolId);
        if (wsList.isEmpty()) {
            throw new ResourceNotFoundException("逻辑池 " + poolId + " 下没有工作空间，无法发放凭证");
        }
        if (wsList.size() > 1) {
            throw new IllegalStateException(
                    "逻辑池 " + poolId + " 下有 " + wsList.size()
                            + " 个工作空间，请改用 /admin/workspaces/{workspaceId}/issue-credential");
        }
        return issueCredentialForWorkspace(wsList.get(0).getId(), request);
    }

    /**
     * 在原始 kubeconfig 之上，用 SA token 重构 kubeconfig，限制访问指定 namespace。
     * 简化实现：保留原始 cluster.server 与 CA。
     */
    private String buildKubeconfigWithToken(String originalKubeconfig, String namespace,
                                           String username, String token, String caCrt) {
        String encodedCa = caCrt != null ? caCrt
                : Base64.getEncoder().encodeToString("".getBytes());

        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: v1\n");
        sb.append("kind: Config\n");
        sb.append("current-context: ws-context\n");
        sb.append("clusters:\n");
        sb.append("- name: kubernetes\n");
        sb.append("  cluster:\n");
        sb.append("    certificate-authority-data: ").append(encodedCa).append("\n");
        sb.append("    server: https://kubernetes.default.svc:443\n");
        sb.append("contexts:\n");
        sb.append("- name: ws-context\n");
        sb.append("  context:\n");
        sb.append("    cluster: kubernetes\n");
        sb.append("    namespace: ").append(namespace).append("\n");
        sb.append("    user: ").append(username).append("\n");
        sb.append("users:\n");
        sb.append("- name: ").append(username).append("\n");
        sb.append("  user:\n");
        sb.append("    token: ").append(token).append("\n");

        return sb.toString();
    }
}
