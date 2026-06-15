package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CapacityResponse {
    private Long gpuSlots;
    private String cpu;
    private String memory;
}
