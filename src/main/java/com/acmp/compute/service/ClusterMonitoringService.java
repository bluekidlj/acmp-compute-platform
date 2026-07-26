package com.acmp.compute.service;

import com.acmp.compute.dto.ClusterMonitoringDetailResponse;
import com.acmp.compute.dto.ClusterMonitoringSummaryResponse;
import com.acmp.compute.dto.MonitoringPointResponse;
import com.acmp.compute.dto.MonitoringSeriesResponse;
import com.acmp.compute.entity.ClusterNode;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ClusterNodeMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.monitoring.PrometheusClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 集群监控查询服务。
 *
 * <p>MVP 只查询前端已确认的四个集群级指标，不提供通用 PromQL 查询。
 */
@Service
@RequiredArgsConstructor
public class ClusterMonitoringService {

    private static final String CPU_USAGE_PROMQL =
            "100 * (1 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])))";
    private static final String MEMORY_USAGE_PROMQL =
            "100 * (1 - (sum(node_memory_MemAvailable_bytes) / sum(node_memory_MemTotal_bytes)))";
    private static final String DISK_USAGE_PROMQL =
            "100 * (1 - (sum(node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\",mountpoint!~\"/run.*|/var/lib/kubelet/pods/.*|/boot.*\"}) / sum(node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs\",mountpoint!~\"/run.*|/var/lib/kubelet/pods/.*|/boot.*\"})))";
    private static final String NETWORK_RECEIVE_PROMQL =
            "sum(rate(node_network_receive_bytes_total{device!=\"lo\"}[5m])) / 1024 / 1024 * 8";
    private static final String NETWORK_TRANSMIT_PROMQL =
            "sum(rate(node_network_transmit_bytes_total{device!=\"lo\"}[5m])) / 1024 / 1024 * 8";
    private static final String LOAD_1M_PROMQL =
            "avg(node_load1)";
    private static final String GPU_USAGE_PROMQL =
            "avg(DCGM_FI_DEV_GPU_UTIL)";
    private static final String GPU_MEMORY_USED_PROMQL =
            "sum(DCGM_FI_DEV_FB_USED)";

    private final PhysicalClusterMapper physicalClusterMapper;
    private final ClusterNodeMapper clusterNodeMapper;
    private final PrometheusClient prometheusClient;

