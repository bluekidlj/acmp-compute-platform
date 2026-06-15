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
    private String gpuTypes;
    private String location;
    private String hamiSplits;
    private Integer maxCpuCores;
    private Integer maxMemoryGib;
    private Instant createdAt;
    private Instant updatedAt;
}
