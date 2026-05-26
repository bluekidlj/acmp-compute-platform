package com.acmp.compute.service;

import com.acmp.compute.dto.WorkspaceQuotaResponse;
import com.acmp.compute.dto.WorkspaceRequest;
import com.acmp.compute.dto.WorkspaceResponse;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.entity.WorkspaceQuota;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.mapper.WorkspaceQuotaMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作空间 = K8s Namespace（100% 对应）。
 * 创建时完成: Namespace → ResourceQuota → SA → Role → RoleBinding → Volcano Queue → DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceQuotaMapper quotaMapper;
    private final ResourcePoolMapper resourcePoolMapper;
    private final ComputeSpecMapper specMapper;
    private final KubernetesClientManager clientManager;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    /** 创建工作空间 = 创建 K8s Namespace + ResourceQuota + RBAC + Volcano Queue */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse create(WorkspaceRequest request) {
        UserPrincipal user = currentUser();
        String poolId = request.getResourcePoolId();
        ResourcePool pool = resourcePoolMapper.findById(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在: " + poolId));

        // 取父逻辑池的第一个物理集群作为 K8s 目标
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(poolId);
        if (clusterIds.isEmpty()) throw new IllegalStateException("逻辑池未关联物理集群");
        String clusterId = clusterIds.get(0);

        // 生成 K8s 资源名
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String ns = "ws-" + request.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (ns.length() > 50) ns = ns.substring(0, 50);
        ns = ns + "-" + shortId;
        String sa = "sa-" + ns.substring(0, 45);
        String roleName = "role-" + ns.substring(0, 45);
        String rbName = "rb-" + ns.substring(0, 45);
        String quotaName = "quota-" + ns.substring(0, 45);
        String queueName = "queue-" + ns.substring(0, 45);

        int maxPods = request.getMaxPods() != null ? request.getMaxPods() : 50;
        int gpu = request.getGpuSlots(), cpu = request.getCpuCores(), mem = request.getMemoryGib();

        // 创建 K8s 资源：Namespace + 按规格 ResourceQuota + RBAC
        clientManager.createNamespace(clusterId, ns);

        // 构建按规格的 ResourceQuota：读取逻辑池的 spec 配额
        Map<String, String> specLimits = new HashMap<>();
        List<Map<String, Object>> poolSpecs = specMapper.findSpecQuotasByResourcePoolId(poolId);
        for (Map<String, Object> row : poolSpecs) {
            String specName = (String) row.get("spec_name");
            int total = toInt(row.get("total_quota"));
            String rqKey = "platform.io/" + specName;
            specLimits.put(rqKey, String.valueOf(total));
        }
        if (!specLimits.isEmpty()) {
            clientManager.createResourceQuotaBySpec(clusterId, ns, quotaName, specLimits, maxPods);
        } else {
            clientManager.createResourceQuota(clusterId, ns, quotaName, gpu, cpu, mem, maxPods);
        }
        clientManager.createServiceAccount(clusterId, ns, sa);
        clientManager.createRole(clusterId, ns, roleName);
        clientManager.createRoleBinding(clusterId, ns, rbName, roleName, sa);
        String queueYaml = K8sResourceBuilder.buildVolcanoQueue(queueName, String.valueOf(gpu), String.valueOf(cpu), String.valueOf(mem));
        clientManager.applyClusterScopedYaml(clusterId, queueYaml);

        // 校验父池配额 + 更新 allocated
        int newAllocGpu = safeInt(pool.getAllocatedGpuSlots()) + gpu;
        int newAllocCpu = safeInt(pool.getAllocatedCpuCores()) + cpu;
        int newAllocMem = safeInt(pool.getAllocatedMemoryGib()) + mem;
        if (newAllocGpu > pool.getGpuSlots()) throw new IllegalArgumentException("逻辑池 GPU 配额不足");
        if (newAllocCpu > pool.getCpuCores()) throw new IllegalArgumentException("逻辑池 CPU 配额不足");
        if (newAllocMem > pool.getMemoryGiB()) throw new IllegalArgumentException("逻辑池内存配额不足");
        pool.setAllocatedGpuSlots(newAllocGpu);
        pool.setAllocatedCpuCores(newAllocCpu);
        pool.setAllocatedMemoryGib(newAllocMem);
        resourcePoolMapper.updateAllocated(pool);

        // 写入 DB
        String id = UUID.randomUUID().toString();
        Workspace ws = Workspace.builder()
                .id(id).resourcePoolId(poolId).name(request.getName()).description(request.getDescription())
                .namespace(ns).serviceAccountName(sa).volcanoQueueName(queueName).primaryClusterId(clusterId)
                .gpuSlots(gpu).cpuCores(cpu).memoryGib(mem).maxPods(maxPods).nodeCount(1)
                .hardwareType(pool.getHardwareType()).securityLevel(pool.getSecurityLevel())
                .gpuType(request.getGpuType() != null ? request.getGpuType() : pool.getGpuType())
                .jobTypes(request.getJobTypes() != null ? request.getJobTypes() : pool.getJobTypes())
                .createdBy(user.getId()).status("active").build();
        workspaceMapper.insert(ws);

        // 初始化 used 追踪配额
        WorkspaceQuota quota = WorkspaceQuota.builder().id(UUID.randomUUID().toString()).workspaceId(id)
                .maxGpuSlots(gpu).maxCpuCores(cpu).maxMemoryGib(mem).maxPods(maxPods).maxHours(1000)
                .usedGpuSlots(0).usedCpuCores(0).usedMemoryGib(0).build();
        quotaMapper.insert(quota);

        // 初始化按规格的 workspace_spec_quota
        for (Map<String, Object> row : poolSpecs) {
            String specId = (String) row.get("spec_id");
            int total = toInt(row.get("total_quota"));
            specMapper.insertWorkspaceSpecQuota(id, specId, total, 0);
        }

        log.info("✓ 工作空间 {} 已创建 (K8s NS={}, cluster={})", request.getName(), ns, clusterId);
        return buildResponse(ws, quota, pool.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse update(String id, WorkspaceRequest request) {
        Workspace ws = workspaceMapper.findById(id).orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
        ws.setName(request.getName());
        if (request.getDescription() != null) ws.setDescription(request.getDescription());
        workspaceMapper.update(ws);
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(id).orElse(null);
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
        return buildResponse(ws, quota, pool != null ? pool.getName() : null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Workspace ws = workspaceMapper.findById(id).orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
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
        specMapper.deleteWorkspaceSpecQuotas(id);
        // 删除 K8s Namespace（级联删除内部所有资源）
        if (ws.getPrimaryClusterId() != null && ws.getNamespace() != null) {
            try { clientManager.deleteNamespace(ws.getPrimaryClusterId(), ws.getNamespace()); } catch (Exception ignored) {}
        }
        workspaceMapper.deleteById(id);
        log.info("✓ 工作空间 {} 已删除 (K8s NS={})", id, ws.getNamespace());
    }

    public WorkspaceResponse getById(String id) {
        Workspace ws = workspaceMapper.findById(id).orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(id).orElse(null);
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
        return buildResponse(ws, quota, pool != null ? pool.getName() : null);
    }

    public List<WorkspaceResponse> list() {
        return workspaceMapper.findAll().stream().map(ws -> {
            WorkspaceQuota quota = quotaMapper.findByWorkspaceId(ws.getId()).orElse(null);
            ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
            return buildResponse(ws, quota, pool != null ? pool.getName() : null);
        }).collect(Collectors.toList());
    }

    public WorkspaceQuotaResponse setQuota(String workspaceId, Map<String, Integer> body) {
        WorkspaceQuota quota = quotaMapper.findByWorkspaceId(workspaceId).orElse(null);
        if (quota == null) {
            quota = WorkspaceQuota.builder().id(UUID.randomUUID().toString()).workspaceId(workspaceId)
                    .usedGpuSlots(0).usedCpuCores(0).usedMemoryGib(0).build();
            applyQuotaBody(quota, body);
            quotaMapper.insert(quota);
        } else { applyQuotaBody(quota, body); quotaMapper.update(quota); }
        return toQuotaResponse(quota);
    }

    public WorkspaceQuotaResponse getQuota(String workspaceId) {
        return toQuotaResponse(quotaMapper.findByWorkspaceId(workspaceId).orElseThrow(() -> new ResourceNotFoundException("配额不存在")));
    }

    @Transactional
    public void deductQuota(String workspaceId, int gpu, int cpu, int mem) {
        WorkspaceQuota q = quotaMapper.findByWorkspaceId(workspaceId).orElseThrow(() -> new ResourceNotFoundException("配额不存在"));
        if (safeInt(q.getUsedGpuSlots()) + gpu > safeInt(q.getMaxGpuSlots()))
            throw new IllegalArgumentException("GPU 配额不足");
        q.setUsedGpuSlots(safeInt(q.getUsedGpuSlots()) + gpu);
        q.setUsedCpuCores(safeInt(q.getUsedCpuCores()) + cpu);
        q.setUsedMemoryGib(safeInt(q.getUsedMemoryGib()) + mem);
        quotaMapper.update(q);
    }

    @Transactional
    public void restoreQuota(String workspaceId, int gpu, int cpu, int mem) {
        WorkspaceQuota q = quotaMapper.findByWorkspaceId(workspaceId).orElseThrow(() -> new ResourceNotFoundException("配额不存在"));
        q.setUsedGpuSlots(Math.max(0, safeInt(q.getUsedGpuSlots()) - gpu));
        q.setUsedCpuCores(Math.max(0, safeInt(q.getUsedCpuCores()) - cpu));
        q.setUsedMemoryGib(Math.max(0, safeInt(q.getUsedMemoryGib()) - mem));
        quotaMapper.update(q);
    }

    private int safeInt(Integer v) { return v != null ? v : 0; }
    private int toInt(Object v) { if (v instanceof Number) return ((Number) v).intValue(); return 0; }

    // ── 成员管理：纯平台层 DB 记录，K8s 层使用工作空间唯一的 SA ──

    @Transactional
    public void addMember(String workspaceId, String userId) {
        if (workspaceMapper.findById(workspaceId).isEmpty())
            throw new ResourceNotFoundException("工作空间不存在");
        workspaceMapper.insertMember(workspaceId, userId);
        log.info("✓ 用户 {} 已加入工作空间 {}", userId, workspaceId);
    }

    @Transactional
    public void removeMember(String workspaceId, String userId) {
        workspaceMapper.deleteMember(workspaceId, userId);
        log.info("✓ 用户 {} 已移出工作空间 {}", userId, workspaceId);
    }

    public List<String> listMembers(String workspaceId) {
        return workspaceMapper.findMemberIds(workspaceId);
    }
    private void applyQuotaBody(WorkspaceQuota q, Map<String, Integer> body) {
        if (body.containsKey("maxGpuSlots")) q.setMaxGpuSlots(body.get("maxGpuSlots"));
        if (body.containsKey("maxCpuCores")) q.setMaxCpuCores(body.get("maxCpuCores"));
        if (body.containsKey("maxMemoryGib")) q.setMaxMemoryGib(body.get("maxMemoryGib"));
        if (body.containsKey("maxPods")) q.setMaxPods(body.get("maxPods"));
        if (body.containsKey("maxHours")) q.setMaxHours(body.get("maxHours"));
    }

    private WorkspaceResponse buildResponse(Workspace ws, WorkspaceQuota quota, String poolName) {
        return WorkspaceResponse.builder()
                .id(ws.getId()).name(ws.getName()).description(ws.getDescription())
                .resourcePoolId(ws.getResourcePoolId()).resourcePoolName(poolName)
                .namespace(ws.getNamespace()).volcanoQueueName(ws.getVolcanoQueueName())
                .primaryClusterId(ws.getPrimaryClusterId())
                .gpuSlots(ws.getGpuSlots()).cpuCores(ws.getCpuCores()).memoryGib(ws.getMemoryGib())
                .maxPods(ws.getMaxPods()).hardwareType(ws.getHardwareType())
                .gpuType(ws.getGpuType()).jobTypes(ws.getJobTypes())
                .createdBy(ws.getCreatedBy()).status(ws.getStatus())
                .quota(quota != null ? toQuotaResponse(quota) : null)
                .createdAt(ws.getCreatedAt()).updatedAt(ws.getUpdatedAt()).build();
    }

    private WorkspaceQuotaResponse toQuotaResponse(WorkspaceQuota q) {
        int maxG = safeInt(q.getMaxGpuSlots()), maxC = safeInt(q.getMaxCpuCores()), maxM = safeInt(q.getMaxMemoryGib());
        int usedG = safeInt(q.getUsedGpuSlots()), usedC = safeInt(q.getUsedCpuCores()), usedM = safeInt(q.getUsedMemoryGib());
        return WorkspaceQuotaResponse.builder().id(q.getId()).workspaceId(q.getWorkspaceId())
                .maxGpuSlots(maxG).maxCpuCores(maxC).maxMemoryGib(maxM).maxPods(q.getMaxPods()).maxHours(q.getMaxHours())
                .usedGpuSlots(usedG).usedCpuCores(usedC).usedMemoryGib(usedM)
                .availableGpuSlots(maxG - usedG).availableCpuCores(maxC - usedC).availableMemoryGib(maxM - usedM).build();
    }
}

