package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 【HAMi vGPU】vGPU 单元明细。
 *
 * 一个 HamiGpuConfig 对应多个 vGPU 单元。
 * 例如：A100-80GB 切成 6 个 vGPU 单元：v100-7b、v100-14b、v100-28b 等。
 *
 * HamiVgpuUnit 的 nodeSelectorValue 会被拼接到 PhysicalCluster.nodeLabels 中，
 * 供 PoolMetadataService 进行标签匹配。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HamiVgpuUnit {
    private String id;
    /** 所属 GPU 配置 ID */
    private String hamiGpuConfigId;
    /** vGPU 索引 0,1,2... */
    private Integer vgpuIndex;
    /** vGPU 名称，如 "v100-7b" */
    private String vgpuName;
    /** vGPU 显存 MB，如 14000 */
    private Integer vgpuMemMb;
    /** vGPU 算力占比 0-100，如 16 */
    private Integer vgpuCores;
    /** 节点标签 value，如 "v100-7b" */
    private String nodeSelectorValue;
    /** 容忍配置 JSON */
    private String tolerations;
    /** 该 vGPU 单元在集群中的可用数量（管理员手动同步或 K8s allocatable 查询） */
    private Integer availableCount;
    private Instant createdAt;
    private Instant updatedAt;
}