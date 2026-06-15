package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 物理集群实体。
 *
 * 1.0 新增：
 * <ul>
 *   <li>hamiSplits - JSON 数组，记录该集群扫描到的 HAMi vGPU 切分规格</li>
 * </ul>
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
    private String gpuTypes;
    private String location;
    /** JSON: {"pool":"nvidia-gpu-pool"} */
    private String nodeLabels;
    /** JSON: [{key,value,effect}] */
    private String taints;
    /** 扫描回写：HAMi 切分规格 */
    private String hamiSplits;
    private Integer maxCpuCores;
    private Integer maxMemoryGib;
    private Instant createdAt;
    private Instant updatedAt;
}
