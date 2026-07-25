package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 前端可直接绘制的一条监控时间序列。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringSeriesResponse {
    /**
     * 平台固定指标名，不直接暴露 PromQL。
     */
    private String metric;

    /**
     * 数值单位，例如 % 或 MiB。
     */
    private String unit;

    /**
     * 按时间升序排列的采样点。
     */
    private List<MonitoringPointResponse> points;
}
