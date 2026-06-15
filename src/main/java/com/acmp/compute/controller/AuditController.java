package com.acmp.compute.controller;

import com.acmp.compute.dto.AuditReport;
import com.acmp.compute.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.0 对账 API：管理员手动触发对账，返回 report。
 * 定时对账由 {@link com.acmp.compute.scheduler.QuotaReconcileScheduler} 跑。
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/deployments")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<AuditReport> auditDeployments() {
        return ResponseEntity.ok(auditService.generate());
    }
}
