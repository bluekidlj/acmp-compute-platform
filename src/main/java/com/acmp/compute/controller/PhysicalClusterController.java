package com.acmp.compute.controller;

import com.acmp.compute.dto.CapacityResponse;
import com.acmp.compute.dto.GpuInfoView;
import com.acmp.compute.dto.GpuSplitView;
import com.acmp.compute.dto.NodeView;
import com.acmp.compute.dto.PhysicalClusterRegisterRequest;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.dto.ScanResult;
import com.acmp.compute.service.GpuInventoryService;
import com.acmp.compute.service.PhysicalClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 1.0 物理集群 API + 显卡库存 API。
 *
 * <ul>
 *   <li>集群 CRUD：/api/v1/clusters</li>
 *   <li>容量：/api/v1/clusters/{id}/capacity</li>
 *   <li>显卡：/api/v1/clusters/{id}/gpus</li>
 *   <li>vGPU 切分：/api/v1/clusters/{id}/gpu-splits</li>
 *   <li>节点列表：/api/v1/clusters/{id}/nodes</li>
 *   <li>扫描回写：/api/v1/clusters/{id}/scan</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clusters")
@RequiredArgsConstructor
public class PhysicalClusterController {

    private final PhysicalClusterService physicalClusterService;
    private final GpuInventoryService gpuInventoryService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PhysicalClusterResponse> register(@Valid @RequestBody PhysicalClusterRegisterRequest request) {
        String taintsJson = request.getTaints() == null ? null
                : request.getTaints().stream().collect(Collectors.joining(",", "[", "]"));
        PhysicalClusterResponse resp = physicalClusterService.register(
                request.getName(), request.getKubeconfigBase64(),
                request.getGpuTypes(), request.getLocation(),
                request.getNodeLabels(), taintsJson);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<PhysicalClusterResponse>> list() {
        return ResponseEntity.ok(physicalClusterService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PhysicalClusterResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(physicalClusterService.getById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        physicalClusterService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    @GetMapping("/{id}/capacity")
    public ResponseEntity<CapacityResponse> getCapacity(@PathVariable String id) {
        return ResponseEntity.ok(physicalClusterService.getCapacity(id));
    }

    @GetMapping("/{id}/nodes")
    public ResponseEntity<List<NodeView>> listNodes(@PathVariable String id) {
        return ResponseEntity.ok(gpuInventoryService.listNodes(id));
    }

    @GetMapping("/{id}/gpus")
    public ResponseEntity<Map<String, Object>> listGpus(@PathVariable String id) {
        List<GpuInfoView> gpus = gpuInventoryService.listGpus(id);
        return ResponseEntity.ok(Map.of(
                "clusterId", id,
                "total", gpus,
                "summary", Map.of("gpuModelCount", gpus.size())
        ));
    }

    @GetMapping("/{id}/gpu-splits")
    public ResponseEntity<Map<String, Object>> listGpuSplits(@PathVariable String id) {
        List<GpuSplitView> splits = gpuInventoryService.listGpuSplits(id);
        return ResponseEntity.ok(Map.of("clusterId", id, "splits", splits));
    }

    @PostMapping("/{id}/scan")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ScanResult> scan(@PathVariable String id) {
        return ResponseEntity.ok(gpuInventoryService.scanAndPersist(id));
    }
}
