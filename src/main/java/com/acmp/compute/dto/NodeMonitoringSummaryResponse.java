package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 节点监控摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeMonitoringSummaryResponse {
    private String nodeId;
    private String clusterId;
    private String nodeName;
    private String internalIp;
    private String status;
    private Integer cpuCores;
    private Long memoryBytes;
    private Integer gpuCount;
    private Double cpuUsagePercent;
    private Double memoryUsagePercent;
    private Double diskUsagePercent;
    private Double networkReceiveMbps;
    private Double networkTransmitMbps;
    private Double loadAverage1m;
    private Double gpuUsagePercent;
    private Double gpuMemoryUsedMib;
    private Instant lastCollectedAt;
}
