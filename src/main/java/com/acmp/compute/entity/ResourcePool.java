package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 逻辑资源池：纯 DB 逻辑分组。不直接对应 K8s 资源。
 * 工作空间才是 K8s Namespace 的实际载体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePool {
    private String id;
    private String name;
    private String description;
    private String departmentCode;
    private String departmentName;
    private Integer gpuSlots;
    private Integer cpuCores;
    private Integer memoryGiB;
    private Integer maxPods;
    private Integer nodeCount;
    private Integer allocatedGpuSlots;
    private Integer allocatedCpuCores;
    private Integer allocatedMemoryGib;
    private String hardwareType;
    private String securityLevel;
    private String gpuType;
    private String jobTypes;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
