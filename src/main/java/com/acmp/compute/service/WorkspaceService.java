package com.acmp.compute.service;

import com.acmp.compute.dto.WorkspaceQuotaResponse;
import com.acmp.compute.dto.WorkspaceRequest;
import com.acmp.compute.dto.WorkspaceResponse;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.entity.WorkspaceQuota;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.mapper.WorkspaceQuotaMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作空间服务：资源的二次分配（属于一个逻辑资源池，配额从该池分配校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceQuotaMapper quotaMapper;
    private final ResourcePoolMapper resourcePoolMapper;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    /** 创建工作空间，关联逻辑资源池 + 分配初始配额，校验不超过父池剩余 */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse create(WorkspaceRequest request) {
        UserPrincipal user = currentUser();
        String poolId = request.getResourcePoolId();
        ResourcePool pool = resourcePoolMapper.findById(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在: " + poolId));

        String id = UUID.randomUUID().toString();
        Workspace ws = Workspace.builder()
                .id(id)
                .resourcePoolId(poolId)
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(user.getId())
                .status("active")
                .build();
        workspaceMapper.insert(ws);

        WorkspaceQuota quota = new WorkspaceQuota();
        quota.setId(UUID.randomUUID().toString());
        quota.setWorkspaceId(id);
        quota.setMaxGpuSlots(request.getInitialGpuSlots());
        quota.setMaxCpuCores(request.getInitialCpuCores());
        quota.setMaxMemoryGib(request.getInitialMemoryGib());
        quota.setMaxPods(request.getInitialMaxPods());
        quota.setMaxHours(request.getInitialMaxHours());
        quota.setUsedGpuSlots(0); quota.setUsedCpuCores(0); quota.setUsedMemoryGib(0);
        validateAndUpdateParentAllocation(pool, quota, 0, 0, 0);
        quotaMapper.insert(quota);

        log.info("✓ 工作空间 {} 已创建 (pool={})", request.getName(), poolId);
        return buildResponse(ws, quota, pool.getName());
    }

    /** 修改工作空间 */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse update(String id, WorkspaceRequest request) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + id));
        ws.setName(request.getName());
        if (request.getDescription() != null) ws.setDescription(request.getDescription());
        workspaceMapper.update(ws);
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(id).orElse(null);
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
        return buildResponse(ws, quota, pool != null ? pool.getName() : null);
    }

    /** 删除工作空间：释放配额 → 删配额 → 删工作空间 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + id));
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(id).orElse(null);
        if (quota != null) {
            ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
            if (pool != null) {
                pool.setAllocatedGpuSlots(Math.max(0, safeInt(pool.getAllocatedGpuSlots()) - safeInt(quota.getMaxGpuSlots())));
                pool.setAllocatedCpuCores(Math.max(0, safeInt(pool.getAllocatedCpuCores()) - safeInt(quota.getMaxCpuCores())));
                pool.setAllocatedMemoryGib(Math.max(0, safeInt(pool.getAllocatedMemoryGib()) - safeInt(quota.getMaxMemoryGib())));
                resourcePoolMapper.updateAllocated(pool);
            }
            quotaMapper.deleteByWorkspaceId(id);
        }
        workspaceMapper.deleteById(id);
        log.info("✓ 工作空间 {} 已删除，配额已释放", id);
    }

    public WorkspaceResponse getById(String id) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + id));
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(id).orElse(null);
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
        return buildResponse(ws, quota, pool != null ? pool.getName() : null);
    }

    public List<WorkspaceResponse> list() {
        return workspaceMapper.findAll().stream()
                .map(ws -> {
                    WorkspaceQuota quota = quotaMapper.findByWorkspaceId(ws.getId()).orElse(null);
                    ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
                    return buildResponse(ws, quota, pool != null ? pool.getName() : null);
                }).collect(Collectors.toList());
    }

    /** 设置/更新配额：校验不超过父池剩余 */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceQuotaResponse setQuota(String workspaceId, Map<String, Integer> body) {
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId())
                .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在"));
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(workspaceId).orElse(null);
        int oldGpu = quota != null ? safeInt(quota.getMaxGpuSlots()) : 0;
        int oldCpu = quota != null ? safeInt(quota.getMaxCpuCores()) : 0;
        int oldMem = quota != null ? safeInt(quota.getMaxMemoryGib()) : 0;
        if (quota == null) {
            quota = WorkspaceQuota.builder().id(UUID.randomUUID().toString()).workspaceId(workspaceId)
                    .usedGpuSlots(0).usedCpuCores(0).usedMemoryGib(0).build();
            applyQuotaBody(quota, body);
            validateAndUpdateParentAllocation(pool, quota, 0, 0, 0);
            quotaMapper.insert(quota);
        } else {
            applyQuotaBody(quota, body);
            validateAndUpdateParentAllocation(pool, quota, oldGpu, oldCpu, oldMem);
            quotaMapper.update(quota);
        }
        return toQuotaResponse(quota);
    }

    public WorkspaceQuotaResponse getQuota(String workspaceId) {
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("配额不存在"));
        return toQuotaResponse(quota);
    }

    // ── 配额运行时扣减/恢复 ──
    @Transactional(rollbackFor = Exception.class)
    public void deductQuota(String workspaceId, int gpu, int cpu, int mem) {
        WorkspaceQuota q = quotaMapper.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("配额不存在"));
        int newGpu = safeInt(q.getUsedGpuSlots()) + gpu;
        if (newGpu > safeInt(q.getMaxGpuSlots()))
            throw new IllegalArgumentException("GPU 配额不足: 需要" + gpu + ", 上限" + q.getMaxGpuSlots());
        q.setUsedGpuSlots(newGpu);
        q.setUsedCpuCores(safeInt(q.getUsedCpuCores()) + cpu);
        q.setUsedMemoryGib(safeInt(q.getUsedMemoryGib()) + mem);
        quotaMapper.update(q);
    }

    @Transactional(rollbackFor = Exception.class)
    public void restoreQuota(String workspaceId, int gpu, int cpu, int mem) {
        WorkspaceQuota q = quotaMapper.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("配额不存在"));
        q.setUsedGpuSlots(Math.max(0, safeInt(q.getUsedGpuSlots()) - gpu));
        q.setUsedCpuCores(Math.max(0, safeInt(q.getUsedCpuCores()) - cpu));
        q.setUsedMemoryGib(Math.max(0, safeInt(q.getUsedMemoryGib()) - mem));
        quotaMapper.update(q);
    }

    // ── private ──
    private int safeInt(Integer v) { return v != null ? v : 0; }

    private void applyQuotaBody(WorkspaceQuota q, Map<String, Integer> body) {
        if (body.containsKey("maxGpuSlots")) q.setMaxGpuSlots(body.get("maxGpuSlots"));
        if (body.containsKey("maxCpuCores")) q.setMaxCpuCores(body.get("maxCpuCores"));
        if (body.containsKey("maxMemoryGib")) q.setMaxMemoryGib(body.get("maxMemoryGib"));
        if (body.containsKey("maxPods")) q.setMaxPods(body.get("maxPods"));
        if (body.containsKey("maxHours")) q.setMaxHours(body.get("maxHours"));
    }

    private void validateAndUpdateParentAllocation(ResourcePool pool, WorkspaceQuota q,
                                                    int oldGpu, int oldCpu, int oldMem) {
        int deltaGpu = safeInt(q.getMaxGpuSlots()) - oldGpu;
        int deltaCpu = safeInt(q.getMaxCpuCores()) - oldCpu;
        int deltaMem = safeInt(q.getMaxMemoryGib()) - oldMem;
        int newAllocGpu = safeInt(pool.getAllocatedGpuSlots()) + deltaGpu;
        int newAllocCpu = safeInt(pool.getAllocatedCpuCores()) + deltaCpu;
        int newAllocMem = safeInt(pool.getAllocatedMemoryGib()) + deltaMem;
        if (newAllocGpu > pool.getGpuSlots()) throw new IllegalArgumentException("逻辑池 GPU 配额不足: 总" + pool.getGpuSlots() + ", 本次申请" + deltaGpu);
        if (newAllocCpu > pool.getCpuCores()) throw new IllegalArgumentException("逻辑池 CPU 配额不足");
        if (newAllocMem > pool.getMemoryGiB()) throw new IllegalArgumentException("逻辑池内存配额不足");
        pool.setAllocatedGpuSlots(newAllocGpu);
        pool.setAllocatedCpuCores(newAllocCpu);
        pool.setAllocatedMemoryGib(newAllocMem);
        resourcePoolMapper.updateAllocated(pool);
    }

    private WorkspaceResponse buildResponse(Workspace ws, WorkspaceQuota quota, String poolName) {
        return WorkspaceResponse.builder()
                .id(ws.getId()).name(ws.getName()).description(ws.getDescription())
                .createdBy(ws.getCreatedBy()).status(ws.getStatus())
                .resourcePoolId(ws.getResourcePoolId()).resourcePoolName(poolName)
                .quota(quota != null ? toQuotaResponse(quota) : null)
                .createdAt(ws.getCreatedAt()).updatedAt(ws.getUpdatedAt()).build();
    }

    private WorkspaceQuotaResponse toQuotaResponse(WorkspaceQuota q) {
        int maxG = safeInt(q.getMaxGpuSlots()), maxC = safeInt(q.getMaxCpuCores()), maxM = safeInt(q.getMaxMemoryGib());
        int usedG = safeInt(q.getUsedGpuSlots()), usedC = safeInt(q.getUsedCpuCores()), usedM = safeInt(q.getUsedMemoryGib());
        return WorkspaceQuotaResponse.builder()
                .id(q.getId()).workspaceId(q.getWorkspaceId())
                .maxGpuSlots(maxG).maxCpuCores(maxC).maxMemoryGib(maxM)
                .maxPods(q.getMaxPods()).maxHours(q.getMaxHours())
                .usedGpuSlots(usedG).usedCpuCores(usedC).usedMemoryGib(usedM)
                .availableGpuSlots(maxG - usedG).availableCpuCores(maxC - usedC).availableMemoryGib(maxM - usedM)
                .build();
    }
}
