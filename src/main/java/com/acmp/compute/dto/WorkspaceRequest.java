package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 工作空间创建 = 创建 K8s Namespace + ResourceQuota
 */
@Data
public class WorkspaceRequest {
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String resourcePoolId;
    @NotNull @Min(1)
    private Integer gpuSlots;
    @NotNull @Min(1)
    private Integer cpuCores;
    @NotNull @Min(1)
    private Integer memoryGib;
    private Integer maxPods = 50;
    private String gpuType = "NVIDIA";
    private String jobTypes = "TRAINING,INFERENCE";
}
