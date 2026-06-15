package com.acmp.compute.controller;

import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.dto.ResourcePoolUpdateRequest;
import com.acmp.compute.service.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 1.0 资源池 API。
 * 池由 WorkspaceService.create 自动建三类；这里只做 PATCH（容量+规格关联）、GET、DELETE。
 */
@RestController
@RequiredArgsConstructor
public class ResourcePoolController {

    private final ResourcePoolService poolService;

    // 列出工作空间下所有池
    @GetMapping("/api/v1/workspaces/{workspaceId}/pools")
    public ResponseEntity<List<ResourcePoolResponse>> listByWorkspace(@PathVariable String workspaceId) {
        return ResponseEntity.ok(poolService.listByWorkspace(workspaceId));
    }

    // 池详情
    @GetMapping("/api/v1/pools/{id}")
    public ResponseEntity<ResourcePoolResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(poolService.getById(id));
    }

    // 修改池容量 + 关联规格（覆盖式）
    @PatchMapping("/api/v1/pools/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ResourcePoolResponse> update(@PathVariable String id,
                                                       @Valid @RequestBody ResourcePoolUpdateRequest req) {
        return ResponseEntity.ok(poolService.update(id, req));
    }

    // 删除池（仅当无项目分配）
    @DeleteMapping("/api/v1/pools/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        poolService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
