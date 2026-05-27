package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 物理集群实体：代表一个完整的 K8s 集群，通过 kubeconfig 连接。
 * 支持多种 GPU 硬件规格和地域划分。
 *
 * <h2>【HAMi vGPU】说明</h2>
 * hamiEnabled 字段标识该集群是否启用 HAMi vGPU 支持。
 * 若启用，平台会自动扫描节点标签（pool=xxx）发现 vGPU 切分规格，
 * 并创建对应的 ComputeSpec，实现 vGPU 节点路由。
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
    /** JSON: {"pool":"nvidia-gpu-pool"} — 节点标签 */
    private String nodeLabels;
    /** JSON: [{"key":"hami.io/gpu","value":"present","effect":"NoSchedule"}] — 节点污点 */
    private String taints;
    /** 【HAMi vGPU】是否启用 HAMi vGPU 支持 */
    private Boolean hamiEnabled;
    /** 单节点最大 CPU 核数（用于部署预检验） */
    private Integer maxCpuCores;
    /** 单节点最大内存 GiB（用于部署预检验） */
    private Integer maxMemoryGib;
    private Instant createdAt;
    private Instant updatedAt;
}
