package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SpecResponse {
    private String id;
    private String name;
    private String displayName;
    private String gpuBrand;
    private Integer memoryGb;
    private String architecture;
    private String description;
    private Instant createdAt;
}
