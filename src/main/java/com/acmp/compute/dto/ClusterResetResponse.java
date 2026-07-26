package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClusterResetResponse {
    private Boolean success;
    private Integer clearedQuotaCount;
    private Integer clearedSpecCount;
    private List<ClusterResult> clusters;

    @Data
    @Builder
    public static class ClusterResult {
        private String clusterId;
        private String clusterName;
        private Boolean success;
        private Integer clearedNodeLabelCount;
        private String message;
    }
}
