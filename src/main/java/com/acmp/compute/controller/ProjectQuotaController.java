package com.acmp.compute.controller;

import com.acmp.compute.dto.ProjectQuotaRequest;
import com.acmp.compute.dto.ProjectQuotaResponse;
import com.acmp.compute.dto.ProjectQuotaUpdateRequest;
import com.acmp.compute.service.ProjectQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

/**
 * 1.0 项目配额 API：管理员把池容量按规格切给项目。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/quotas")
@RequiredArgsConstructor
public class ProjectQuotaController {

    private final ProjectQuotaService quotaService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ProjectQuotaResponse> allocate(@PathVariable String projectId,
                                                          @Valid @RequestBody ProjectQuotaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotaService.allocate(projectId, req));
    }

    @PatchMapping("/{quotaId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ProjectQuotaResponse> update(@PathVariable String projectId,
                                                        @PathVariable String quotaId,
                                                        @Valid @RequestBody ProjectQuotaUpdateRequest req) {
        return ResponseEntity.ok(quotaService.update(projectId, quotaId, req));
    }

    @DeleteMapping("/{quotaId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String projectId, @PathVariable String quotaId) {
        quotaService.delete(projectId, quotaId);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
