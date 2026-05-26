package com.acmp.compute.controller;

import com.acmp.compute.dto.ResourcePoolCreateRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.service.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resource-pools")
@RequiredArgsConstructor
public class ResourcePoolController {

    private final ResourcePoolService resourcePoolService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ResourcePoolResponse> create(@Valid @RequestBody ResourcePoolCreateRequest request) {
        ResourcePoolResponse resp = resourcePoolService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<List<ResourcePoolResponse>> list(
            @RequestParam(required = false) String physicalClusterId) {
        if (physicalClusterId != null && !physicalClusterId.isEmpty()) {
            return ResponseEntity.ok(resourcePoolService.listByPhysicalCluster(physicalClusterId));
        }
        return ResponseEntity.ok(resourcePoolService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourcePoolResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(resourcePoolService.getById(id));
    }

    @PatchMapping("/{id}/capacity")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<ResourcePoolResponse> patchCapacity(
            @PathVariable String id,
            @RequestBody Map<String, Integer> body) {
        ResourcePoolResponse resp = resourcePoolService.patchCapacity(
                id,
                body.get("gpuSlots"),
                body.get("cpuCores"),
                body.get("memoryGiB"));
        return ResponseEntity.ok(resp);
    }
}
