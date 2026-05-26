package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResourcePoolResponse {
    private String id, name, description, departmentCode, departmentName, namespace;
    private List<String> physicalClusterIds;
    private Integer gpuSlots, cpuCores, memoryGiB, maxPods, nodeCount;
    private Integer allocatedGpuSlots, allocatedCpuCores, allocatedMemoryGib, availableGpuSlots;
    private String hardwareType;
    private GpuBrand gpuType;
    private String jobTypes, volcanoQueueName, status;
    private Instant createdAt, updatedAt;
}
