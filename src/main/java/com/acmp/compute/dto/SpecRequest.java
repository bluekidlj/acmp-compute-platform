package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Min;

@Data
public class SpecRequest {
    @NotBlank private String name;
    private String displayName;
    private GpuBrand gpuBrand = GpuBrand.NVIDIA;
    @Min(1) private Integer memoryGb;
    private String description;
}
