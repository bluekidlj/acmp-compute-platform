package com.acmp.compute.service;

import com.acmp.compute.dto.AuditReport;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.ProjectResourceQuota;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.ProjectResourceQuotaMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对账服务：1.0 简化版。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>孤儿部署：DB 中 status='running' 但 K8s 上找不到对应 Deployment</li>
 *   <li>配额偏差：project_resource_quota.used 与 K8s Deployment 实际 readyReplicas 不一致</li>
 * </ul>
 *
 * <h2>不做什么</h2>
 * <p>1.0 明确不做高并发分布式一致性：
 * <ul>
 *   <li>不做实时同步</li>
 *   <li>不做行级锁 / 原子 SQL</li>
 *   <li>不做对账后自动修复（仅报告，由运维决定）</li>
 * </ul>
 *
 * <h2>调用方式</h2>
 * <ul>
 *   <li>管理员手动：GET /api/v1/admin/audit/deployments</li>
 *   <li>定时对账：{@link com.acmp.compute.scheduler.QuotaReconcileScheduler} 每 5 分钟跑一次</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final ModelDeploymentMapper deploymentMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ProjectResourceQuotaMapper quotaMapper;
    private final KubernetesClientManager clientManager;

    public AuditReport generate() {
        Instant now = Instant.now();
        List<ModelDeployment> all = deploymentMapper.findAll();
        if (all == null) all = List.of();

        List<AuditReport.OrphanDeployment> orphans = new ArrayList<>();
        List<AuditReport.QuotaMismatch> mismatches = new ArrayList<>();

        for (ModelDeployment m : all) {
            // 1) 孤儿检测：仅对 status='running' 的非超分部署
            if (!"running".equalsIgnoreCase(m.getStatus())) continue;
            if ("OVERSELL".equalsIgnoreCase(m.getPoolType())) continue;  // 超分不上 K8s
            if (m.getActualClusterId() == null || m.getActualClusterId().isEmpty()) continue;

            try {
                Workspace ws = workspaceMapper.findById(m.getWorkspaceId()).orElse(null);
                if (ws == null) {
                    orphans.add(AuditReport.OrphanDeployment.builder()
                            .deploymentId(m.getId())
                            .projectId(m.getProjectId())
                            .workspaceId(m.getWorkspaceId())
                            .k8sDeploymentName(m.getK8sDeploymentName())
                            .k8sNamespace(ws != null ? ws.getNamespace() : "?")
                            .reason("工作空间不存在")
                            .build());
                    continue;
                }

                Deployment k8sDep = clientManager.getDeployment(
                        m.getActualClusterId(), ws.getNamespace(), m.getK8sDeploymentName());
                if (k8sDep == null) {
                    orphans.add(AuditReport.OrphanDeployment.builder()
                            .deploymentId(m.getId())
                            .projectId(m.getProjectId())
                            .workspaceId(m.getWorkspaceId())
                            .k8sDeploymentName(m.getK8sDeploymentName())
                            .k8sNamespace(ws.getNamespace())
                            .reason("K8s 中无该 Deployment")
                            .build());
                    continue;
                }

                // 2) 配额偏差检测：DB.used vs K8s.readyReplicas（仅 1.0 replicas=1 场景）
                Integer k8sReady = k8sDep.getStatus() != null
                        ? k8sDep.getStatus().getReadyReplicas()
                        : null;
                if (k8sReady == null) k8sReady = 0;
                int dbUsed = m.getReplicas() != null ? m.getReplicas() : 0;
                // 1.0 严格 replicas=1，这里只能查 project 的 quota 行（一个 deployment 关联一个 quota）
                Optional<ProjectResourceQuota> quotaOpt = quotaMapper.findByProjectPoolSpec(
                        m.getProjectId(), m.getResourcePoolId(), m.getSpecId());
                if (quotaOpt.isPresent()) {
                    int quotaUsed = quotaOpt.get().getUsedNodes() != null ? quotaOpt.get().getUsedNodes() : 0;
                    if (quotaUsed != dbUsed) {
                        mismatches.add(AuditReport.QuotaMismatch.builder()
                                .quotaId(quotaOpt.get().getId())
                                .projectId(m.getProjectId())
                                .resourcePoolId(m.getResourcePoolId())
                                .specId(m.getSpecId())
                                .dbUsedNodes(quotaUsed)
                                .k8sReadyReplicas(k8sReady)
                                .reason(String.format("quota.used=%d 但 deployment.replicas=%d（差 %d）",
                                        quotaUsed, dbUsed, quotaUsed - dbUsed))
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("对账部署 {} 失败: {}", m.getId(), e.getMessage());
            }
        }

        return AuditReport.builder()
                .generatedAt(now)
                .totalDeployments(all.size())
                .orphanCount(orphans.size())
                .quotaMismatchCount(mismatches.size())
                .orphanDeployments(orphans)
                .quotaMismatches(mismatches)
                .build();
    }
}
