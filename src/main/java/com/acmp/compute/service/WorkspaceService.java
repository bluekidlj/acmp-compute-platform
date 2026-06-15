package com.acmp.compute.service;

import com.acmp.compute.dto.WorkspacePoolSummary;
import com.acmp.compute.dto.WorkspaceRequest;
import com.acmp.compute.dto.WorkspaceResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1.0 工作空间（租户）服务。
 *
 * <h2>创建流程</h2>
 * <ol>
 *   <li>校验物理集群存在</li>
 *   <li>写 workspace 行（primaryClusterId）</li>
 *   <li>自动建 3 个 ResourcePool（EXCLUSIVE / SHARED / OVERSELL）</li>
 *   <li>K8s：Namespace + SA + Role + RoleBinding + Volcano Queue</li>
 *   <li>写 workspace_member（可选）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final String[] POOL_TYPES = {"EXCLUSIVE", "SHARED", "OVERSELL"};

    private final WorkspaceMapper workspaceMapper;
    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;
    private final ComputeSpecMapper computeSpecMapper;
    private final KubernetesClientManager clientManager;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse create(WorkspaceRequest request) {
        UserPrincipal user = currentUser();
        PhysicalCluster cluster = physicalClusterMapper.findById(request.getClusterId())
                .orElseThrow(() -> new BadRequestException("物理集群不存在: " + request.getClusterId()));

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitize(request.getName(), 30);
        String ns = "ws-" + safeName + "-" + shortId;
        String sa = "sa-" + ns;
        String roleName = "role-" + ns;
        String rbName = "rb-" + ns;
        String queueName = "queue-" + ns;

        Workspace ws = Workspace.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .description(request.getDescription())
                .primaryClusterId(cluster.getId())
                .namespace(ns)
                .serviceAccountName(sa)
                .volcanoQueueName(queueName)
                .maxPods(request.getMaxPods() != null ? request.getMaxPods() : 50)
                .createdBy(user.getId())
                .status("active")
                .build();
        workspaceMapper.insert(ws);

        // 三类池
        for (String type : POOL_TYPES) {
            ResourcePool pool = ResourcePool.builder()
                    .id(UUID.randomUUID().toString())
                    .workspaceId(ws.getId())
                    .poolType(type)
                    .name(ws.getName() + "-" + type.toLowerCase())
                    .description(ws.getName() + " 的 " + type + " 池")
                    .primaryClusterId(cluster.getId())
                    .totalNodes(0)
                    .allocatedNodes(0)
                    .status("active")
                    .build();
            resourcePoolMapper.insert(pool);
        }

        // K8s 资源
        try {
            clientManager.createNamespace(cluster.getId(), ns);
            clientManager.createServiceAccount(cluster.getId(), ns, sa);
            clientManager.createRole(cluster.getId(), ns, roleName);
            clientManager.createRoleBinding(cluster.getId(), ns, rbName, roleName, sa);

            String queueYaml = K8sResourceBuilder.buildVolcanoQueue(queueName, new java.util.LinkedHashMap<>());
            clientManager.applyClusterScopedYaml(cluster.getId(), queueYaml);
        } catch (Exception e) {
            log.error("K8s 资源创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("工作空间创建失败（K8s 资源）: " + e.getMessage(), e);
        }

        // 成员
        if (request.getMemberIds() != null) {
            for (String userId : request.getMemberIds()) {
                workspaceMapper.insertMember(ws.getId(), userId);
            }
        }

        log.info("✓ 工作空间 {} 已创建 (ns={}, cluster={})", ws.getName(), ns, cluster.getId());
        return toResponse(ws, cluster, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceResponse update(String id, WorkspaceRequest request) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + id));
        ws.setName(request.getName());
        if (request.getDescription() != null) ws.setDescription(request.getDescription());
        if (request.getMaxPods() != null) ws.setMaxPods(request.getMaxPods());
        workspaceMapper.update(ws);
        PhysicalCluster cluster = physicalClusterMapper.findById(ws.getPrimaryClusterId()).orElse(null);
        return toResponse(ws, cluster, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + id));
        PhysicalCluster cluster = physicalClusterMapper.findById(ws.getPrimaryClusterId()).orElse(null);

        // K8s Namespace（级联删除内部所有资源）
        if (cluster != null && ws.getNamespace() != null) {
            try {
                clientManager.deleteNamespace(cluster.getId(), ws.getNamespace());
            } catch (Exception e) {
                log.warn("删除 K8s Namespace 失败: {}", e.getMessage());
            }
        }

        // 删除三类池（级联 resource_pool_spec）
        List<ResourcePool> pools = resourcePoolMapper.findByWorkspaceId(id);
        for (ResourcePool p : pools) {
            computeSpecMapper.deleteResourcePoolSpecsByPool(p.getId());
            resourcePoolMapper.deleteById(p.getId());
        }

        // 删 WS 成员
        workspaceMapper.deleteAllMembers(id);

        workspaceMapper.deleteById(id);
        log.info("✓ 工作空间 {} 已删除", id);
    }

    public List<WorkspaceResponse> list() {
        List<WorkspaceResponse> result = new ArrayList<>();
        for (Workspace ws : workspaceMapper.findAll()) {
            PhysicalCluster cluster = physicalClusterMapper.findById(ws.getPrimaryClusterId()).orElse(null);
            result.add(toResponse(ws, cluster, false));
        }
        return result;
    }

    public WorkspaceResponse getById(String id) {
        Workspace ws = workspaceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + id));
        PhysicalCluster cluster = physicalClusterMapper.findById(ws.getPrimaryClusterId()).orElse(null);
        return toResponse(ws, cluster, true);
    }

    // ── 成员管理 ──
    @Transactional
    public void addMember(String workspaceId, String userId) {
        if (workspaceMapper.findById(workspaceId).isEmpty())
            throw new ResourceNotFoundException("工作空间不存在");
        workspaceMapper.insertMember(workspaceId, userId);
    }

    @Transactional
    public void removeMember(String workspaceId, String userId) {
        workspaceMapper.deleteMember(workspaceId, userId);
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

    private WorkspaceResponse toResponse(Workspace ws, PhysicalCluster cluster, boolean withPools) {
        WorkspaceResponse.WorkspaceResponseBuilder b = WorkspaceResponse.builder()
                .id(ws.getId())
                .name(ws.getName())
                .description(ws.getDescription())
                .primaryClusterId(ws.getPrimaryClusterId())
                .primaryClusterName(cluster != null ? cluster.getName() : null)
                .namespace(ws.getNamespace())
                .volcanoQueueName(ws.getVolcanoQueueName())
                .serviceAccountName(ws.getServiceAccountName())
                .maxPods(ws.getMaxPods())
                .createdBy(ws.getCreatedBy())
                .status(ws.getStatus())
                .createdAt(ws.getCreatedAt())
                .updatedAt(ws.getUpdatedAt())
                .memberIds(workspaceMapper.findMemberIds(ws.getId()));

        if (withPools) {
            List<ResourcePool> pools = resourcePoolMapper.findByWorkspaceId(ws.getId());
            List<WorkspacePoolSummary> summaries = new ArrayList<>();
            for (ResourcePool p : pools) {
                int specCount = computeSpecMapper.findByResourcePoolId(p.getId()).size();
                int total = p.getTotalNodes() != null ? p.getTotalNodes() : 0;
                int allocated = p.getAllocatedNodes() != null ? p.getAllocatedNodes() : 0;
                summaries.add(WorkspacePoolSummary.builder()
                        .id(p.getId())
                        .poolType(p.getPoolType())
                        .name(p.getName())
                        .description(p.getDescription())
                        .totalNodes(total)
                        .allocatedNodes(allocated)
                        .availableNodes(Math.max(0, total - allocated))
                        .specCount(specCount)
                        .build());
            }
            b.pools(summaries);
        }
        return b.build();
    }
}
