package com.acmp.compute.controller;

import com.acmp.compute.dto.ClusterMonitoringDetailResponse;
import com.acmp.compute.dto.ClusterMonitoringSummaryResponse;
import com.acmp.compute.service.ClusterMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 集群监控前端接口。
 */
@RestController
@RequestMapping("/api/v1/monitoring/clusters")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class ClusterMonitoringController {

    private final ClusterMonitoringService clusterMonitoringService;

    /**
     * 查询集群资产和最近一次 Prometheus 指标。
     */
    @GetMapping
    public ResponseEntity<List<ClusterMonitoringSummaryResponse>> list() {
        return ResponseEntity.ok(clusterMonitoringService.list());
    }

    /**
     * 查询指定时间范围的集群监控曲线。
     *
     * @param clusterId 平台集群 ID
     * @param start ISO-8601 开始时间；不传时默认为最近一小时
     * @param end ISO-8601 结束时间；不传时默认为当前时间
     * @param step Prometheus 查询步长，单位秒；默认 60 秒
     */
    @GetMapping("/{clusterId}")
    public ResponseEntity<ClusterMonitoringDetailResponse> detail(
            @PathVariable String clusterId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @RequestParam(required = false) Integer step) {
        return ResponseEntity.ok(clusterMonitoringService.detail(clusterId, start, end, step));
    }
}
