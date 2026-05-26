package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResourcePoolResponse {
    private String id;
    private String name;
    private String description;
    private String departmentCode;
    private String departmentName;
    private String namespace;
    // ── 关联的物理集群 ──
    private List<String> physicalClusterIds;
    // ── 总配额 ──
    private Integer gpuSlots;
    private Integer cpuCores;
    private Integer memoryGiB;
    private Integer maxPods;
    private Integer nodeCount;
    // ── 已分配 + 可分配 ──
    private Integer allocatedGpuSlots;
    private Integer allocatedCpuCores;
    private Integer allocatedMemoryGib;
    private Integer availableGpuSlots;
    // ── 划分维度 ──
    private String hardwareType;
    private String securityLevel;
    // ── 作业类型 ──
    private String gpuType;
    private String jobTypes;
    private String volcanoQueueName;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
