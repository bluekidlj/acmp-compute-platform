package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工作空间 = K8s Namespace（100% 对应），用户唯一可见的资源边界。
 * 所有任务、数据、权限都在这一个 Namespace 内。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    private String id;
    private String resourcePoolId;
    private String name;
    private String description;
    // ── K8s 资源 ──
    private String namespace;
    private String serviceAccountName;
    private String volcanoQueueName;
    private String primaryClusterId;
    // ── 配额（DB 备份 K8s ResourceQuota）──
    private Integer gpuSlots;
    private Integer cpuCores;
    private Integer memoryGib;
    private Integer maxPods;
    private Integer nodeCount;
    // ── 维度 ──
    private String hardwareType;
    private String securityLevel;
    private String gpuType;
    private String jobTypes;
    // ──
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
