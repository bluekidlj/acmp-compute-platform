package com.acmp.compute.service;

import com.acmp.compute.dto.ResourcePoolCreateRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuSplitSpec;
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

        // 2. 【HAMi vGPU 切分模式】自动生成 ComputeSpec
        if (request.getPoolLabel() != null && !request.getPoolLabel().isEmpty()) {
            return createWithGpuSplit(request, clusterIds);
        }

        // 3. 规格存在性校验 + 名称→ID 映射
        Map<String, ComputeSpec> specByName = request.getSpecQuotas().stream()
                .map(item -> computeSpecMapper.findByName(item.getSpecName())
                        .orElseThrow(() -> new BadRequestException("规格不存在: " + item.getSpecName())))
                .collect(Collectors.toMap(ComputeSpec::getName, s -> s, (a, b) -> a));

        // 4. resource_pool 行
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

        // 5. 物理集群关联
        for (String cid : clusterIds) {
            resourcePoolMapper.insertPhysicalCluster(id, cid);
        }

        // 6. 按规格配额（allocated 起始 0）
        for (ResourcePoolCreateRequest.SpecQuotaItem item : request.getSpecQuotas()) {
            ComputeSpec spec = specByName.get(item.getSpecName());
            computeSpecMapper.insertResourcePoolSpecQuota(id, spec.getId(), item.getTotalQuota(), 0);
        }

        log.info("✓ 逻辑资源池 {} 已创建 (clusters={}, specs={})",
                id, clusterIds.size(), request.getSpecQuotas().size());

        return toResponse(resourcePoolMapper.findById(id).orElseThrow());
    }

    /**
     * 【HAMi vGPU 切分模式】根据 poolLabel 自动生成 ComputeSpec 并创建资源池。
     * poolLabel 直接作为 ComputeSpec.name（与节点 pool 标签一致）。
     * 尝试匹配 GpuSplitSpec 枚举获取 gpumem/gpucores；若不匹配则用默认值。
     */
    private ResourcePoolResponse createWithGpuSplit(ResourcePoolCreateRequest request, List<String> clusterIds) {
        String poolLabel = request.getPoolLabel();

        // 尝试从 GpuSplitSpec 枚举获取详细参数
        GpuSplitSpec splitSpec = GpuSplitSpec.fromSpecName(poolLabel);

        int gpumemMb = 16384;  // 默认 16GB
        int gpucores = 50;     // 默认 50%
        String gpuBrand = "NVIDIA";
        String displaySuffix = poolLabel;

        if (splitSpec != null) {
            gpumemMb = splitSpec.getGpumemMb();
            gpucores = splitSpec.getGpucores();
            gpuBrand = splitSpec.getGpuBrand();
            displaySuffix = GpuSplitSpec.parseSplitType(poolLabel);
        } else {
            log.warn("poolLabel {} 未匹配到预设 GpuSplitSpec，使用默认参数", poolLabel);
        }

        String specName = poolLabel;

        // 检查 ComputeSpec 是否已存在（已存在则复用）
        ComputeSpec existingSpec = computeSpecMapper.findByName(specName).orElse(null);
        String specId;
        if (existingSpec != null) {
            specId = existingSpec.getId();
            log.info("复用已有 ComputeSpec: {}", specName);
        } else {
            // 创建新的 ComputeSpec
            specId = UUID.randomUUID().toString();
            ComputeSpec spec = ComputeSpec.builder()
                    .id(specId)
                    .name(specName)
                    .displayName(displaySuffix)
                    .gpuBrand(com.acmp.compute.entity.GpuBrand.valueOf(gpuBrand))
                    .defaultGpuCount(1)
                    .defaultGpumemMb(gpumemMb)
                    .defaultGpucores(gpucores)
                    .defaultCpuCores(4)
                    .defaultMemoryGib(16)
                    .nodeSelector("{\"pool\":\"" + specName + "\"}")
                    .tolerations("[{\"key\":\"nvidia.com/gpu\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]")
                    .resourceQuotaKey("platform.io/" + specName)
                    .specType(ComputeSpec.SpecType.VIRTUAL)
                    .memoryGb(gpumemMb / 1024)
                    .build();
            computeSpecMapper.insert(spec);

            // 关联到所有物理集群
            for (String cid : clusterIds) {
                computeSpecMapper.insertPhysicalClusterSpec(cid, specId, 0);
            }
            log.info("✓ 自动创建 HAMi vGPU 切分规格: {}", specName);
        }

        // 创建资源池
        String poolId = UUID.randomUUID().toString();
        ResourcePool pool = ResourcePool.builder()
                .id(poolId)
                .name(request.getName())
                .description(request.getDescription())
                .departmentCode(request.getDepartmentCode())
                .departmentName(request.getDepartmentName())
                .status("active")
                .build();
        resourcePoolMapper.insert(pool);

        // 物理集群关联
        for (String cid : clusterIds) {
            resourcePoolMapper.insertPhysicalCluster(poolId, cid);
        }

        // 配额（切分模式下只有一种规格，配额来自请求的 specQuotas）
        int totalQuota = request.getSpecQuotas() != null && !request.getSpecQuotas().isEmpty()
                ? request.getSpecQuotas().get(0).getTotalQuota() : 0;
        computeSpecMapper.insertResourcePoolSpecQuota(poolId, specId, totalQuota, 0);

        log.info("✓ HAMi vGPU 切分资源池 {} 已创建 (spec={}, totalQuota={})", poolId, specName, totalQuota);
        return toResponse(resourcePoolMapper.findById(poolId).orElseThrow());
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
