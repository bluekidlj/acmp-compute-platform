package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterNode {
    private String id;
    private String clusterId;
    private String name;
    private String internalIp;
    private Integer cpuCores;
    private Long memoryBytes;
    private Integer gpuCount;
    private String status;
    private String resourcePoolId;
    private String computeSpecId;
    private String labelsJson;
    private String taintsJson;
    private Instant lastSyncAt;
    private Instant createdAt;
    private Instant updatedAt;
}
