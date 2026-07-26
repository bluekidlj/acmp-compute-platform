package com.acmp.compute.controller;

import com.acmp.compute.dto.NodeJoinSpecRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.entity.ClusterNode;
import com.acmp.compute.entity.GpuDevice;
import com.acmp.compute.service.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 平台固定资源池接口。
 *
 * <p>平台只维护独享池和共享池，不允许用户创建或删除资源池。
 */
@RestController
@RequestMapping("/api/v1/resource-pools")
@RequiredArgsConstructor
public class ResourcePoolController {

    private final ResourcePoolService service;

    /**
     * 查询平台固定的独享池和共享池。
     */
    @GetMapping
    public ResponseEntity<List<ResourcePoolResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    /**
     * 查询资源池详情和 GPU 总量。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResourcePoolResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(service.getById(id));
    }

    /**
     * 查询已经加入指定资源池的 GPU。
     */
    @GetMapping("/{id}/gpus")
    public ResponseEntity<List<GpuDevice>> listGpus(@PathVariable String id) {
        return ResponseEntity.ok(service.listGpus(id));
    }

    /**
     * 查询已经整体加入指定资源池的 Node。
     */
    @GetMapping("/{id}/nodes")
    public ResponseEntity<List<ClusterNode>> listNodes(@PathVariable String id) {
        return ResponseEntity.ok(service.listNodes(id));
    }

    /**
     * 将一台 Node 的全部 GPU 一次性加入指定资源池并写入 Kubernetes 调度标签。
     */
    @PostMapping("/{id}/nodes/{nodeId}/join")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<SpecResponse> joinNode(
            @PathVariable String id,
            @PathVariable String nodeId,
            @Valid @RequestBody NodeJoinSpecRequest request) {
        return ResponseEntity.ok(service.joinNode(id, nodeId, request));
    }

}
