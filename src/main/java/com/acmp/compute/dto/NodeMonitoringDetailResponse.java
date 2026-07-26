package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 节点监控详情。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeMonitoringDetailResponse {
    private NodeMonitoringSummaryResponse summary;
    private List<MonitoringSeriesResponse> series;
    private List<NodeGpuMonitoringResponse> gpus;
}
