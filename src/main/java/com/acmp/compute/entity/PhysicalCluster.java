package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 已接入的 Kubernetes 集群。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalCluster {
    private String id;
    private String name;
    private String description;
    private String kubeconfigBase64Encrypted;
    private String status;
    private String kubernetesVersion;
    private Integer nodeCount;
    private Integer gpuCount;
    private Instant lastSyncAt;
    private String syncMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
