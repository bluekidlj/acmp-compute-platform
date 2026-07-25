package com.acmp.compute.controller;

import com.acmp.compute.dto.PhysicalClusterRegisterRequest;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.ClusterNode;
import com.acmp.compute.entity.GpuDevice;
import com.acmp.compute.service.ClusterInventoryService;
import com.acmp.compute.service.PhysicalClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes 集群接入、同步及 Node/GPU 库存查询接口。
 */
@RestController
@RequestMapping("/api/v1/clusters")
@RequiredArgsConstructor
public class PhysicalClusterController {

    private final PhysicalClusterService physicalClusterService;
    private final ClusterInventoryService clusterInventoryService;

    /**
     * 注册内网 Kubernetes 集群。
     *
     * <p>注册时验证 kubeconfig 的 Node 查询权限，并执行第一次库存同步。
     */
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PhysicalClusterResponse> register(
            @Valid @RequestBody PhysicalClusterRegisterRequest request) {
        PhysicalClusterResponse response = physicalClusterService.register(
                request.getName(),
                request.getDescription(),
                request.getKubeconfig());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 重新同步指定集群的 Node 和 GPU 库存。
     */
    @PostMapping("/{id}/sync")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PhysicalClusterResponse> sync(@PathVariable String id) {
        clusterInventoryService.sync(id);
        return ResponseEntity.ok(physicalClusterService.getById(id));
    }

    /**
     * 查询数据库中保存的节点库存。
     */
    @GetMapping("/{id}/nodes")
    public ResponseEntity<List<ClusterNode>> listNodes(@PathVariable String id) {
        return ResponseEntity.ok(clusterInventoryService.listNodes(id));
    }

    /**
     * 查询数据库中保存的 GPU 库存。
     */
    @GetMapping("/{id}/gpus")
    public ResponseEntity<List<GpuDevice>> listGpus(@PathVariable String id) {
        return ResponseEntity.ok(clusterInventoryService.listGpusByCluster(id));
    }

    /**
     * 查询指定真实 Node 上发现的 GPU 设备。
     *
     * @param id 集群 ID
     * @param nodeId Node 库存 ID
     */
    @GetMapping("/{id}/nodes/{nodeId}/gpus")
    public ResponseEntity<List<GpuDevice>> listNodeGpus(@PathVariable String id,
                                                        @PathVariable String nodeId) {
        return ResponseEntity.ok(clusterInventoryService.listGpusByNode(id, nodeId));
    }

    /**
     * 查询全部已注册集群。
     */
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<PhysicalClusterResponse>> list() {
        return ResponseEntity.ok(physicalClusterService.list());
    }

    /**
     * 查询集群详情和最近一次同步结果。
     */
    @GetMapping("/{id}")
    public ResponseEntity<PhysicalClusterResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(physicalClusterService.getById(id));
    }

    /**
     * 删除集群记录并关闭缓存的 Kubernetes 客户端。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        physicalClusterService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

}
