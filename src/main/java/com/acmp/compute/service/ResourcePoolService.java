package com.acmp.compute.service;

import com.acmp.compute.dto.ResourcePoolCreateRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 逻辑资源池：纯 DB 逻辑分组。不创建 K8s 资源。
 * K8s Namespace + ResourceQuota 的创建在 WorkspaceService 中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourcePoolService {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;

    @Transactional(rollbackFor = Exception.class)
    public ResourcePoolResponse create(ResourcePoolCreateRequest request) {
        List<String> clusterIds = request.getPhysicalClusterIds();
        if (clusterIds == null || clusterIds.isEmpty())
            throw new IllegalArgumentException("至少需要关联一个物理集群");
        for (String cid : clusterIds) {
            if (physicalClusterMapper.findById(cid).isEmpty())
                throw new ResourceNotFoundException("物理集群不存在: " + cid);
        }
        String id = UUID.randomUUID().toString();
        ResourcePool pool = ResourcePool.builder()
                .id(id).name(request.getName()).description(request.getDescription())
                .departmentCode(request.getDepartmentCode()).departmentName(request.getDepartmentName())
                .gpuSlots(request.getGpuSlots()).cpuCores(request.getCpuCores()).memoryGiB(request.getMemoryGib())
                .maxPods(request.getMaxPods() != null ? request.getMaxPods() : 50)
                .nodeCount(request.getNodeCount() != null ? request.getNodeCount() : 1)
                .allocatedGpuSlots(0).allocatedCpuCores(0).allocatedMemoryGib(0)
                .hardwareType(request.getHardwareType() != null ? request.getHardwareType() : "NVIDIA-GPU")
                .gpuType(request.getGpuType() != null ? request.getGpuType() : GpuBrand.NVIDIA)
                .jobTypes(request.getJobTypes() != null ? request.getJobTypes() : "TRAINING,INFERENCE")
                .status("active").build();
        resourcePoolMapper.insert(pool);
        for (String cid : clusterIds) resourcePoolMapper.insertPhysicalCluster(id, cid);
        log.info("✓ 逻辑资源池 {} 已创建 (纯DB, 关联{}个物理集群)", id, clusterIds.size());
        return toResponse(resourcePoolMapper.findById(id).orElseThrow());
    }

    public List<ResourcePoolResponse> listByPhysicalCluster(String cid) {
        return resourcePoolMapper.findByPhysicalClusterId(cid).stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ResourcePoolResponse> list() {
        return resourcePoolMapper.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public ResourcePoolResponse getById(String id) {
        ResourcePool pool = resourcePoolMapper.findById(id).orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof UserPrincipal && !((UserPrincipal) p).canAccessPool(id))
            throw new ForbiddenException("无权限");
        return toResponse(pool);
    }

    private ResourcePoolResponse toResponse(ResourcePool p) {
        List<String> cids = resourcePoolMapper.findPhysicalClusterIds(p.getId());
        int alloc = p.getAllocatedGpuSlots() != null ? p.getAllocatedGpuSlots() : 0;
        return ResourcePoolResponse.builder()
                .id(p.getId()).name(p.getName()).description(p.getDescription())
                .departmentCode(p.getDepartmentCode()).departmentName(p.getDepartmentName())
                .physicalClusterIds(cids)
                .gpuSlots(p.getGpuSlots()).cpuCores(p.getCpuCores()).memoryGiB(p.getMemoryGiB())
                .maxPods(p.getMaxPods()).nodeCount(p.getNodeCount())
                .allocatedGpuSlots(alloc).availableGpuSlots(p.getGpuSlots() - alloc)
                .hardwareType(p.getHardwareType()).gpuType(p.getGpuType()).jobTypes(p.getJobTypes())
                .status(p.getStatus()).createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt())
                .build();
    }
}
