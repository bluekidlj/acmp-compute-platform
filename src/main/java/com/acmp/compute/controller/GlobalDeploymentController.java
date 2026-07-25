package com.acmp.compute.controller;

import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.service.ModelDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局推理服务查询接口。
 */
@RestController
@RequestMapping("/api/v1/deployments")
@RequiredArgsConstructor
public class GlobalDeploymentController {

    private final ModelDeploymentService service;

    /**
     * 查询当前用户有权访问的推理服务，可按租户、项目和状态筛选。
     */
    @GetMapping
    public ResponseEntity<List<ModelDeploymentResponse>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status) {
        List<ModelDeploymentResponse> result = new ArrayList<>();
        for (ModelDeploymentResponse deployment : service.listAccessible()) {
            if (tenantId != null && !tenantId.equals(deployment.getTenantId())) {
                continue;
            }
            if (projectId != null && !projectId.equals(deployment.getProjectId())) {
                continue;
            }
            if (status != null && !status.equalsIgnoreCase(deployment.getStatus())) {
                continue;
            }
            result.add(deployment);
        }
        return ResponseEntity.ok(result);
    }
}
