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
 *  ④ 按 spec.nodeSelector 选定目标物理集群
 *     【异构算力说明】允许多个规格分散在不同物理集群（NVIDIA/DCU 混部场景）。
 *     工作空间通过 workspace_pool_cluster 关联表记录涉及的物理集群列表，
 *     部署时由 PoolMetadataService.pickClusterForSpec 根据请求的 spec 动态选定目标集群，
 *     而非在工作空间创建时就写死单一 primaryClusterId。
 *  ⑤ K8s 创建 Namespace + ResourceQuota(platform.io/{spec}) + SA + Role + RoleBinding + Volcano Queue
 *  ⑥ 双侧账本：
 *      - resource_pool_spec_quota.allocated += req
 *      - workspace_pool_spec_quota.max = req, used = 0
 *  ⑦ 写 workspace 行 + workspace_resource_pool 绑定 + workspace_pool_cluster 关联
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
            int total = toInt(q.get("total_nodes"));
            int allocated = toInt(q.get("allocated_nodes"));
            if (allocated + item.getMaxQuota() > total) {
                throw new BadRequestException(String.format(
                        "L1 配额不足: 规格=%s, total=%d, allocated=%d, 申请=%d",
                        item.getSpecName(), total, allocated, item.getMaxQuota()));
            }
        }

        // ④ 选定目标物理集群
        // 【异构算力核心修改】移除"所有规格必须指向同一集群"的约束。
        // 允许多个规格分散在不同物理集群（NVIDIA/DCU 混部场景）。
        // 每个规格各自匹配目标集群，结果集用于后续创建跨集群 K8s 资源。
        // 不再写死单一的 primaryClusterId，而是通过 workspace_pool_cluster 关联表记录。
        Set<String> targetClusterIds = new HashSet<>();
        for (ComputeSpec spec : specByName.values()) {
            targetClusterIds.add(poolMetadataService.pickClusterForSpec(poolId, spec).getId());
        }

        // ⑤ K8s 名生成
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String ns = sanitize("ws-" + request.getName(), 40) + "-" + shortId;
        String sa = "sa-" + ns;
        String roleName = "role-" + ns;
        String rbName = "rb-" + ns;
        String quotaName = "quota-" + ns;
        String queueName = "queue-" + ns;

        int maxPods = request.getMaxPods() != null ? request.getMaxPods() : 50;

        // ⑤ K8s 资源创建
        // 【异构算力】遍历所有涉及的物理集群，在每个集群上创建 Namespace + ResourceQuota + SA + Role + RoleBinding
        // Volcano Queue 为集群级资源，每个集群单独创建一份（Queue 名全局唯一，加 namespace 前缀区分）
        // 注意：Namespace 在 K8s 中是集群级资源，同名 NS 在不同集群是独立的，不冲突
        for (String clusterId : targetClusterIds) {
            clientManager.createNamespace(clusterId, ns);

            // 仅给该集群上实际使用的规格创建 ResourceQuota（按 cluster+spec 过滤）
            Map<String, String> specLimits = new LinkedHashMap<>();
            for (ComputeSpec spec : specByName.values()) {
                // 检查该规格的目标集群是否是当前 clusterId
                if (poolMetadataService.pickClusterForSpec(poolId, spec).getId().equals(clusterId)) {
                    // 找到该规格在此池中的 maxQuota
                    for (WorkspaceRequest.SpecQuotaItem item : request.getSpecQuotas()) {
                        if (item.getSpecName().equals(spec.getName())) {
                            specLimits.put(spec.getResourceQuotaKey(), String.valueOf(item.getMaxQuota()));
                            break;
                        }
                    }
                }
            }
            if (!specLimits.isEmpty()) {
                clientManager.createResourceQuotaBySpec(clusterId, ns, quotaName + "-" + clusterId, specLimits, maxPods);
            }

            // SA + Role + RoleBinding（所有集群用相同的 ns 名，但资源相互独立）
            clientManager.createServiceAccount(clusterId, ns, sa);
            clientManager.createRole(clusterId, ns, roleName);
            clientManager.createRoleBinding(clusterId, ns, rbName, roleName, sa);

            // Volcano Queue：每个集群单独创建一份（Queue 是集群级资源）
            Map<String, String> queueCapability = new LinkedHashMap<>(specLimits);
            String queueYaml = K8sResourceBuilder.buildVolcanoQueue(queueName + "-" + clusterId, queueCapability);
            clientManager.applyClusterScopedYaml(clusterId, queueYaml);
        }

        // ⑥ DB：更新 L1.allocated + 写 L2 + 写 workspace_pool_cluster
        String wsId = UUID.randomUUID().toString();
        for (WorkspaceRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specByName.get(item.getSpecName());
            Map<String, Object> q = poolQuotaBySpecId.get(spec.getId());
            int newAllocated = toInt(q.get("allocated_nodes")) + item.getMaxQuota();
            specMapper.updateResourcePoolSpecAllocated(poolId, spec.getId(), newAllocated);
            specMapper.insertWorkspaceSpecQuota(wsId, poolId, spec.getId(), item.getMaxQuota(), 0);
        }

        // ⑦ workspace 行（primaryClusterId 已废弃，设为 null）
        Workspace ws = Workspace.builder()
                .id(wsId)
                .resourcePoolId(poolId)
                .name(request.getName())
                .description(request.getDescription())
                .namespace(ns)
                .serviceAccountName(sa)
                .volcanoQueueName(queueName)
                .primaryClusterId(null) // 【异构算力】不再写死单一集群
                .maxPods(maxPods)
                .nodeCount(1)
                .createdBy(user.getId())
                .status("active")
                .build();
        workspaceMapper.insert(ws);

        // ⑧ 写 workspace_pool_cluster 关联（记录该工作空间涉及的所有物理集群）
        for (String clusterId : targetClusterIds) {
            workspaceMapper.insertCluster(wsId, clusterId);
        }

        log.info("✓ 工作空间 {} 已创建 (ns={}, clusters={}, specs={})",
                request.getName(), ns, targetClusterIds, specByName.keySet());

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

        // 释放 L1.allocated（按 ws 当前持有的 max_nodes 回退）
        List<Map<String, Object>> wsQuotas = specMapper.findSpecQuotasByWorkspaceId(id);
        for (Map<String, Object> q : wsQuotas) {
            String specId = (String) q.get("spec_id");
            String poolId = (String) q.get("resource_pool_id");
            int max = toInt(q.get("max_nodes"));
            // L1.allocated -= max
            List<Map<String, Object>> poolQs = specMapper.findSpecQuotasByResourcePoolId(poolId);
            for (Map<String, Object> pq : poolQs) {
                if (specId.equals(pq.get("spec_id"))) {
                    int newAllocated = Math.max(0, toInt(pq.get("allocated_nodes")) - max);
                    specMapper.updateResourcePoolSpecAllocated(poolId, specId, newAllocated);
                    break;
                }
            }
        }
        specMapper.deleteWorkspaceSpecQuotas(id);

        // 【异构算力】删除工作空间关联的所有物理集群上的 K8s Namespace（分别删除）
        List<String> clusterIds = workspaceMapper.findClusterIds(id);
        for (String clusterId : clusterIds) {
            if (ws.getNamespace() != null) {
                try {
                    clientManager.deleteNamespace(clusterId, ws.getNamespace());
                } catch (Exception e) {
                    log.warn("删除 K8s Namespace 失败（继续删 DB）: {}", e.getMessage());
                }
            }
        }

        // 清理 workspace_pool_cluster 关联表
        workspaceMapper.deleteClusters(id);

        workspaceMapper.deleteById(id);
        log.info("✓ 工作空间 {} 已删除 (ns={}, clusters={})", id, ws.getNamespace(), clusterIds);
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
            int max = toInt(q.get("max_nodes"));
            int used = toInt(q.get("used_nodes"));
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
