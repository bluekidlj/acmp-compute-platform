package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class DeploymentMetricsResponse {
    private String deploymentId;
    private boolean available;
    private String message;
    private Instant collectedAt;
    private Double runningRequests;
    private Double waitingRequests;
    private Double promptTokensTotal;
    private Double generationTokensTotal;
    private Double successfulRequestsTotal;
    private Double gpuCacheUsagePercent;
    private Double averageE2eLatencyMs;
    private Double averageTimeToFirstTokenMs;
}
