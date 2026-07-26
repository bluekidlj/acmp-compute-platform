package com.acmp.compute.controller;

import com.acmp.compute.dto.NodeMonitoringDetailResponse;
import com.acmp.compute.service.NodeMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/monitoring/clusters/{clusterId}/nodes")
@RequiredArgsConstructor
public class NodeMonitoringController {

    private final NodeMonitoringService nodeMonitoringService;

    @GetMapping("/{nodeId}")
    public ResponseEntity<NodeMonitoringDetailResponse> detail(@PathVariable String clusterId,
                                                               @PathVariable String nodeId,
                                                               @RequestParam(required = false) Instant start,
                                                               @RequestParam(required = false) Instant end,
                                                               @RequestParam(required = false) Integer step) {
        return ResponseEntity.ok(nodeMonitoringService.detail(clusterId, nodeId, start, end, step));
    }
}
