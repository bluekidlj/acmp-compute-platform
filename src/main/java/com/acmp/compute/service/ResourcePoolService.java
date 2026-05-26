package com.acmp.compute.service;

import com.acmp.compute.dto.ResourcePoolCreateRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
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
 * 逻辑资源池服务：创建时依次完成 Namespace → ResourceQuota → RBAC(SA/Role/RB) → Volcano Queue → 落库。
 * 所有用户通过平台代理操作，不 per-user 创建 K8s ServiceAccount。
 * 使用 fabric8 Builder API 构建 K8s 资源，无需模板引擎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourcePoolService {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;
    private final KubernetesClientManager clientManager;

    /**
     * 创建逻辑资源池（部门级）：严格按顺序完成以下步骤，保证 K8s 与 DB 一致。
     * 步骤：
     * 1) 校验物理集群存在
     * 2) 生成 namespace 与相关 K8s 资源名
     * 3) 创建 Namespace
     * 4) 创建 ResourceQuota（包含 maxPods 限制）
     * 5) 创建 ServiceAccount
     * 6) 创建 Role（部门级权限）
     * 7) 创建 RoleBinding
     * 8) 创建 Volcano Queue（集群级）
     * 9) 写入 DB
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourcePoolResponse create(ResourcePoolCreateRequest request) {
        List<String> clusterIds = request.getPhysicalClusterIds();
        if (clusterIds == null || clusterIds.isEmpty()) {
            throw new IllegalArgumentException("至少需要关联一个物理集群");
        }
        // 校验所有物理集群存在
        for (String cid : clusterIds) {
            if (physicalClusterMapper.findById(cid).isEmpty()) {
                throw new ResourceNotFoundException("物理集群不存在: " + cid);
            }
        }
        // 以第一个物理集群作为 K8s 资源创建目标
        String primaryClusterId = clusterIds.get(0);

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "dept-" + request.getDepartmentCode() + "-" + shortId;
        String serviceAccountName = "sa-dept-" + request.getDepartmentCode();
        String roleName = "role-dept-" + request.getDepartmentCode();
        String roleBindingName = "rb-dept-" + request.getDepartmentCode();
        String quotaName = "quota-dept-" + request.getDepartmentCode();
        String volcanoQueueName = "queue-dept-" + request.getDepartmentCode();

        int maxPods = request.getMaxPods() != null ? request.getMaxPods() : 50;
        clientManager.createNamespace(primaryClusterId, namespace);
        clientManager.createResourceQuota(primaryClusterId, namespace, quotaName,
                request.getGpuSlots(), request.getCpuCores(), request.getMemoryGiB(), maxPods);
        clientManager.createServiceAccount(primaryClusterId, namespace, serviceAccountName);
        clientManager.createRole(primaryClusterId, namespace, roleName);
        clientManager.createRoleBinding(primaryClusterId, namespace, roleBindingName, roleName, serviceAccountName);

        String queueYaml = K8sResourceBuilder.buildVolcanoQueue(
                volcanoQueueName,
                String.valueOf(request.getGpuSlots()),
                String.valueOf(request.getCpuCores()),
                String.valueOf(request.getMemoryGiB()));
        clientManager.applyClusterScopedYaml(primaryClusterId, queueYaml);

        String id = UUID.randomUUID().toString();
        ResourcePool pool = ResourcePool.builder()
                .id(id)
                .name(request.getName())
                .description(request.getDescription())
                .departmentCode(request.getDepartmentCode())
                .departmentName(request.getDepartmentName())
                .namespace(namespace)
                .serviceAccountName(serviceAccountName)
                .gpuSlots(request.getGpuSlots())
                .cpuCores(request.getCpuCores())
                .memoryGiB(request.getMemoryGiB())
                .maxPods(maxPods)
                .nodeCount(request.getNodeCount() != null ? request.getNodeCount() : 1)
                .allocatedGpuSlots(0)
                .allocatedCpuCores(0)
                .allocatedMemoryGib(0)
                .hardwareType(request.getHardwareType() != null ? request.getHardwareType() : "NVIDIA-GPU")
                .securityLevel(request.getSecurityLevel() != null ? request.getSecurityLevel() : "NORMAL")
                .gpuType(request.getGpuType() != null ? request.getGpuType() : "NVIDIA")
                .jobTypes(request.getJobTypes() != null ? request.getJobTypes() : "TRAINING,INFERENCE")
                .volcanoQueueName(volcanoQueueName)
                .status("active")
                .build();
        resourcePoolMapper.insert(pool);

        // 建立 M2M 关联
        for (String cid : clusterIds) {
            resourcePoolMapper.insertPhysicalCluster(id, cid);
        }

        log.info("✓ 逻辑资源池 {} 已创建 (namespace={}, 关联{}个物理集群)", id, namespace, clusterIds.size());
        return toResponse(resourcePoolMapper.findById(id).orElseThrow());
    }

    /** 按物理集群 ID 查询其下所有逻辑子池 */
    public List<ResourcePoolResponse> listByPhysicalCluster(String physicalClusterId) {
        return resourcePoolMapper.findByPhysicalClusterId(physicalClusterId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public List<ResourcePoolResponse> list() {
        return resourcePoolMapper.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** 获取资源池详情；非 PLATFORM_ADMIN/ORG_ADMIN 时校验当前用户是否拥有该 pool 权限 */
    public ResourcePoolResponse getById(String id) {
        ResourcePool pool = resourcePoolMapper.findById(id).orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof UserPrincipal) {
            UserPrincipal user = (UserPrincipal) p;
            if (!user.canAccessPool(id)) throw new ForbiddenException("无权限访问该资源池");
        }
        return toResponse(pool);
    }

    /**
     * 在线扩缩容：更新 DB 记录 + 同步更新 K8s ResourceQuota + Volcano Queue。
     * 扩缩过程不中断在运行作业（仅修改配额上限，不重启 Pod）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourcePoolResponse patchCapacity(String id, Integer gpuSlots, Integer cpuCores, Integer memoryGiB) {
        ResourcePool pool = resourcePoolMapper.findById(id).orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + id));
        if (gpuSlots != null) pool.setGpuSlots(gpuSlots);
        if (cpuCores != null) pool.setCpuCores(cpuCores);
        if (memoryGiB != null) pool.setMemoryGiB(memoryGiB);
        resourcePoolMapper.update(pool);

        // 取第一个物理集群作为 K8s 资源更新目标
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(id);
        if (!clusterIds.isEmpty()) {
            String primaryClusterId = clusterIds.get(0);
            String quotaName = "quota-dept-" + pool.getDepartmentCode();
            int maxPods = pool.getMaxPods() != null ? pool.getMaxPods() : 50;
            clientManager.createResourceQuota(primaryClusterId, pool.getNamespace(), quotaName,
                    pool.getGpuSlots(), pool.getCpuCores(), pool.getMemoryGiB(), maxPods);

            String queueYaml = K8sResourceBuilder.buildVolcanoQueue(
                    pool.getVolcanoQueueName(),
                    String.valueOf(pool.getGpuSlots()),
                    String.valueOf(pool.getCpuCores()),
                    String.valueOf(pool.getMemoryGiB()));
            clientManager.applyClusterScopedYaml(primaryClusterId, queueYaml);
        }

        log.info("✓ 资源池 {} 已在线扩缩容", id);
        return toResponse(resourcePoolMapper.findById(id).orElseThrow());
    }

    private ResourcePoolResponse toResponse(ResourcePool p) {
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(p.getId());
        int availableGpu = p.getGpuSlots() - (p.getAllocatedGpuSlots() != null ? p.getAllocatedGpuSlots() : 0);
        return ResourcePoolResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .departmentCode(p.getDepartmentCode())
                .departmentName(p.getDepartmentName())
                .namespace(p.getNamespace())
                .physicalClusterIds(clusterIds)
                .gpuSlots(p.getGpuSlots())
                .cpuCores(p.getCpuCores())
                .memoryGiB(p.getMemoryGiB())
                .maxPods(p.getMaxPods())
                .nodeCount(p.getNodeCount())
                .allocatedGpuSlots(p.getAllocatedGpuSlots())
                .allocatedCpuCores(p.getAllocatedCpuCores())
                .allocatedMemoryGib(p.getAllocatedMemoryGib())
                .availableGpuSlots(availableGpu)
                .hardwareType(p.getHardwareType())
                .securityLevel(p.getSecurityLevel())
                .gpuType(p.getGpuType())
                .jobTypes(p.getJobTypes())
                .volcanoQueueName(p.getVolcanoQueueName())
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
