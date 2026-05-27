package com.acmp.compute.controller;

import com.acmp.compute.dto.CapacityResponse;
import com.acmp.compute.dto.PhysicalClusterRegisterRequest;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.k8s.NodeScanResponse;
import com.acmp.compute.service.PhysicalClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/physical-clusters")
@RequiredArgsConstructor
public class PhysicalClusterController {

    private final PhysicalClusterService physicalClusterService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PhysicalClusterResponse> register(@Valid @RequestBody PhysicalClusterRegisterRequest request) {
        PhysicalClusterResponse resp = physicalClusterService.register(
                request.getName(), request.getKubeconfigBase64(),
                request.getGpuTypes() != null ? request.getGpuTypes() : GpuBrand.NVIDIA.name(),
                request.getLocation() != null ? request.getLocation() : "default",
                request.getNodeLabels(), request.getTaints());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<PhysicalClusterResponse>> list() {
        return ResponseEntity.ok(physicalClusterService.list());
    }

    @GetMapping("/{id}/capacity")
    public ResponseEntity<CapacityResponse> getCapacity(@PathVariable String id) {
        return ResponseEntity.ok(physicalClusterService.getCapacity(id));
    }

    /**
     * 扫描集群节点，收集节点算力信息（用于纳管展示）。
     * 返回节点列表 + 集群 poolLabel 枚举。
     */
    @GetMapping("/{id}/nodes")
    public ResponseEntity<NodeScanResponse> scanNodes(@PathVariable String id) {
        return ResponseEntity.ok(physicalClusterService.scanNodes(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        physicalClusterService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
