package com.acmp.compute.controller;

import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.VllmDeployRequest;
import com.acmp.compute.service.ModelDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * vLLM 模型服务部署（规范版本）。
 * 
 * 新版 API：POST /api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments
 * 旧版 API：POST /api/v1/workspaces/{workspaceId}/model-deployments（向后兼容）
 */
@RestController
@RequiredArgsConstructor
public class ModelDeploymentController {

    private final ModelDeploymentService modelDeploymentService;

    /**
     * 新版 API：规范版本（使用规格名称部署）
     * 
     * POST /api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments
     */
    @PostMapping("/api/v1/resource-pools/{poolId}/workspaces/{workspaceId}/model-deployments")
    public ResponseEntity<ModelDeploymentResponse> deployBySpec(
            @PathVariable String poolId,
            @PathVariable String workspaceId,
            @Valid @RequestBody VllmDeployRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(modelDeploymentService.deployBySpec(poolId, workspaceId, request));
    }

    /**
     * 旧版 API：向后兼容
     * 
     * @deprecated 使用新版 API
     */
    @Deprecated
    @PostMapping("/api/v1/workspaces/{workspaceId}/model-deployments")
    public ResponseEntity<ModelDeploymentResponse> deploy(
            @PathVariable String workspaceId,
            @Valid @RequestBody VllmDeployRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(modelDeploymentService.deploy(workspaceId, request));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/model-deployments")
    public ResponseEntity<List<ModelDeploymentResponse>> list(@PathVariable String workspaceId) {
        return ResponseEntity.ok(modelDeploymentService.listByWorkspace(workspaceId));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/model-deployments/{deploymentId}")
    public ResponseEntity<ModelDeploymentResponse> getStatus(
            @PathVariable String workspaceId, @PathVariable String deploymentId) {
        return ResponseEntity.ok(modelDeploymentService.getStatus(workspaceId, deploymentId));
    }

    @DeleteMapping("/api/v1/workspaces/{workspaceId}/model-deployments/{deploymentId}")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable String workspaceId, @PathVariable String deploymentId) {
        modelDeploymentService.delete(workspaceId, deploymentId);
        return ResponseEntity.ok(Map.of("message", "已删除部署"));
    }
}
