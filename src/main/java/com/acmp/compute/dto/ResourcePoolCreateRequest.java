package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.List;

/**
 * 创建逻辑资源池请求。
 * 逻辑池可跨多个物理集群，平台的首次资源划分（按硬件/性能/安全/地域）。
 */
@Data
public class ResourcePoolCreateRequest {
    @NotEmpty
    private List<String> physicalClusterIds;
    @NotBlank
    private String name;
    private String description;
    @NotBlank @Pattern(regexp = "^[a-z0-9_-]+$")
    private String departmentCode;
    @NotBlank
    private String departmentName;
    @NotNull @Min(1) private Integer gpuSlots;
    @NotNull @Min(1) private Integer cpuCores;
    @NotNull @Min(1) private Integer memoryGib;
    private Integer maxPods = 50;
    private Integer nodeCount = 1;
    private String hardwareType = "NVIDIA-GPU";
    private String securityLevel = "NORMAL";
    private String gpuType = "NVIDIA";
    private String jobTypes = "TRAINING,INFERENCE";
}
