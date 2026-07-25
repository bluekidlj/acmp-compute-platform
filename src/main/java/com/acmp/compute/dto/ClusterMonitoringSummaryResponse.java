package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 集群监控列表和详情顶部使用的摘要。
 */
@Data
@Builder
public class ClusterMonitoringSummaryResponse {
    private String clusterId;
    private String name;
    private String status;
    private Integer nodeCount;
    private Integer readyNodeCount;
    private Integer gpuCount;
    private Double cpuUsagePercent;
    private Double memoryUsagePercent;
    private Double gpuUsagePercent;
    private Double gpuMemoryUsedMib;
    private Instant lastCollectedAt;
}
