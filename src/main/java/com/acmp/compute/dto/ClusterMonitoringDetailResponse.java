package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 集群监控详情响应：顶部摘要和固定监控曲线。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterMonitoringDetailResponse {
    private ClusterMonitoringSummaryResponse summary;
    private List<MonitoringSeriesResponse> series;
}
