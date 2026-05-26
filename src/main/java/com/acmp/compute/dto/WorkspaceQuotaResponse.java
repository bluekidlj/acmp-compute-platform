package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 工作空间配额响应（含已用量 + 剩余可用量）。
 */
@Data
@Builder
public class WorkspaceQuotaResponse {
    private String id;
    private String workspaceId;
    /** 上限 */
    private Integer maxGpuSlots;
    private Integer maxCpuCores;
    private Integer maxMemoryGib;
    private Integer maxPods;
    private Integer maxHours;
    /** 当前已使用 */
    private Integer usedGpuSlots;
    private Integer usedCpuCores;
    private Integer usedMemoryGib;
    /** 剩余可用（max - used） */
    private Integer availableGpuSlots;
    private Integer availableCpuCores;
    private Integer availableMemoryGib;
}
