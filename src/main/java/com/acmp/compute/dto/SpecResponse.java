package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SpecResponse {
    private String id;
    private String name;
    private String displayName;
    private GpuBrand gpuBrand;
    private String specType;
    private String resourcePoolId;
    private String gpuModel;
    private Integer gpuCount;
    private Integer cpuCores;
    private Integer memoryGib;
    private String gpuShare;
    private String description;
    private String status;
    private Integer capacityNodes;
    private Integer allocatedNodes;
    private Integer usedNodes;
    private String sourceGpuId;
    private String sourceGpuUuid;
    private Integer sourceGpuIndex;
    private String sourceNodeName;
    private String resourcePoolName;
    private Instant createdAt;
    private Instant updatedAt;
}
