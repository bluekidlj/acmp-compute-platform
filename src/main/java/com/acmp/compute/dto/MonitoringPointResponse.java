package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端监控曲线中的一个采样点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringPointResponse {
    /**
     * Unix 时间戳，单位为秒。
     */
    private long timestamp;

    /**
     * Prometheus 返回的数值。
     */
    private double value;
}
