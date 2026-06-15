package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

/**
 * 创建/更新规格请求。
 * specType 必填，决定 poolType 派生（PHYSICAL→EXCLUSIVE, VIRTUAL→SHARED, OVERSELL→OVERSELL）。
 */
@Data
public class SpecRequest {
    @NotBlank
    private String name;
    private String displayName;
    private GpuBrand gpuBrand = GpuBrand.NVIDIA;

    @NotBlank
    @Pattern(regexp = "PHYSICAL|VIRTUAL|OVERSELL")
    private String specType;

    @NotNull
    private Integer defaultGpuCount = 1;
    private Integer defaultGpumemMb;
    private Integer defaultGpucores;
    @NotNull
    private Integer defaultCpuCores = 4;
    @NotNull
    private Integer defaultMemoryGib = 16;
    private String nodeSelector;
    private String tolerations;
    private String resourceQuotaKey;
    private Integer memoryGb;
    private String description;
}
