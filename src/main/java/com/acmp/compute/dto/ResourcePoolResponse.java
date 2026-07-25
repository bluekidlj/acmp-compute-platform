package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResourcePoolResponse {
    private String id;
    private String poolType;
    private String name;
    private String description;
    private Integer gpuCount;
    private String status;

    private List<SpecBrief> specs;

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class SpecBrief {
        private String id;
        private String name;
        private String displayName;
        private String specType;
        private GpuBrand gpuBrand;
    }
}
