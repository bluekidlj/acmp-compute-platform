package com.acmp.compute.service;

import com.acmp.compute.dto.ResourcePoolCreateRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 逻辑资源池：纯 DB 聚合，不创建 K8s 资源。
 *
 * 创建语义：
 *  1. 校验物理集群存在
 *  2. 校验所有规格名存在
 *  3. 写 resource_pool 行
 *  4. 写 resource_pool_physical_cluster 关联
 *  5. 写 resource_pool_spec_quota（按规格配额，allocated 初始为 0）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourcePoolService {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;
    private final ComputeSpecMapper computeSpecMapper;

    @Transactional(rollbackFor = Exception.class)
    public ResourcePoolResponse create(ResourcePoolCreateRequest request) {
        // 1. 物理集群存在性校验
        List<String> clusterIds = request.getPhysicalClusterIds();
        for (String cid : clusterIds) {
            if (physicalClusterMapper.findById(cid).isEmpty())
                throw new ResourceNotFoundException("物理集群不存在: " + cid);
        }

        // 2. 规格存在性校验 + 名称→ID 映射
        Map<String, ComputeSpec> specByName = request.getSpecQuotas().stream()
                .map(item -> computeSpecMapper.findByName(item.getSpecName())
                        .orElseThrow(() -> new BadRequestException("规格不存在: " + item.getSpecName())))
                .collect(Collectors.toMap(ComputeSpec::getName, s -> s, (a, b) -> a));

        // 3. resource_pool 行
        String id = UUID.randomUUID().toString();
        ResourcePool pool = ResourcePool.builder()
                .id(id)
                .name(request.getName())
                .description(request.getDescription())
                .departmentCode(request.getDepartmentCode())
                .departmentName(request.getDepartmentName())
                .status("active")
                .build();
        resourcePoolMapper.insert(pool);

        // 4. 物理集群关联
        for (String cid : clusterIds) {
            resourcePoolMapper.insertPhysicalCluster(id, cid);
        }

        // 5. 按规格配额（allocated 起始 0）
        for (ResourcePoolCreateRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specByName.get(item.getSpecName());
            computeSpecMapper.insertResourcePoolSpecQuota(id, spec.getId(), item.getTotalQuota(), 0);
        }

        log.info("✓ 逻辑资源池 {} 已创建 (clusters={}, specs={})",
                id, clusterIds.size(), request.getSpecQuotas().size());

        return toResponse(resourcePoolMapper.findById(id).orElseThrow());
    }

    public List<ResourcePoolResponse> listByPhysicalCluster(String cid) {
        return resourcePoolMapper.findByPhysicalClusterId(cid).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<ResourcePoolResponse> list() {
        return resourcePoolMapper.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ResourcePoolResponse getById(String id) {
        ResourcePool pool = resourcePoolMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof UserPrincipal && !((UserPrincipal) p).canAccessPool(id))
            throw new ForbiddenException("无权限");
        return toResponse(pool);
    }

    /**
     * 旧 patchCapacity API 已废弃：资源量按规格存储于 resource_pool_spec_quota，
     * 调整请通过 PATCH /resource-pools/{id}/spec-quotas 接口（待实现）。
     * 目前保留方法签名以兼容现有 Controller，但直接返回当前快照。
     */
    public ResourcePoolResponse patchCapacity(String id, Integer gpuSlots, Integer cpuCores, Integer memoryGiB) {
        log.warn("patchCapacity 已废弃：逻辑池资源量按规格管理，参数 gpu/cpu/mem 被忽略");
        return getById(id);
    }

    private ResourcePoolResponse toResponse(ResourcePool p) {
        List<String> cids = resourcePoolMapper.findPhysicalClusterIds(p.getId());

        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByResourcePoolId(p.getId());
        List<ResourcePoolResponse.SpecQuotaView> specViews = new ArrayList<>();
        for (Map<String, Object> q : quotas) {
            int total = toInt(q.get("total_quota"));
            int allocated = toInt(q.get("allocated_quota"));
            specViews.add(ResourcePoolResponse.SpecQuotaView.builder()
                    .specId((String) q.get("spec_id"))
                    .specName((String) q.get("spec_name"))
                    .totalQuota(total)
                    .allocatedQuota(allocated)
                    .availableQuota(total - allocated)
                    .build());
        }

        return ResourcePoolResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .departmentCode(p.getDepartmentCode())
                .departmentName(p.getDepartmentName())
                .status(p.getStatus())
                .physicalClusterIds(cids)
                .specQuotas(specViews)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}
