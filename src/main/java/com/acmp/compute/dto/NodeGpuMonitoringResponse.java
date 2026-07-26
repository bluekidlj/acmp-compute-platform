package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单张 GPU 的当前监控值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeGpuMonitoringResponse {
    private Integer gpuIndex;
    private String gpuLabel;
    private Double gpuUsagePercent;
    private Double gpuMemoryUsedMib;
}
