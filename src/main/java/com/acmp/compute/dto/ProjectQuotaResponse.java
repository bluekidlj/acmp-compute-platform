package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ProjectQuotaResponse {
    private String id;
    private String projectId;
    private String poolId;
    private String specId;
    private Integer totalNodes;
    private Integer usedNodes;
    private Integer availableNodes;
    private Instant createdAt;
    private Instant updatedAt;
}
