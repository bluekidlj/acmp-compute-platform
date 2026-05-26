package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 逻辑资源池：资源的初次划分（按硬件类型/性能/安全/地域）。
 * 可跨多个物理集群，总配额由平台管理员设置。
 * 配额的一部分可分配给下属工作空间。
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
    /** K8s Namespace 名称 */
    private String namespace;
    /** ServiceAccount 名称 */
    private String serviceAccountName;
    // ── 总配额（平台管理员设置）──
    private Integer gpuSlots;
    private Integer cpuCores;
    private Integer memoryGiB;
    private Integer maxPods;
    private Integer nodeCount;
    // ── 已分配给工作空间的累计值 ──
    private Integer allocatedGpuSlots;
    private Integer allocatedCpuCores;
    private Integer allocatedMemoryGib;
    // ── 划分维度 ──
    /** 硬件类型：A100-80G / V100 / H100 / CPU-ONLY */
    private String hardwareType;
    /** 安全等级：NORMAL / CONFIDENTIAL */
    private String securityLevel;
    // ── 作业控制 ──
    /** GPU 品牌：NVIDIA / HYGON */
    private String gpuType;
    /** 作业类型：TRAINING,INFERENCE */
    private String jobTypes;
    private String volcanoQueueName;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
