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
    private String poolType;
    private Integer defaultGpuCount;
    private Integer defaultGpumemMb;
    private Integer defaultGpucores;
    private Integer defaultCpuCores;
    private Integer defaultMemoryGib;
    private String nodeSelector;
    private String tolerations;
    private String resourceQuotaKey;
    private Integer memoryGb;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
