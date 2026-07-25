package com.acmp.compute.service;

import com.acmp.compute.dto.GpuJoinSpecRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuDevice;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.GpuDeviceMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResourcePoolService {
    public static final String EXCLUSIVE_POOL_ID = "pool-exclusive";
    public static final String SHARED_POOL_ID = "pool-shared";

    private final ResourcePoolMapper poolMapper;
    private final GpuDeviceMapper gpuMapper;
    private final ComputeSpecMapper specMapper;
    private final ComputeSpecService specService;

    public List<ResourcePoolResponse> list() {
        List<ResourcePoolResponse> result = new ArrayList<>();
        ResourcePool exclusive = poolMapper.findById(EXCLUSIVE_POOL_ID).orElse(null);
        ResourcePool shared = poolMapper.findById(SHARED_POOL_ID).orElse(null);
        if (exclusive != null) {
            result.add(toResponse(exclusive));
        }
        if (shared != null) {
            result.add(toResponse(shared));
        }
        return result;
    }

    public ResourcePoolResponse getById(String id) {
        return toResponse(corePool(id));
    }

    public List<GpuDevice> listGpus(String id) {
        corePool(id);
        return gpuMapper.findByPoolId(id);
    }

    /**
     * Gpu 入池和规格创建是同一个数据库事务。
     *
     * <p>Gpu 型号、规格类型、资源池和固定单 Gpu 约束由后端确定，避免前端提交冲突字段。
     */
    @Transactional
    public SpecResponse joinGpu(
            String poolId,
            String gpuId,
            GpuJoinSpecRequest request) {
        ResourcePool pool = corePool(poolId);
        GpuDevice gpu = gpuMapper.findById(gpuId).orElse(null);

        if (gpu == null) {
            throw new ResourceNotFoundException("Gpu 不存在: " + gpuId);
        }
        if (gpu.getResourcePoolId() != null || gpu.getComputeSpecId() != null) {
            throw new BadRequestException("Gpu 已经加入资源池");
        }
        if (specMapper.findByName(request.getName()).isPresent()) {
            throw new BadRequestException("算力规格名称已存在: " + request.getName());
        }
        if (request.getCpuCores() == null || request.getCpuCores() <= 0
                || request.getMemoryGib() == null || request.getMemoryGib() <= 0) {
            throw new BadRequestException("CPU 和内存必须大于 0");
        }
        if (gpu.getGpuBrand() == null) {
            throw new BadRequestException("Gpu 品牌未识别，请先重新同步集群库存");
        }
        if ("SHARED".equals(pool.getPoolType())
                && gpu.getGpuBrand() != com.acmp.compute.entity.GpuBrand.NVIDIA
                && (gpu.getMemoryMb() == null || gpu.getMemoryMb() <= 0)) {
            throw new BadRequestException("海光或华为共享 Gpu 必须先识别显存容量");
        }

        String gpuShare = validateShare(pool, request.getGpuShare());
        String specId = UUID.randomUUID().toString();
        ComputeSpec spec = ComputeSpec.builder()
                .id(specId)
                .name(request.getName())
                .displayName(request.getDisplayName())
                .gpuBrand(gpu.getGpuBrand())
                .specType(pool.getPoolType())
                .resourcePoolId(pool.getId())
                .gpuModel(gpu.getGpuModel())
                .gpuMemoryMb(gpu.getMemoryMb())
                .gpuCount(1)
                .cpuCores(request.getCpuCores())
                .memoryGib(request.getMemoryGib())
                .gpuShare(gpuShare)
                .description(request.getDescription())
                .status("active")
                .build();

        specMapper.insert(spec);

        if (gpuMapper.assignPoolAndSpec(gpuId, poolId, specId) != 1) {
            throw new BadRequestException("Gpu 入池失败或已经被加入其他资源池");
        }

        return specService.getById(specId);
    }

    private ResourcePool corePool(String id) {
        if (!EXCLUSIVE_POOL_ID.equals(id) && !SHARED_POOL_ID.equals(id)) {
            throw new ResourceNotFoundException("固定资源池不存在: " + id);
        }
        ResourcePool pool = poolMapper.findById(id).orElse(null);
        if (pool == null) {
            throw new ResourceNotFoundException("资源池未初始化: " + id);
        }
        return pool;
    }

    private String validateShare(ResourcePool pool, String gpuShare) {
        if ("EXCLUSIVE".equals(pool.getPoolType())) {
            if (gpuShare != null && !gpuShare.isBlank()) {
                throw new BadRequestException("独享池规格不能设置共享比例");
            }
            return null;
        }

        if (!"1/8".equals(gpuShare)
                && !"1/4".equals(gpuShare)
                && !"1/2".equals(gpuShare)) {
            throw new BadRequestException("共享比例只允许 1/8、1/4、1/2");
        }

        return gpuShare;
    }

    private ResourcePoolResponse toResponse(ResourcePool pool) {
        List<ResourcePoolResponse.SpecBrief> briefs = new ArrayList<>();
        List<ComputeSpec> specs = specMapper.findByResourcePoolId(pool.getId());
        for (ComputeSpec spec : specs) {
            briefs.add(ResourcePoolResponse.SpecBrief.builder().id(spec.getId()).name(spec.getName())
                    .displayName(spec.getDisplayName()).specType(spec.getSpecType())
                    .gpuBrand(spec.getGpuBrand())
                    .build());
        }
        int total = gpuMapper.countByPool(pool.getId());
        return ResourcePoolResponse.builder().id(pool.getId()).poolType(pool.getPoolType())
                .name(pool.getName()).description(pool.getDescription()).gpuCount(total)
                .status(pool.getStatus()).specs(briefs)
                .createdAt(pool.getCreatedAt()).updatedAt(pool.getUpdatedAt()).build();
    }
}
