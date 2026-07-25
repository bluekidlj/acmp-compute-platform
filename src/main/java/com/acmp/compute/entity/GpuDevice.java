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
public class GpuDevice {
    private String id;
    private String clusterId;
    private String nodeId;
    private String nodeName;
    private Integer gpuIndex;
    private String uuid;
    private GpuBrand gpuBrand;
    private String gpuModel;
    private Long memoryMb;
    private String driverVersion;
    private String cudaVersion;
    private String status;
    private String resourcePoolId;
    private String computeSpecId;
    private String usageStatus;
    private Instant lastSyncAt;
    private Instant createdAt;
    private Instant updatedAt;
}
