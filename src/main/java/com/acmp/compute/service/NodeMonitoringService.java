package com.acmp.compute.service;

import com.acmp.compute.dto.MonitoringPointResponse;
import com.acmp.compute.dto.MonitoringSeriesResponse;
import com.acmp.compute.dto.NodeGpuMonitoringResponse;
import com.acmp.compute.dto.NodeMonitoringDetailResponse;
import com.acmp.compute.dto.NodeMonitoringSummaryResponse;
import com.acmp.compute.entity.ClusterNode;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ClusterNodeMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.monitoring.PrometheusClient;
import com.acmp.compute.monitoring.PrometheusClient.MonitoringSeriesResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class NodeMonitoringService {

    private static final String CPU_USAGE_PROMQL =
            "100 * (1 - avg(rate(node_cpu_seconds_total{mode=\"idle\",%s}[5m])))";
    private static final String MEMORY_USAGE_PROMQL =
            "100 * (1 - (sum(node_memory_MemAvailable_bytes{%s}) / sum(node_memory_MemTotal_bytes{%s})))";
    private static final String DISK_USAGE_PROMQL =
            "100 * (1 - (sum(node_filesystem_avail_bytes{%s,fstype!~\"tmpfs|overlay|squashfs\",mountpoint!~\"/run.*|/var/lib/kubelet/pods/.*|/boot.*\"}) / sum(node_filesystem_size_bytes{%s,fstype!~\"tmpfs|overlay|squashfs\",mountpoint!~\"/run.*|/var/lib/kubelet/pods/.*|/boot.*\"})))";
    private static final String NETWORK_RECEIVE_PROMQL =
            "sum(rate(node_network_receive_bytes_total{%s,device!=\"lo\"}[5m])) / 1024 / 1024 * 8";
    private static final String NETWORK_TRANSMIT_PROMQL =
            "sum(rate(node_network_transmit_bytes_total{%s,device!=\"lo\"}[5m])) / 1024 / 1024 * 8";
    private static final String LOAD_1M_PROMQL =
            "avg(node_load1{%s})";
    private static final String GPU_USAGE_PROMQL =
            "avg(DCGM_FI_DEV_GPU_UTIL{%s})";
    private static final String GPU_MEMORY_USED_PROMQL =
            "sum(DCGM_FI_DEV_FB_USED{%s})";
    private static final String GPU_BY_CARD_USAGE_PROMQL =
            "avg by (gpu) (DCGM_FI_DEV_GPU_UTIL{%s})";
    private static final String GPU_BY_CARD_MEMORY_PROMQL =
            "avg by (gpu) (DCGM_FI_DEV_FB_USED{%s})";

    private final PhysicalClusterMapper physicalClusterMapper;
    private final ClusterNodeMapper nodeMapper;
    private final PrometheusClient prometheusClient;

    public NodeMonitoringDetailResponse detail(String clusterId,
                                               String nodeId,
                                               Instant requestedStart,
                                               Instant requestedEnd,
                                               Integer requestedStep) {
        PhysicalCluster cluster = physicalClusterMapper.findById(clusterId)
                .orElseThrow(() -> new ResourceNotFoundException("集群不存在: " + clusterId));
        ClusterNode node = nodeMapper.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("节点不存在: " + nodeId));
        if (!clusterId.equals(node.getClusterId())) {
            throw new ResourceNotFoundException("节点不属于该集群");
        }
        if (node.getInternalIp() == null || node.getInternalIp().isBlank()) {
            return NodeMonitoringDetailResponse.builder()
                    .summary(summaryOf(node, null, null, null, null, null, null, null, null))
                    .series(Collections.emptyList())
                    .gpus(Collections.emptyList())
                    .build();
        }

        Instant end = requestedEnd == null ? Instant.now() : requestedEnd;
        Instant start = requestedStart == null ? end.minus(Duration.ofHours(1)) : requestedStart;
        int step = requestedStep == null ? 60 : requestedStep;
        List<String> nodeMatchers = nodeMatchers(node);

        List<MonitoringPointResponse> cpuPoints = queryFirst(nodeMatchers, matcher -> String.format(CPU_USAGE_PROMQL, matcher), start, end, step);
        List<MonitoringPointResponse> memoryPoints = queryFirst(nodeMatchers, matcher -> String.format(MEMORY_USAGE_PROMQL, matcher, matcher), start, end, step);
        List<MonitoringPointResponse> diskPoints = queryFirst(nodeMatchers, matcher -> String.format(DISK_USAGE_PROMQL, matcher, matcher), start, end, step);
        List<MonitoringPointResponse> receivePoints = queryFirst(nodeMatchers, matcher -> String.format(NETWORK_RECEIVE_PROMQL, matcher), start, end, step);
        List<MonitoringPointResponse> transmitPoints = queryFirst(nodeMatchers, matcher -> String.format(NETWORK_TRANSMIT_PROMQL, matcher), start, end, step);
        List<MonitoringPointResponse> loadPoints = queryFirst(nodeMatchers, matcher -> String.format(LOAD_1M_PROMQL, matcher), start, end, step);

        List<MonitoringPointResponse> gpuPoints = node.getGpuCount() != null && node.getGpuCount() > 0
                ? queryFirst(nodeMatchers, matcher -> String.format(GPU_USAGE_PROMQL, matcher), start, end, step)
                : Collections.emptyList();
        List<MonitoringPointResponse> gpuMemoryPoints = node.getGpuCount() != null && node.getGpuCount() > 0
                ? queryFirst(nodeMatchers, matcher -> String.format(GPU_MEMORY_USED_PROMQL, matcher), start, end, step)
                : Collections.emptyList();

        List<MonitoringSeriesResponse> series = new ArrayList<>();
        addSeriesWhenPresent(series, "cpu_usage_percent", "%", cpuPoints);
        addSeriesWhenPresent(series, "memory_usage_percent", "%", memoryPoints);
        addSeriesWhenPresent(series, "disk_usage_percent", "%", diskPoints);
        addSeriesWhenPresent(series, "network_receive_mbps", "Mbps", receivePoints);
        addSeriesWhenPresent(series, "network_transmit_mbps", "Mbps", transmitPoints);
        addSeriesWhenPresent(series, "load_average_1m", "load", loadPoints);
        addSeriesWhenPresent(series, "gpu_usage_percent", "%", gpuPoints);
        addSeriesWhenPresent(series, "gpu_memory_used_mib", "MiB", gpuMemoryPoints);

        List<NodeGpuMonitoringResponse> gpus = new ArrayList<>();
        if (node.getGpuCount() != null && node.getGpuCount() > 0) {
            List<MonitoringSeriesResult> gpuUtilSeries = querySeriesFirst(
                    nodeMatchers, matcher -> String.format(GPU_BY_CARD_USAGE_PROMQL, matcher), start, end, step);
            List<MonitoringSeriesResult> gpuMemorySeries = querySeriesFirst(
                    nodeMatchers, matcher -> String.format(GPU_BY_CARD_MEMORY_PROMQL, matcher), start, end, step);
            int maxCount = Math.max(gpuUtilSeries.size(), gpuMemorySeries.size());
            for (int index = 0; index < maxCount; index++) {
                MonitoringSeriesResult util = index < gpuUtilSeries.size() ? gpuUtilSeries.get(index) : null;
                MonitoringSeriesResult memory = index < gpuMemorySeries.size() ? gpuMemorySeries.get(index) : null;
                String gpuLabel = labelOf(util, memory, index);
                gpus.add(NodeGpuMonitoringResponse.builder()
                        .gpuIndex(index)
                        .gpuLabel(gpuLabel)
                        .gpuUsagePercent(latestValue(util))
                        .gpuMemoryUsedMib(latestValue(memory))
                        .build());
            }
        }

        NodeMonitoringSummaryResponse summary = summaryOf(
                node,
                latestValue(cpuPoints),
                latestValue(memoryPoints),
                latestValue(diskPoints),
                latestValue(receivePoints),
                latestValue(transmitPoints),
                latestValue(loadPoints),
                latestValue(gpuPoints),
                latestValue(gpuMemoryPoints));
        return NodeMonitoringDetailResponse.builder()
                .summary(summary)
                .series(series)
                .gpus(gpus)
                .build();
    }

    private NodeMonitoringSummaryResponse summaryOf(ClusterNode node,
                                                    Double cpu,
                                                    Double memory,
                                                    Double disk,
                                                    Double receive,
                                                    Double transmit,
                                                    Double load,
                                                    Double gpu,
                                                    Double gpuMemory) {
        return NodeMonitoringSummaryResponse.builder()
                .nodeId(node.getId())
                .clusterId(node.getClusterId())
                .nodeName(node.getName())
                .internalIp(node.getInternalIp())
                .status(node.getStatus())
                .cpuCores(node.getCpuCores())
                .memoryBytes(node.getMemoryBytes())
                .gpuCount(node.getGpuCount())
                .cpuUsagePercent(cpu)
                .memoryUsagePercent(memory)
                .diskUsagePercent(disk)
                .networkReceiveMbps(receive)
                .networkTransmitMbps(transmit)
                .loadAverage1m(load)
                .gpuUsagePercent(gpu)
                .gpuMemoryUsedMib(gpuMemory)
                .lastCollectedAt(Instant.now())
                .build();
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

    private Double latestValue(MonitoringSeriesResult result) {
        return result == null || result.getPoints().isEmpty() ? null : result.getPoints().get(result.getPoints().size() - 1).getValue();
    }

    private List<MonitoringPointResponse> queryFirst(List<String> matchers,
                                                     Function<String, String> promqlBuilder,
                                                     Instant start,
                                                     Instant end,
                                                     int step) {
        for (String matcher : matchers) {
            List<MonitoringPointResponse> points = prometheusClient.queryRange(promqlBuilder.apply(matcher), start, end, step);
            if (!points.isEmpty()) {
                return points;
            }
        }
        return Collections.emptyList();
    }

    private List<MonitoringSeriesResult> querySeriesFirst(List<String> matchers,
                                                          Function<String, String> promqlBuilder,
                                                          Instant start,
                                                          Instant end,
                                                          int step) {
        for (String matcher : matchers) {
            List<MonitoringSeriesResult> series = prometheusClient.queryRangeSeries(promqlBuilder.apply(matcher), start, end, step);
            if (!series.isEmpty()) {
                return series;
            }
        }
        return Collections.emptyList();
    }

    private List<String> nodeMatchers(ClusterNode node) {
        List<String> matchers = new ArrayList<>();
        if (node.getInternalIp() != null && !node.getInternalIp().isBlank()) {
            matchers.add("instance=~\"^" + escapeRegex(node.getInternalIp()) + "(:.*)?$\"");
        }
        if (node.getName() != null && !node.getName().isBlank()) {
            matchers.add("instance=~\"^" + escapeRegex(node.getName()) + "(:.*)?$\"");
            matchers.add("node=\"" + escapeLabelValue(node.getName()) + "\"");
            matchers.add("kubernetes_node=\"" + escapeLabelValue(node.getName()) + "\"");
        }
        return matchers;
    }

    private String escapeRegex(String value) {
        // matcher 最终位于 PromQL 的双引号字符串中，正则反斜杠必须再转义一次。
        // 例如 IP 192.168.1.10 必须生成 192\\.168\\.1\\.10，而不是非法的 192\.168\.1\.10。
        return value.replace("\\", "\\\\").replace(".", "\\\\.");
    }

    private String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String labelOf(MonitoringSeriesResult util, MonitoringSeriesResult memory, int index) {
        if (util != null && util.getMetric() != null && util.getMetric().containsKey("gpu")) {
            return "GPU" + util.getMetric().get("gpu");
        }
        if (memory != null && memory.getMetric() != null && memory.getMetric().containsKey("gpu")) {
            return "GPU" + memory.getMetric().get("gpu");
        }
        return "GPU" + index;
    }
}
