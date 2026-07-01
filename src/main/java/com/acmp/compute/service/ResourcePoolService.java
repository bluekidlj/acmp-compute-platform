package com.acmp.compute.service;

import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.dto.ResourcePoolUpdateRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.PoolCardMapper;
import com.acmp.compute.mapper.ProjectResourceQuotaMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1.0 资源池服务（Workspace 私有三类池）。
 *
 * <p>池由 WorkspaceService.create 自动建三类空池。
 * 管理员通过 PATCH /pools/{id} 设置容量并关联规格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourcePoolService {

    private final ResourcePoolMapper poolMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ComputeSpecMapper specMapper;
    private final ProjectResourceQuotaMapper projectQuotaMapper;
    private final PoolCardMapper poolCardMapper;
    private final KubernetesClientManager clientManager;

    public List<ResourcePoolResponse> listByWorkspace(String workspaceId) {
        return poolMapper.findByWorkspaceId(workspaceId).stream()
                .map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    public ResourcePoolResponse getById(String id) {
        ResourcePool pool = poolMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        return toResponse(pool);
    }

    /**
     * 修改池容量 + 关联规格（覆盖式）。
     * 同步 K8s ResourceQuota：hard[platform.io/{spec}] = totalNodes。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourcePoolResponse update(String id, ResourcePoolUpdateRequest req) {
        ResourcePool pool = poolMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        Workspace ws = workspaceMapper.findById(pool.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + pool.getWorkspaceId()));

        // 校验 specs（如果传了）：必须与 pool.poolType 一致
        if (req.getSpecs() != null) {
            for (String specId : req.getSpecs()) {
                ComputeSpec spec = specMapper.findById(specId)
                        .orElseThrow(() -> new BadRequestException("规格不存在: " + specId));
                if (!pool.getPoolType().equals(spec.getPoolType())) {
                    throw new BadRequestException(String.format(
                            "规格 %s 的 poolType=%s 与池 %s 的 poolType=%s 不匹配",
                            spec.getName(), spec.getPoolType(), pool.getName(), pool.getPoolType()));
                }
            }
        }

        // 写规格关联（覆盖式）
        if (req.getSpecs() != null) {
            specMapper.deleteResourcePoolSpecsByPool(id);
            for (String specId : req.getSpecs()) {
                specMapper.insertResourcePoolSpec(id, specId);
            }
        }

        // 同步 K8s ResourceQuota（按 spec 维度，hard[platform.io/{spec}] = sum(pool_card.slots)）
        try {
            Map<String, String> specLimits = new LinkedHashMap<>();
            List<ComputeSpec> specs = specMapper.findByResourcePoolId(id);
            for (ComputeSpec s : specs) {
                int slots = poolCardMapper.sumActiveSlotsByPoolAndSpec(id, s.getId());
                specLimits.put(s.getResourceQuotaKey(), String.valueOf(slots));
            }
            int total = pool.getTotalNodes() != null ? pool.getTotalNodes() : 0;
            if (!specLimits.isEmpty()) {
                clientManager.createResourceQuotaBySpec(
                        pool.getPrimaryClusterId(), ws.getNamespace(),
                        "quota-" + pool.getPoolType().toLowerCase() + "-" + id.substring(0, 8),
                        specLimits, Math.max(50, total * 10));
            }
        } catch (Exception e) {
            log.warn("K8s ResourceQuota 同步失败（继续）: {}", e.getMessage());
        }

        log.info("✓ 池 {} 已更新: totalNodes={}（自动累加）, specs={}", id, pool.getTotalNodes(),
                req.getSpecs() != null ? req.getSpecs().size() : "(未变)");
        return toResponse(poolMapper.findById(id).orElseThrow());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        ResourcePool pool = poolMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        if (pool.getAllocatedNodes() != null && pool.getAllocatedNodes() > 0) {
            throw new BadRequestException("池已被项目分配，不允许删除");
        }
        specMapper.deleteResourcePoolSpecsByPool(id);
        poolMapper.deleteById(id);
        log.info("✓ 池已删除: {}", id);
    }

    private ResourcePoolResponse toResponse(ResourcePool p) {
        List<ComputeSpec> specs = specMapper.findByResourcePoolId(p.getId());
        List<ResourcePoolResponse.SpecBrief> specBriefs = new ArrayList<>();
        for (ComputeSpec s : specs) {
            specBriefs.add(ResourcePoolResponse.SpecBrief.builder()
                    .id(s.getId())
                    .name(s.getName())
                    .displayName(s.getDisplayName())
                    .specType(s.getSpecType())
                    .poolType(s.getPoolType())
                    .build());
        }
        int total = p.getTotalNodes() != null ? p.getTotalNodes() : 0;
        int allocated = p.getAllocatedNodes() != null ? p.getAllocatedNodes() : 0;
        return ResourcePoolResponse.builder()
                .id(p.getId())
                .workspaceId(p.getWorkspaceId())
                .poolType(p.getPoolType())
                .name(p.getName())
                .description(p.getDescription())
                .primaryClusterId(p.getPrimaryClusterId())
                .totalNodes(total)
                .allocatedNodes(allocated)
                .availableNodes(Math.max(0, total - allocated))
                .status(p.getStatus())
                .specs(specBriefs)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
