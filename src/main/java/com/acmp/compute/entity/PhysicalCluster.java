package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 物理集群实体：代表一个完整的 K8s 集群，通过 kubeconfig 连接。
 * 支持多种 GPU 硬件规格和地域划分。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalCluster {
    private String id;
    private String name;
    private String description;
    /** AES 加密后的 kubeconfig 内容（Base64） */
    private String kubeconfigBase64Encrypted;
    /** active / degraded / offline */
    private String status;
    /** 集群总 GPU 槽数（实时从 K8s 节点汇总） */
    private Integer totalGpuSlots;
    /** 支持的 GPU 硬件类型，逗号分隔，如 NVIDIA,HYGON */
    private String gpuTypes;
    /** 地域/机房，如 beijing, shanghai */
    private String location;
    private Instant createdAt;
    private Instant updatedAt;
}
