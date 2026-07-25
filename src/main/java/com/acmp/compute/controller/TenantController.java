package com.acmp.compute.controller;

import com.acmp.compute.dto.TenantRequest;
import com.acmp.compute.dto.TenantResponse;
import com.acmp.compute.dto.TenantSpecQuotaRequest;
import com.acmp.compute.dto.TenantSpecQuotaResponse;
import com.acmp.compute.service.TenantService;
import com.acmp.compute.service.TenantSpecQuotaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 租户与租户规格配额接口。
 *
 * <p>租户是纯业务边界，不直接绑定 Kubernetes 集群或 Namespace。
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final TenantSpecQuotaService quotaService;

    /**
     * 创建租户。
     *
     * @param request 租户名称和描述
     * @return 新创建的租户
     */
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody TenantRequest request) {
        TenantResponse response = tenantService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 查询全部租户。
     */
    @GetMapping
    public ResponseEntity<List<TenantResponse>> list() {
        return ResponseEntity.ok(tenantService.list());
    }

    /**
     * 查询租户详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(tenantService.get(id));
    }

    /**
     * 修改租户名称或描述。
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<TenantResponse> update(
            @PathVariable String id,
            @Valid @RequestBody TenantRequest request) {
        return ResponseEntity.ok(tenantService.update(id, request));
    }

    /**
     * 删除空租户。
     *
     * <p>租户存在项目或已使用配额时拒绝删除。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        tenantService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /**
     * 为租户分配一个 Spec 配额。
     */
    @PostMapping("/{id}/spec-quotas")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantSpecQuotaResponse> createQuota(
            @PathVariable String id,
            @Valid @RequestBody TenantSpecQuotaRequest request) {
        TenantSpecQuotaResponse response = quotaService.create(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 查询租户拥有的全部 Spec 配额。
     */
    @GetMapping("/{id}/spec-quotas")
    public ResponseEntity<List<TenantSpecQuotaResponse>> listQuotas(@PathVariable String id) {
        return ResponseEntity.ok(quotaService.list(id));
    }

    /**
     * 修改租户 Spec 的总配额。
     */
    @PatchMapping("/{id}/spec-quotas/{quotaId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantSpecQuotaResponse> updateQuota(
            @PathVariable String id,
            @PathVariable String quotaId,
            @RequestBody QuotaTotalRequest request) {
        return ResponseEntity.ok(quotaService.update(id, quotaId, request.getTotal()));
    }

    /**
     * 删除一个尚未使用的租户 Spec 配额。
     */
    @DeleteMapping("/{id}/spec-quotas/{quotaId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> deleteQuota(
            @PathVariable String id,
            @PathVariable String quotaId) {
        quotaService.delete(id, quotaId);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /**
     * 修改配额总量的请求体。
     */
    @Data
    public static class QuotaTotalRequest {
        private int total;
    }
}
