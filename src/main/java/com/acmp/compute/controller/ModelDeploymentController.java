package com.acmp.compute.controller;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.ChatCompletionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.acmp.compute.service.ModelDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 项目推理服务接口。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/deployments")
@RequiredArgsConstructor
public class ModelDeploymentController {

    private final ModelDeploymentService service;

    /**
     * 创建推理服务，并根据 Spec 生成 Kubernetes 或 HAMi 资源请求。
     */
    @PostMapping
    public ResponseEntity<ModelDeploymentResponse> deploy(
            @PathVariable String projectId,
            @Valid @RequestBody ModelDeploymentRequest request) {
        ModelDeploymentResponse response = service.deploy(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 查询项目下的全部推理服务。
     */
    @GetMapping
    public ResponseEntity<List<ModelDeploymentResponse>> list(@PathVariable String projectId) {
        return ResponseEntity.ok(service.listByProject(projectId));
    }

    /**
     * 查询推理服务详情和 Kubernetes Ready 副本数。
     */
    @GetMapping("/{deploymentId}")
    public ResponseEntity<ModelDeploymentResponse> getStatus(
            @PathVariable String projectId,
            @PathVariable String deploymentId) {
        return ResponseEntity.ok(service.getStatus(projectId, deploymentId));
    }

    /**
     * 通过后端安全代理调用 vLLM 的 OpenAI 兼容对话接口。
     */
    @PostMapping("/{deploymentId}/chat/completions")
    public ResponseEntity<JsonNode> chat(
            @PathVariable String projectId,
            @PathVariable String deploymentId,
            @Valid @RequestBody ChatCompletionRequest request) {
        return ResponseEntity.ok(service.chat(projectId, deploymentId, request));
    }

    /**
     * 删除 Kubernetes 推理服务并释放租户 Spec 配额。
     */
    @DeleteMapping("/{deploymentId}")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable String projectId,
            @PathVariable String deploymentId) {
        service.delete(projectId, deploymentId);
        return ResponseEntity.ok(Map.of("message", "已删除部署"));
    }
}
