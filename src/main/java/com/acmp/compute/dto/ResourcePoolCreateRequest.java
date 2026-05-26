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
    /** 关联的物理集群 ID 列表（一个逻辑池可跨多个物理集群） */
    @NotEmpty
    private List<String> physicalClusterIds;
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    @Pattern(regexp = "^[a-z0-9_-]+$", message = "departmentCode 只能包含小写字母、数字、下划线和连字符")
    private String departmentCode;
    @NotBlank
    private String departmentName;
    // ── 总配额 ──
    @NotNull @Min(1)
    private Integer gpuSlots;
    @NotNull @Min(1)
    private Integer cpuCores;
    @NotNull @Min(1)
    private Integer memoryGiB;
    private Integer maxPods = 50;
    private Integer nodeCount = 1;
    // ── 划分维度 ──
    /** 硬件类型：A100-80G / V100 / H100 / CPU-ONLY */
    private String hardwareType = "NVIDIA-GPU";
    /** 安全等级：NORMAL / CONFIDENTIAL */
    private String securityLevel = "NORMAL";
    // ── 作业类型 ──
    private String gpuType = "NVIDIA";
    private String jobTypes = "TRAINING,INFERENCE";
}
