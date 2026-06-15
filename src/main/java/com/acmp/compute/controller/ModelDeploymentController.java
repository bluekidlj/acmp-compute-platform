package com.acmp.compute.controller;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.service.ModelDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 1.0 模型部署 API：入口在 Project 下。
 *
 * <pre>
 * POST   /api/v1/projects/{projectId}/deployments
 * GET    /api/v1/projects/{projectId}/deployments
 * GET    /api/v1/projects/{projectId}/deployments/{id}
 * DELETE /api/v1/projects/{projectId}/deployments/{id}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/deployments")
@RequiredArgsConstructor
public class ModelDeploymentController {

    private final ModelDeploymentService service;

    @PostMapping
    public ResponseEntity<ModelDeploymentResponse> deploy(@PathVariable String projectId,
                                                           @Valid @RequestBody ModelDeploymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.deploy(projectId, req));
    }

    @GetMapping
    public ResponseEntity<List<ModelDeploymentResponse>> list(@PathVariable String projectId) {
        return ResponseEntity.ok(service.listByProject(projectId));
    }

    @GetMapping("/{deploymentId}")
    public ResponseEntity<ModelDeploymentResponse> getStatus(@PathVariable String projectId,
                                                              @PathVariable String deploymentId) {
        return ResponseEntity.ok(service.getStatus(projectId, deploymentId));
    }

    @DeleteMapping("/{deploymentId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String projectId,
                                                      @PathVariable String deploymentId) {
        service.delete(projectId, deploymentId);
        return ResponseEntity.ok(Map.of("message", "已删除部署"));
    }
}
