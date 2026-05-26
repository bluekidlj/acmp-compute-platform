package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工作空间 = K8s Namespace（100% 对应），用户唯一可见的资源边界。
 * 资源数量按规格管理（见 workspace_pool_spec_quota），此处不再保留 gpu/cpu/mem 聚合字段。
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
    // ── Pod 数量上限（与规格无关的全局约束）──
    private Integer maxPods;
    private Integer nodeCount;
    // ──
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
