package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class WorkspaceResponse {
    private String id, name, description, resourcePoolId, resourcePoolName;
    private String namespace, volcanoQueueName, primaryClusterId;
    private Integer gpuSlots, cpuCores, memoryGib, maxPods;
    private String hardwareType;
    private GpuBrand gpuType;
    private String jobTypes;
    private String createdBy, status;
    private WorkspaceQuotaResponse quota;
    private Instant createdAt, updatedAt;
}
