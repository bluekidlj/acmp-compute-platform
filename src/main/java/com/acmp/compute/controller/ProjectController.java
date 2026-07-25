package com.acmp.compute.controller;

import com.acmp.compute.dto.ProjectRequest;
import com.acmp.compute.dto.ProjectResponse;
import com.acmp.compute.dto.TenantSpecQuotaResponse;
import com.acmp.compute.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 租户项目、项目成员和项目可用 Spec 接口。
 */
@RestController
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 在指定租户下创建项目。
     */
    @PostMapping("/api/v1/tenants/{tenantId}/projects")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ProjectResponse> create(
            @PathVariable String tenantId,
            @Valid @RequestBody ProjectRequest request) {
        ProjectResponse response = projectService.create(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 修改项目名称和描述。
     */
    @PutMapping("/api/v1/projects/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable String id,
            @Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.ok(projectService.update(id, request));
    }

    /**
     * 删除项目。
     */
    @DeleteMapping("/api/v1/projects/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        projectService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /**
     * 查询项目详情。
     */
    @GetMapping("/api/v1/projects/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(projectService.getById(id));
    }

    /**
     * 查询指定租户的项目。
     */
    @GetMapping("/api/v1/tenants/{tenantId}/projects")
    public ResponseEntity<List<ProjectResponse>> listByTenant(@PathVariable String tenantId) {
        return ResponseEntity.ok(projectService.listByTenant(tenantId));
    }

    /**
     * 查询项目从所属租户继承的可用 Spec。
     */
    @GetMapping("/api/v1/projects/{id}/available-specs")
    public ResponseEntity<List<TenantSpecQuotaResponse>> availableSpecs(@PathVariable String id) {
        return ResponseEntity.ok(projectService.availableSpecs(id));
    }

    /**
     * 添加项目成员。
     */
    @PostMapping("/api/v1/projects/{id}/members")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> addMember(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        projectService.addMember(id, body.get("userId"));
        return ResponseEntity.ok(Map.of("message", "已添加"));
    }

    /**
     * 移除项目成员。
     */
    @DeleteMapping("/api/v1/projects/{id}/members/{userId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> removeMember(
            @PathVariable String id,
            @PathVariable String userId) {
        projectService.removeMember(id, userId);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }

    /**
     * 查询项目成员 ID。
     */
    @GetMapping("/api/v1/projects/{id}/members")
    public ResponseEntity<List<String>> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(projectService.listMembers(id));
    }
}
