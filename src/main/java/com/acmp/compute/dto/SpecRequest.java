package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Min;

@Data
public class SpecRequest {
    @NotBlank
    private String name;
    private String displayName;
    private String gpuBrand = "NVIDIA";
    @Min(1)
    private Integer memoryGb;
    private String architecture;
    private String description;
}
