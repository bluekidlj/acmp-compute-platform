package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class PhysicalClusterResponse {
    private String id;
    private String name;
    private String description;
    private String status;
    private String kubernetesVersion;
    private Integer nodeCount;
    private Integer gpuCount;
    private Instant lastSyncAt;
    private String syncMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
