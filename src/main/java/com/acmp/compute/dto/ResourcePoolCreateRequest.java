package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.List;

@Data
public class ResourcePoolCreateRequest {
    @NotEmpty private List<String> physicalClusterIds;
    @NotBlank private String name;
    private String description;
    @NotBlank @Pattern(regexp = "^[a-z0-9_-]+$") private String departmentCode;
    @NotBlank private String departmentName;
    @NotNull @Min(1) private Integer gpuSlots;
    @NotNull @Min(1) private Integer cpuCores;
    @NotNull @Min(1) private Integer memoryGib;
    private Integer maxPods = 50;
    private Integer nodeCount = 1;
    private String hardwareType = "NVIDIA-GPU";
    private GpuBrand gpuType = GpuBrand.NVIDIA;
    private String jobTypes = "TRAINING,INFERENCE";
}
