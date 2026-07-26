package com.acmp.compute.monitoring;

import com.acmp.compute.dto.MonitoringPointResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus HTTP API 最小客户端。
 *
 * <p>这里只开放范围查询，PromQL 由业务服务固定提供，不能接收前端任意表达式。
 */
@Slf4j
@Component
public class PrometheusClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public PrometheusClient(RestTemplateBuilder builder,
                            ObjectMapper objectMapper,
                            @Value("${acmp.monitoring.prometheus-url:}") String baseUrl,
                            @Value("${acmp.monitoring.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                            @Value("${acmp.monitoring.read-timeout-seconds:10}") long readTimeoutSeconds) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    /**
     * 查询单条聚合后的 Prometheus 时间序列。
     *
     * @return Prometheus 未配置、不可达或无数据时返回空列表
     */
    public List<MonitoringPointResponse> queryRange(String promql,
                                                    Instant start,
                                                    Instant end,
                                                    int stepSeconds) {
        if (baseUrl.isBlank()) {
            return Collections.emptyList();
        }

        URI uri = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/v1/query_range")
                .queryParam("query", promql)
                .queryParam("start", start.getEpochSecond())
                .queryParam("end", end.getEpochSecond())
                .queryParam("step", stepSeconds)
                .build()
                .encode()
                .toUri();

        try {
            String responseBody = restTemplate.getForObject(uri, String.class);
            return parseMatrixResponse(responseBody);
        } catch (RestClientException | IllegalArgumentException exception) {
            // 监控不可用不能影响集群资产接口，返回空序列并保留可排查日志。
            log.warn("Prometheus 查询失败: url={}, error={}", baseUrl, exception.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询多条 Prometheus 时间序列，通常用于按 GPU 分组的场景。
     */
    public List<MonitoringSeriesResult> queryRangeSeries(String promql,
                                                         Instant start,
                                                         Instant end,
                                                         int stepSeconds) {
        if (baseUrl.isBlank()) {
            return Collections.emptyList();
        }

        URI uri = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/v1/query_range")
                .queryParam("query", promql)
                .queryParam("start", start.getEpochSecond())
                .queryParam("end", end.getEpochSecond())
                .queryParam("step", stepSeconds)
                .build()
                .encode()
                .toUri();

        try {
            String responseBody = restTemplate.getForObject(uri, String.class);
            return parseMatrixSeriesResponse(responseBody);
        } catch (RestClientException | IllegalArgumentException exception) {
            log.warn("Prometheus 查询失败: url={}, error={}", baseUrl, exception.getMessage());
            return Collections.emptyList();
        }
    }

    List<MonitoringPointResponse> parseMatrixResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!"success".equals(root.path("status").asText())) {
                return Collections.emptyList();
            }
            JsonNode results = root.path("data").path("result");
            if (!results.isArray() || results.isEmpty()) {
                return Collections.emptyList();
            }

            // 固定 PromQL 已经聚合为单序列，因此只读取第一组 values。
            JsonNode values = results.get(0).path("values");
            List<MonitoringPointResponse> points = new ArrayList<>();
            if (!values.isArray()) {
                return points;
            }
            for (JsonNode valuePair : values) {
                if (!valuePair.isArray() || valuePair.size() < 2) {
                    continue;
                }
                double value = parseFiniteDouble(valuePair.get(1).asText());
                if (Double.isFinite(value)) {
                    points.add(new MonitoringPointResponse(
                            valuePair.get(0).asLong(),
                            value));
                }
            }
            return points;
        } catch (Exception exception) {
            log.warn("Prometheus 响应解析失败: {}", exception.getMessage());
            return Collections.emptyList();
        }
    }

    List<MonitoringSeriesResult> parseMatrixSeriesResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!"success".equals(root.path("status").asText())) {
                return Collections.emptyList();
            }
            JsonNode results = root.path("data").path("result");
            if (!results.isArray() || results.isEmpty()) {
                return Collections.emptyList();
            }

            List<MonitoringSeriesResult> response = new ArrayList<>();
            for (JsonNode result : results) {
                JsonNode values = result.path("values");
                List<MonitoringPointResponse> points = new ArrayList<>();
                if (values.isArray()) {
                    for (JsonNode valuePair : values) {
                        if (!valuePair.isArray() || valuePair.size() < 2) {
                            continue;
                        }
                        double value = parseFiniteDouble(valuePair.get(1).asText());
                        if (Double.isFinite(value)) {
                            points.add(new MonitoringPointResponse(
                                    valuePair.get(0).asLong(),
                                    value));
                        }
                    }
                }

                if (!points.isEmpty()) {
                    response.add(new MonitoringSeriesResult(
                            parseMetricLabels(result.path("metric")),
                            points));
                }
            }
            return response;
        } catch (Exception exception) {
            log.warn("Prometheus 响应解析失败: {}", exception.getMessage());
            return Collections.emptyList();
        }
    }

    private double parseFiniteDouble(String rawValue) {
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class MonitoringSeriesResult {
        private Map<String, String> metric;
        private List<MonitoringPointResponse> points;
    }

    private Map<String, String> parseMetricLabels(JsonNode metricNode) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (metricNode != null && metricNode.isObject()) {
            metricNode.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
        }
        return labels;
    }
}
