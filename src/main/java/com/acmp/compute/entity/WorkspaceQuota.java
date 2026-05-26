package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工作空间配额：从所属逻辑池分配的资源上限，含实时用量追踪。
 * 任务提交时校验剩余配额（max - used），运行时扣减/恢复。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceQuota {
    private String id;
    private String workspaceId;
    /** GPU 卡数上限 */
    private Integer maxGpuSlots;
    /** CPU 核心数上限 */
    private Integer maxCpuCores;
    /** 内存上限 (GiB) */
    private Integer maxMemoryGib;
    /** Pod 数量上限 */
    private Integer maxPods;
    /** 累计使用时长上限（小时） */
    private Integer maxHours;
    /** 当前已使用 GPU 卡数 */
    private Integer usedGpuSlots;
    /** 当前已使用 CPU 核心数 */
    private Integer usedCpuCores;
    /** 当前已使用内存 (GiB) */
    private Integer usedMemoryGib;
    private Instant createdAt;
    private Instant updatedAt;
}
