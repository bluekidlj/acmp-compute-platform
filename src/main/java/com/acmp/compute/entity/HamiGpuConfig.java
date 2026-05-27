package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 【HAMi vGPU】GPU 切分主配置。
 *
 * 每行代表一个物理集群中一种 GPU 型号的切分配置。
 * 例如：一个 NVIDIA A100-80GB 物理卡，切成 6 个 vGPU 单元。
 *
 * 关联关系：
 *  PhysicalCluster (1) → (N) HamiGpuConfig
 *  HamiGpuConfig (1) → (N) HamiVgpuUnit
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HamiGpuConfig {
    private String id;
    /** 所属物理集群 ID */
    private String physicalClusterId;
    /** GPU 型号，如 "A100-80GB-SXM" */
    private String gpuType;
    /** 整卡显存 MB，如 81920 */
    private Integer gpuMemMb;
    /** 整卡算力占比 0-100，如 100 */
    private Integer gpuCores;
    /** 从该卡切出的 vGPU 总数，如 6 */
    private Integer totalVgpuCount;
    /** 节点标签 key，如 "pool" */
    private String nodeSelectorKey;
    /** 节点标签前缀，如 "v100-" */
    private String nodeSelectorPrefix;
    /** active / inactive */
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}