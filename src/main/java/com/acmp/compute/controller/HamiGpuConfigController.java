package com.acmp.compute.controller;

import com.acmp.compute.entity.HamiGpuConfig;
import com.acmp.compute.entity.HamiVgpuUnit;
import com.acmp.compute.service.HamiGpuConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【HAMi vGPU】GPU 切分配置管理 API。
 *
 * 提供 HAMi vGPU 切分配置的 CRUD，以及 vGPU 单元的手动同步。
 * 管理员通过此 API 管理物理 GPU 的切分参数，平台据此实现 vGPU 规格路由。
 */
@RestController
@RequestMapping("/api/v1/hami-gpu-configs")
@RequiredArgsConstructor
public class HamiGpuConfigController {

    private final HamiGpuConfigService hamiGpuConfigService;

    // ─────────────────────────── GPU 切分配置 CRUD ───────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<HamiGpuConfig> create(@RequestBody Map<String, Object> request) {
        String physicalClusterId = (String) request.get("physicalClusterId");
        String gpuType = (String) request.get("gpuType");
        int gpuMemMb = (Integer) request.get("gpuMemMb");
        int gpuCores = (Integer) request.get("gpuCores");
        int totalVgpuCount = (Integer) request.get("totalVgpuCount");
        String nodeSelectorKey = (String) request.get("nodeSelectorKey");
        String nodeSelectorPrefix = (String) request.get("nodeSelectorPrefix");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unitsRaw = (List<Map<String, Object>>) request.get("vgpuUnits");
        List<HamiVgpuUnit> vgpuUnits = unitsRaw.stream().map(m -> HamiVgpuUnit.builder()
                .vgpuIndex((Integer) m.get("vgpuIndex"))
                .vgpuName((String) m.get("vgpuName"))
                .vgpuMemMb((Integer) m.get("vgpuMemMb"))
                .vgpuCores((Integer) m.get("vgpuCores"))
                .nodeSelectorValue((String) m.get("nodeSelectorValue"))
                .tolerations(m.containsKey("tolerations") ? (String) m.get("tolerations") : null)
                .availableCount(totalVgpuCount)
                .build()).collect(Collectors.toList());

        HamiGpuConfig config = hamiGpuConfigService.createGpuConfig(
                physicalClusterId, gpuType, gpuMemMb, gpuCores, totalVgpuCount,
                nodeSelectorKey, nodeSelectorPrefix, vgpuUnits);
        return ResponseEntity.status(HttpStatus.CREATED).body(config);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<List<HamiGpuConfig>> list() {
        // TODO: 按权限过滤
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<HamiGpuConfig> getById(@PathVariable String id) {
        return ResponseEntity.ok(hamiGpuConfigService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<HamiGpuConfig> update(@PathVariable String id,
                                                @RequestBody Map<String, Object> request) {
        String gpuType = (String) request.get("gpuType");
        int gpuMemMb = (Integer) request.get("gpuMemMb");
        int gpuCores = (Integer) request.get("gpuCores");
        int totalVgpuCount = (Integer) request.get("totalVgpuCount");
        String nodeSelectorKey = (String) request.get("nodeSelectorKey");
        String nodeSelectorPrefix = (String) request.get("nodeSelectorPrefix");

        HamiGpuConfig config = hamiGpuConfigService.updateGpuConfig(
                id, gpuType, gpuMemMb, gpuCores, totalVgpuCount, nodeSelectorKey, nodeSelectorPrefix);
        return ResponseEntity.ok(config);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        hamiGpuConfigService.deleteGpuConfig(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    @GetMapping("/cluster/{clusterId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<List<HamiGpuConfig>> listByCluster(@PathVariable String clusterId) {
        return ResponseEntity.ok(hamiGpuConfigService.listByCluster(clusterId));
    }

    // ─────────────────────────── vGPU 单元 ───────────────────────────

    @PostMapping("/{id}/vgpu-units")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<HamiVgpuUnit> addVgpuUnit(@PathVariable String id,
                                                     @RequestBody Map<String, Object> request) {
        HamiVgpuUnit unit = HamiVgpuUnit.builder()
                .vgpuIndex((Integer) request.get("vgpuIndex"))
                .vgpuName((String) request.get("vgpuName"))
                .vgpuMemMb((Integer) request.get("vgpuMemMb"))
                .vgpuCores((Integer) request.get("vgpuCores"))
                .nodeSelectorValue((String) request.get("nodeSelectorValue"))
                .tolerations(request.containsKey("tolerations") ? (String) request.get("tolerations") : null)
                .availableCount((Integer) request.get("availableCount"))
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(hamiGpuConfigService.addVgpuUnit(id, unit));
    }

    @GetMapping("/{id}/vgpu-units")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<List<HamiVgpuUnit>> listVgpuUnits(@PathVariable String id) {
        return ResponseEntity.ok(hamiGpuConfigService.listVgpuUnits(id));
    }

    @DeleteMapping("/{id}/vgpu-units/{unitId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> deleteVgpuUnit(@PathVariable String id,
                                                                @PathVariable String unitId) {
        hamiGpuConfigService.deleteVgpuUnit(unitId);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    // ─────────────────────────── 手动同步 ───────────────────────────

    /**
     * 手动同步 vGPU 可用数量。
     * 从 K8s 节点 allocatable 查询 nvidia.com/gpumem，计算满足条件的节点数。
     */
    @PostMapping("/{id}/sync")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> syncAvailableCount(
            @PathVariable String id,
            @RequestBody Map<String, String> request) {
        String clusterId = request.get("clusterId");
        String vgpuUnitId = request.get("vgpuUnitId");
        hamiGpuConfigService.syncAvailableCount(clusterId, vgpuUnitId);
        return ResponseEntity.ok(Map.of("message", "同步完成"));
    }
}