package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 工作空间响应（含所属逻辑池信息 + 配额 + 用量）。
 */
@Data
@Builder
public class WorkspaceResponse {
    private String id;
    private String name;
    private String description;
    private String resourcePoolId;
    private String resourcePoolName;
    // ── K8s 资源 ──
    private String namespace;
    private String volcanoQueueName;
    private String primaryClusterId;
    // ── 配额 ──
    private Integer gpuSlots;
    private Integer cpuCores;
    private Integer memoryGib;
    private Integer maxPods;
    private String hardwareType;
    private String gpuType;
    private String jobTypes;
    // ──
    private String createdBy;
    private String status;
    private WorkspaceQuotaResponse quota;
    private Instant createdAt;
    private Instant updatedAt;
}
