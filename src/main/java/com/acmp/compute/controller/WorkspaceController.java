package com.acmp.compute.controller;

import com.acmp.compute.dto.WorkspaceRequest;
import com.acmp.compute.dto.WorkspaceResponse;
import com.acmp.compute.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 工作空间管理 API：CRUD + 成员。配额信息嵌入 WorkspaceResponse.specQuotas 返回。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody WorkspaceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaceService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<WorkspaceResponse> update(@PathVariable String id,
                                                     @Valid @RequestBody WorkspaceRequest request) {
        return ResponseEntity.ok(workspaceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        workspaceService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    @GetMapping
    public ResponseEntity<List<WorkspaceResponse>> list() {
        return ResponseEntity.ok(workspaceService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkspaceResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(workspaceService.getById(id));
    }

    // ── 成员管理 ──

    @PostMapping("/{id}/members")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> addMember(@PathVariable String id,
                                                          @RequestBody Map<String, String> body) {
        workspaceService.addMember(id, body.get("userId"));
        return ResponseEntity.ok(Map.of("message", "已添加成员"));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> removeMember(@PathVariable String id,
                                                             @PathVariable String userId) {
        workspaceService.removeMember(id, userId);
        return ResponseEntity.ok(Map.of("message", "已移除成员"));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<String>> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(workspaceService.listMembers(id));
    }
}
