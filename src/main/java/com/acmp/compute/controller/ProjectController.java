package com.acmp.compute.controller;

import com.acmp.compute.dto.ProjectRequest;
import com.acmp.compute.dto.ProjectResponse;
import com.acmp.compute.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 1.0 项目 API。
 */
@RestController
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping("/api/v1/workspaces/{workspaceId}/projects")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ProjectResponse> create(@PathVariable String workspaceId,
                                                  @Valid @RequestBody ProjectRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(workspaceId, req));
    }

    @PutMapping("/api/v1/projects/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ProjectResponse> update(@PathVariable String id,
                                                   @Valid @RequestBody ProjectRequest req) {
        return ResponseEntity.ok(projectService.update(id, req));
    }

    @DeleteMapping("/api/v1/projects/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        projectService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    @GetMapping("/api/v1/projects/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(projectService.getById(id));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/projects")
    public ResponseEntity<List<ProjectResponse>> listByWorkspace(@PathVariable String workspaceId) {
        return ResponseEntity.ok(projectService.listByWorkspace(workspaceId));
    }

    @PostMapping("/api/v1/projects/{id}/members")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> addMember(@PathVariable String id, @RequestBody Map<String, String> body) {
        projectService.addMember(id, body.get("userId"));
        return ResponseEntity.ok(Map.of("message", "已添加"));
    }

    @DeleteMapping("/api/v1/projects/{id}/members/{userId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> removeMember(@PathVariable String id, @PathVariable String userId) {
        projectService.removeMember(id, userId);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }

    @GetMapping("/api/v1/projects/{id}/members")
    public ResponseEntity<List<String>> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(projectService.listMembers(id));
    }
}
