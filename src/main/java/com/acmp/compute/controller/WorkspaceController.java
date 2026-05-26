package com.acmp.compute.controller;

import com.acmp.compute.dto.WorkspaceQuotaResponse;
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
 * 工作空间管理 API：创建、修改、删除、关联资源池、配额管理。
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

    /** 设置/更新工作空间配额 */
    @PutMapping("/{id}/quota")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<WorkspaceQuotaResponse> setQuota(@PathVariable String id,
                                                            @RequestBody Map<String, Integer> body) {
        return ResponseEntity.ok(workspaceService.setQuota(id, body));
    }

    /** 查询工作空间配额 */
    @GetMapping("/{id}/quota")
    public ResponseEntity<WorkspaceQuotaResponse> getQuota(@PathVariable String id) {
        return ResponseEntity.ok(workspaceService.getQuota(id));
    }
}
