package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class SpecResponse {
    private String id, name, displayName;
    private GpuBrand gpuBrand;
    private Integer memoryGb;
    private String description;
    private Instant createdAt;
}