    public List<ClusterMonitoringSummaryResponse> list() {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofMinutes(5));
        List<ClusterMonitoringSummaryResponse> result = new ArrayList<>();
        for (PhysicalCluster cluster : physicalClusterMapper.findAll()) {
            ClusterMonitoringDetailResponse detail = queryCluster(cluster, start, end, 60);
            result.add(detail.getSummary());
        }
        return result;
    }

    public ClusterMonitoringDetailResponse detail(String clusterId,
                                                  Instant requestedStart,
                                                  Instant requestedEnd,
                                                  Integer requestedStep) {
        PhysicalCluster cluster = physicalClusterMapper.findById(clusterId)
                .orElseThrow(() -> new ResourceNotFoundException("集群不存在: " + clusterId));

        Instant end = requestedEnd == null ? Instant.now() : requestedEnd;
        Instant start = requestedStart == null ? end.minus(Duration.ofHours(1)) : requestedStart;
        int step = requestedStep == null ? 60 : requestedStep;
        validateRange(start, end, step);
        return queryCluster(cluster, start, end, step);
    }

    private ClusterMonitoringDetailResponse queryCluster(PhysicalCluster cluster,
                                                         Instant start,
                                                         Instant end,
                                                         int step) {
        List<MonitoringPointResponse> cpuPoints =
                prometheusClient.queryRange(CPU_USAGE_PROMQL, start, end, step);
        List<MonitoringPointResponse> memoryPoints =
                prometheusClient.queryRange(MEMORY_USAGE_PROMQL, start, end, step);
        List<MonitoringPointResponse> diskPoints =
                prometheusClient.queryRange(DISK_USAGE_PROMQL, start, end, step);
        List<MonitoringPointResponse> networkReceivePoints =
                prometheusClient.queryRange(NETWORK_RECEIVE_PROMQL, start, end, step);
        List<MonitoringPointResponse> networkTransmitPoints =
                prometheusClient.queryRange(NETWORK_TRANSMIT_PROMQL, start, end, step);
        List<MonitoringPointResponse> load1Points =
                prometheusClient.queryRange(LOAD_1M_PROMQL, start, end, step);

        List<MonitoringPointResponse> gpuPoints = Collections.emptyList();
        List<MonitoringPointResponse> gpuMemoryPoints = Collections.emptyList();
        if (cluster.getGpuCount() != null && cluster.getGpuCount() > 0) {
            gpuPoints = prometheusClient.queryRange(GPU_USAGE_PROMQL, start, end, step);
            gpuMemoryPoints = prometheusClient.queryRange(GPU_MEMORY_USED_PROMQL, start, end, step);
        }

        List<MonitoringSeriesResponse> series = new ArrayList<>();
        addSeriesWhenPresent(series, "cpu_usage_percent", "%", cpuPoints);
        addSeriesWhenPresent(series, "memory_usage_percent", "%", memoryPoints);
        addSeriesWhenPresent(series, "disk_usage_percent", "%", diskPoints);
        addSeriesWhenPresent(series, "network_receive_mbps", "Mbps", networkReceivePoints);
        addSeriesWhenPresent(series, "network_transmit_mbps", "Mbps", networkTransmitPoints);
        addSeriesWhenPresent(series, "load_average_1m", "load", load1Points);
        addSeriesWhenPresent(series, "gpu_usage_percent", "%", gpuPoints);
        addSeriesWhenPresent(series, "gpu_memory_used_mib", "MiB", gpuMemoryPoints);

        List<ClusterNode> nodes = clusterNodeMapper.findByClusterId(cluster.getId());
        int readyNodeCount = (int) nodes.stream()
                .filter(node -> "READY".equalsIgnoreCase(node.getStatus()))
                .count();

        Instant lastCollectedAt = latestTimestamp(series);
        ClusterMonitoringSummaryResponse summary = ClusterMonitoringSummaryResponse.builder()
                .clusterId(cluster.getId())
                .name(cluster.getName())
                .status(cluster.getStatus())
                .nodeCount(cluster.getNodeCount())
                .readyNodeCount(readyNodeCount)
                .gpuCount(cluster.getGpuCount())
                .cpuUsagePercent(latestValue(cpuPoints))
                .memoryUsagePercent(latestValue(memoryPoints))
                .diskUsagePercent(latestValue(diskPoints))
                .networkReceiveMbps(latestValue(networkReceivePoints))
                .networkTransmitMbps(latestValue(networkTransmitPoints))
                .loadAverage1m(latestValue(load1Points))
                .gpuUsagePercent(latestValue(gpuPoints))
                .gpuMemoryUsedMib(latestValue(gpuMemoryPoints))
                .lastCollectedAt(lastCollectedAt)
                .build();
        return new ClusterMonitoringDetailResponse(summary, series);
    }

    private void validateRange(Instant start, Instant end, int step) {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start 必须早于 end");
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("监控查询时间范围不能超过 7 天");
        }
        if (step < 15 || step > 3600) {
            throw new IllegalArgumentException("step 必须在 15 到 3600 秒之间");
        }
    }

    private void addSeriesWhenPresent(List<MonitoringSeriesResponse> target,
                                      String metric,
                                      String unit,
                                      List<MonitoringPointResponse> points) {
        if (!points.isEmpty()) {
            target.add(new MonitoringSeriesResponse(metric, unit, points));
        }
    }

    private Double latestValue(List<MonitoringPointResponse> points) {
        return points.isEmpty() ? null : points.get(points.size() - 1).getValue();
    }

    private Instant latestTimestamp(List<MonitoringSeriesResponse> series) {
        long latest = 0L;
        for (MonitoringSeriesResponse item : series) {
            for (MonitoringPointResponse point : item.getPoints()) {
                latest = Math.max(latest, point.getTimestamp());
            }
        }
        return latest == 0L ? null : Instant.ofEpochSecond(latest);
    }
}
