package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 对账报告：DB 与 K8s 实际状态不一致的部署列表。
 */
@Data
@Builder
public class AuditReport {

    private Instant generatedAt;
    private Integer totalDeployments;
    private Integer orphanCount;
    private Integer quotaMismatchCount;
    private List<OrphanDeployment> orphanDeployments;
    private List<QuotaMismatch> quotaMismatches;

    @Data
    @Builder
    public static class OrphanDeployment {
        private String deploymentId;
        private String projectId;
        private String workspaceId;
        private String k8sDeploymentName;
        private String k8sNamespace;
        private String reason;
    }

    @Data
    @Builder
    public static class QuotaMismatch {
        private String quotaId;
        private String projectId;
        private String resourcePoolId;
        private String specId;
        private Integer dbUsedNodes;
        private Integer k8sReadyReplicas;
        private String reason;
    }
}
