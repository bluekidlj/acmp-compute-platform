package com.acmp.compute.service;

import com.acmp.compute.dto.ProjectQuotaRequest;
import com.acmp.compute.dto.ProjectQuotaResponse;
import com.acmp.compute.dto.ProjectQuotaUpdateRequest;
import com.acmp.compute.entity.Project;
import com.acmp.compute.entity.ProjectResourceQuota;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ProjectMapper;
import com.acmp.compute.mapper.ProjectResourceQuotaMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 1.0 项目配额服务：管理员把池容量按规格切给项目。
 *
 * 三层校验：
 *   1) 项目 / 池 / 规格 存在
 *   2) 池-规格已关联
 *   3) 池.allocated + req ≤ 池.total
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectQuotaService {

    private final ProjectResourceQuotaMapper quotaMapper;
    private final ProjectMapper projectMapper;
    private final ResourcePoolMapper poolMapper;
    private final ComputeSpecMapper specMapper;

    @Transactional
    public ProjectQuotaResponse allocate(String projectId, ProjectQuotaRequest req) {
        Project project = projectMapper.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
        ResourcePool pool = poolMapper.findById(req.getPoolId())
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + req.getPoolId()));
        if (!project.getWorkspaceId().equals(pool.getWorkspaceId())) {
            throw new BadRequestException("项目与池不在同一工作空间");
        }
        var spec = specMapper.findById(req.getSpecId())
                .orElseThrow(() -> new BadRequestException("规格不存在: " + req.getSpecId()));
        if (!pool.getPoolType().equals(spec.getPoolType())) {
            throw new BadRequestException("规格与池类型不匹配");
        }
        if (specMapper.findSpecIdsByResourcePoolId(pool.getId()).stream().noneMatch(id -> id.equals(spec.getId()))) {
            throw new BadRequestException("规格未关联到该资源池");
        }

        // 池容量校验
        int allocated = pool.getAllocatedNodes() != null ? pool.getAllocatedNodes() : 0;
        int total = pool.getTotalNodes() != null ? pool.getTotalNodes() : 0;
        if (allocated + req.getTotalNodes() > total) {
            throw new BadRequestException(String.format(
                    "池容量不足: 池 %s 剩余 %d, 申请 %d", pool.getName(),
                    total - allocated, req.getTotalNodes()));
        }

        // 同一项目对 (pool, spec) 已分配则覆盖
        var existing = quotaMapper.findByProjectPoolSpec(projectId, pool.getId(), spec.getId());
        String quotaId;
        int oldTotal = 0;
        if (existing.isPresent()) {
            quotaId = existing.get().getId();
            oldTotal = existing.get().getTotalNodes();
            quotaMapper.updateTotalNodes(quotaId, req.getTotalNodes());
        } else {
            quotaId = UUID.randomUUID().toString();
            ProjectResourceQuota q = ProjectResourceQuota.builder()
                    .id(quotaId)
                    .projectId(projectId)
                    .resourcePoolId(pool.getId())
                    .specId(spec.getId())
                    .totalNodes(req.getTotalNodes())
                    .usedNodes(0)
                    .build();
            quotaMapper.insert(q);
        }

        // 更新池.allocated
        int delta = req.getTotalNodes() - oldTotal;
        int newAllocated = Math.max(0, allocated + delta);
        poolMapper.updateAllocated(pool.getId(), newAllocated);

        log.info("✓ 项目 {} 配额分配: pool={}, spec={}, totalNodes={} (delta={})",
                projectId, pool.getName(), spec.getName(), req.getTotalNodes(), delta);
        return toResponse(quotaId, projectId, pool.getId(), spec.getId(),
                req.getTotalNodes(), existing.map(ProjectResourceQuota::getUsedNodes).orElse(0));
    }

    @Transactional
    public ProjectQuotaResponse update(String projectId, String quotaId, ProjectQuotaUpdateRequest req) {
        var quota = quotaMapper.findById(quotaId)
                .orElseThrow(() -> new ResourceNotFoundException("配额记录不存在: " + quotaId));
        if (!quota.getProjectId().equals(projectId)) {
            throw new BadRequestException("配额记录不属于该项目");
        }
        ResourcePool pool = poolMapper.findById(quota.getResourcePoolId())
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在"));
        int allocated = pool.getAllocatedNodes() != null ? pool.getAllocatedNodes() : 0;
        int total = pool.getTotalNodes() != null ? pool.getTotalNodes() : 0;
        int otherAllocated = allocated - quota.getTotalNodes();
        if (otherAllocated + req.getTotalNodes() > total) {
            throw new BadRequestException("池容量不足");
        }
        if (req.getTotalNodes() < quota.getUsedNodes()) {
            throw new BadRequestException("新配额不能小于已使用");
        }
        quotaMapper.updateTotalNodes(quotaId, req.getTotalNodes());
        poolMapper.updateAllocated(pool.getId(), otherAllocated + req.getTotalNodes());
        return toResponse(quotaId, projectId, pool.getId(), quota.getSpecId(),
                req.getTotalNodes(), quota.getUsedNodes());
    }

    @Transactional
    public void delete(String projectId, String quotaId) {
        var quota = quotaMapper.findById(quotaId)
                .orElseThrow(() -> new ResourceNotFoundException("配额记录不存在: " + quotaId));
        if (!quota.getProjectId().equals(projectId)) {
            throw new BadRequestException("配额记录不属于该项目");
        }
        if (quota.getUsedNodes() != null && quota.getUsedNodes() > 0) {
            throw new BadRequestException("配额已被使用，不允许删除");
        }
        ResourcePool pool = poolMapper.findById(quota.getResourcePoolId()).orElseThrow();
        poolMapper.updateAllocated(pool.getId(),
                Math.max(0, (pool.getAllocatedNodes() != null ? pool.getAllocatedNodes() : 0) - quota.getTotalNodes()));
        quotaMapper.deleteById(quotaId);
        log.info("✓ 项目配额已删除: {}", quotaId);
    }

    private ProjectQuotaResponse toResponse(String id, String projectId, String poolId, String specId,
                                             int total, int used) {
        return ProjectQuotaResponse.builder()
                .id(id)
                .projectId(projectId)
                .poolId(poolId)
                .specId(specId)
                .totalNodes(total)
                .usedNodes(used)
                .availableNodes(Math.max(0, total - used))
                .build();
    }
}
