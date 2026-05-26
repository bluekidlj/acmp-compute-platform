package com.acmp.compute.service;

import com.acmp.compute.dto.WorkspaceRequest;
import com.acmp.compute.dto.WorkspaceResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作空间 = K8s Namespace。
 *
 * 创建流程（按规格驱动）：
 *  ① 校验逻辑池存在
 *  ② 加载并校验所有规格存在
 *  ③ 校验 L1 配额：pool.allocated + req ≤ pool.total（每个规格）
 *  ④ 按 spec.nodeSelector 选定一个目标物理集群（所有规格必须指向同一集群）
 *  ⑤ K8s 创建 Namespace + ResourceQuota(platform.io/{spec}) + SA + Role + RoleBinding + Volcano Queue
 *  ⑥ 双侧账本：
 *      - resource_pool_spec_quota.allocated += req
 *      - workspace_pool_spec_quota.max = req, used = 0
 *  ⑦ 写 workspace 行 + workspace_resource_pool 绑定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final ResourcePoolMapper resourcePoolMapper;
    private final ComputeSpecMapper specMapper;
    private final KubernetesClientManager clientManager;
    private final PoolMetadataService poolMetadataService;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse create(WorkspaceRequest request) {
        request.requireSpecQuotasForCreate();
        UserPrincipal user = currentUser();

        // ① 逻辑池
        String poolId = request.getResourcePoolId();
        ResourcePool pool = resourcePoolMapper.findById(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在: " + poolId));

        // ② 规格存在性校验 + name→spec
        Map<String, ComputeSpec> specByName = new LinkedHashMap<>();
        for (WorkspaceRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specMapper.findByName(item.getSpecName())
                    .orElseThrow(() -> new BadRequestException("规格不存在: " + item.getSpecName()));
            specByName.put(item.getSpecName(), spec);
        }

        // ③ L1 配额校验（pool.allocated + req ≤ pool.total）
        List<Map<String, Object>> poolQuotas = specMapper.findSpecQuotasByResourcePoolId(poolId);
        Map<String, Map<String, Object>> poolQuotaBySpecId = new HashMap<>();
        for (Map<String, Object> q : poolQuotas) {
            poolQuotaBySpecId.put((String) q.get("spec_id"), q);
        }
        for (WorkspaceRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specByName.get(item.getSpecName());
            Map<String, Object> q = poolQuotaBySpecId.get(spec.getId());
            if (q == null) {
                throw new BadRequestException("逻辑池 " + poolId + " 未配置规格 " + item.getSpecName() + " 的配额");
            }
            int total = toInt(q.get("total_quota"));
            int allocated = toInt(q.get("allocated_quota"));
            if (allocated + item.getMaxQuota() > total) {
                throw new BadRequestException(String.format(
                        "L1 配额不足: 规格=%s, total=%d, allocated=%d, 申请=%d",
                        item.getSpecName(), total, allocated, item.getMaxQuota()));
            }
        }

        // ④ 选定目标物理集群（所有规格必须指向同一集群）
        Set<String> targetClusterIds = new HashSet<>();
        PoolMetadataService.TargetCluster target = null;
        for (ComputeSpec spec : specByName.values()) {
            PoolMetadataService.TargetCluster t = poolMetadataService.pickClusterForSpec(poolId, spec);
            targetClusterIds.add(t.getClusterId());
            target = t;
        }
        if (targetClusterIds.size() > 1) {
            throw new BadRequestException(
                    "工作空间所申请的规格分散在多个物理集群（" + targetClusterIds
                            + "），请拆分为多个工作空间或调整规格选择");
        }
        String clusterId = target.getClusterId();

        // ⑤ K8s 名生成
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String ns = sanitize("ws-" + request.getName(), 40) + "-" + shortId;
        String sa = "sa-" + ns;
        String roleName = "role-" + ns;
        String rbName = "rb-" + ns;
        String quotaName = "quota-" + ns;
        String queueName = "queue-" + ns;

        int maxPods = request.getMaxPods() != null ? request.getMaxPods() : 50;

        // ⑤a Namespace
        clientManager.createNamespace(clusterId, ns);

        // ⑤b ResourceQuota：按 platform.io/{spec} 设置上限
        Map<String, String> specLimits = new LinkedHashMap<>();
        for (WorkspaceRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specByName.get(item.getSpecName());
            specLimits.put(spec.getResourceQuotaKey(), String.valueOf(item.getMaxQuota()));
        }
        clientManager.createResourceQuotaBySpec(clusterId, ns, quotaName, specLimits, maxPods);

        // ⑤c SA + Role + RoleBinding
        clientManager.createServiceAccount(clusterId, ns, sa);
        clientManager.createRole(clusterId, ns, roleName);
        clientManager.createRoleBinding(clusterId, ns, rbName, roleName, sa);

        // ⑤d Volcano Queue：capability 也用 platform.io/{spec}，与 ResourceQuota 统一资源键
        Map<String, String> queueCapability = new LinkedHashMap<>(specLimits);
        String queueYaml = K8sResourceBuilder.buildVolcanoQueue(queueName, queueCapability);
        clientManager.applyClusterScopedYaml(clusterId, queueYaml);

        // ⑥ DB：更新 L1.allocated + 写 L2
        String wsId = UUID.randomUUID().toString();
        for (WorkspaceRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specByName.get(item.getSpecName());
            Map<String, Object> q = poolQuotaBySpecId.get(spec.getId());
            int newAllocated = toInt(q.get("allocated_quota")) + item.getMaxQuota();
            specMapper.updateResourcePoolSpecAllocated(poolId, spec.getId(), newAllocated);
            specMapper.insertWorkspaceSpecQuota(wsId, poolId, spec.getId(), item.getMaxQuota(), 0);
        }

        // ⑦ workspace 行
        Workspace ws = Workspace.builder()
                .id(wsId)
                .resourcePoolId(poolId)
                .name(request.getName())
                .description(request.getDescription())
                .namespace(ns)
                .serviceAccountName(sa)
                .volcanoQueueName(queueName)
                .primaryClusterId(clusterId)
                .maxPods(maxPods)
                .nodeCount(1)
                .createdBy(user.getId())
                .status("active")
                .build();
        workspaceMapper.insert(ws);

        log.info("✓ 工作空间 {} 已创建 (ns={}, cluster={}, specs={})",
                request.getName(), ns, clusterId, specLimits.keySet());

        return buildResponse(ws, pool.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse update(String id, WorkspaceRequest request) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
        ws.setName(request.getName());
        if (request.getDescription() != null) ws.setDescription(request.getDescription());
        workspaceMapper.update(ws);
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
        return buildResponse(ws, pool != null ? pool.getName() : null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));

        // 释放 L1.allocated（按 ws 当前持有的 max_quota 回退）
        List<Map<String, Object>> wsQuotas = specMapper.findSpecQuotasByWorkspaceId(id);
        for (Map<String, Object> q : wsQuotas) {
            String specId = (String) q.get("spec_id");
            String poolId = (String) q.get("resource_pool_id");
            int max = toInt(q.get("max_quota"));
            // L1.allocated -= max
            List<Map<String, Object>> poolQs = specMapper.findSpecQuotasByResourcePoolId(poolId);
            for (Map<String, Object> pq : poolQs) {
                if (specId.equals(pq.get("spec_id"))) {
                    int newAllocated = Math.max(0, toInt(pq.get("allocated_quota")) - max);
                    specMapper.updateResourcePoolSpecAllocated(poolId, specId, newAllocated);
                    break;
                }
            }
        }
        specMapper.deleteWorkspaceSpecQuotas(id);

        // 删除 K8s Namespace（级联清空所有资源）
        if (ws.getPrimaryClusterId() != null && ws.getNamespace() != null) {
            try {
                clientManager.deleteNamespace(ws.getPrimaryClusterId(), ws.getNamespace());
            } catch (Exception e) {
                log.warn("删除 K8s Namespace 失败（继续删 DB）: {}", e.getMessage());
            }
        }

        workspaceMapper.deleteById(id);
        log.info("✓ 工作空间 {} 已删除 (ns={})", id, ws.getNamespace());
    }

    public WorkspaceResponse getById(String id) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
        ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
        return buildResponse(ws, pool != null ? pool.getName() : null);
    }

    public List<WorkspaceResponse> list() {
        return workspaceMapper.findAll().stream().map(ws -> {
            ResourcePool pool = resourcePoolMapper.findById(ws.getResourcePoolId()).orElse(null);
            return buildResponse(ws, pool != null ? pool.getName() : null);
        }).collect(Collectors.toList());
    }

    // ── 成员管理 ──

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

    // ── helpers ──

    private String sanitize(String s, int maxLen) {
        String x = s.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (x.length() > maxLen) x = x.substring(0, maxLen);
        return x.replaceAll("-+$", "");
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private WorkspaceResponse buildResponse(Workspace ws, String poolName) {
        List<Map<String, Object>> quotas = specMapper.findSpecQuotasByWorkspaceId(ws.getId());
        List<WorkspaceResponse.SpecQuotaView> specViews = new ArrayList<>();
        for (Map<String, Object> q : quotas) {
            int max = toInt(q.get("max_quota"));
            int used = toInt(q.get("used_quota"));
            specViews.add(WorkspaceResponse.SpecQuotaView.builder()
                    .specId((String) q.get("spec_id"))
                    .specName((String) q.get("spec_name"))
                    .maxQuota(max)
                    .usedQuota(used)
                    .availableQuota(max - used)
                    .build());
        }

        return WorkspaceResponse.builder()
                .id(ws.getId())
                .name(ws.getName())
                .description(ws.getDescription())
                .resourcePoolId(ws.getResourcePoolId())
                .resourcePoolName(poolName)
                .namespace(ws.getNamespace())
                .volcanoQueueName(ws.getVolcanoQueueName())
                .primaryClusterId(ws.getPrimaryClusterId())
                .maxPods(ws.getMaxPods())
                .createdBy(ws.getCreatedBy())
                .status(ws.getStatus())
                .specQuotas(specViews)
                .createdAt(ws.getCreatedAt())
                .updatedAt(ws.getUpdatedAt())
                .build();
    }
}
