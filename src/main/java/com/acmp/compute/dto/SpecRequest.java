package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 创建/更新规格请求。
 */
@Data
public class SpecRequest {
    @NotBlank
    private String name;
    private String displayName;
    private GpuBrand gpuBrand = GpuBrand.NVIDIA;

    @NotBlank
    @Pattern(regexp = "EXCLUSIVE|SHARED")
    private String specType;
    @NotBlank
    private String resourcePoolId;
    private String gpuModel;
    private Integer gpuCount = 1;
    private Integer cpuCores = 4;
    private Integer memoryGib = 16;
    private String gpuShare;
    private String description;
}
