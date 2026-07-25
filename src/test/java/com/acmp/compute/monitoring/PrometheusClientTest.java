package com.acmp.compute.monitoring;

import com.acmp.compute.dto.MonitoringPointResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusClientTest {

    private final PrometheusClient client = new PrometheusClient(
            new RestTemplateBuilder(),
            new ObjectMapper(),
            "",
            3,
            10);

    @Test
    void shouldParsePrometheusMatrixPoints() {
        String response = "{"
                + "\"status\":\"success\","
                + "\"data\":{\"resultType\":\"matrix\",\"result\":[{"
                + "\"metric\":{},"
                + "\"values\":[[1785030900,\"34.2\"],[1785030960,\"35.6\"]]"
                + "}]}}";

        List<MonitoringPointResponse> points = client.parseMatrixResponse(response);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).getTimestamp()).isEqualTo(1785030900L);
        assertThat(points.get(1).getValue()).isEqualTo(35.6);
    }

    @Test
    void shouldReturnEmptyPointsWhenPrometheusHasNoResult() {
        String response = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";

        assertThat(client.parseMatrixResponse(response)).isEmpty();
    }

    @Test
    void shouldIgnoreNonNumericPrometheusValues() {
        String response = "{"
                + "\"status\":\"success\","
                + "\"data\":{\"resultType\":\"matrix\",\"result\":[{"
                + "\"metric\":{},"
                + "\"values\":[[1785030900,\"NaN\"],[1785030960,\"12.5\"]]"
                + "}]}}";

        List<MonitoringPointResponse> points = client.parseMatrixResponse(response);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).getValue()).isEqualTo(12.5);
    }
}
