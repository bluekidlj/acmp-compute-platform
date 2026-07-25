package com.acmp.compute.service;

import com.acmp.compute.dto.SpecRequest;
import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuDevice;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.GpuDeviceMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.TenantSpecQuotaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ComputeSpecService {
    private final ComputeSpecMapper specMapper;
    private final ResourcePoolMapper poolMapper;
    private final TenantSpecQuotaMapper quotaMapper;
    private final GpuDeviceMapper gpuMapper;

    @Transactional
    public SpecResponse create(SpecRequest request) {
        if (specMapper.findByName(request.getName()).isPresent()) {
            throw new BadRequestException("规格已存在: " + request.getName());
        }
        validate(request);
        ComputeSpec spec = build(null, request);
        specMapper.insert(spec);
        return response(specMapper.findById(spec.getId()).orElseThrow());
    }

    @Transactional
    public SpecResponse update(String id, SpecRequest request) {
        ComputeSpec old = specMapper.findById(id).orElse(null);
        if (old == null) {
            throw new ResourceNotFoundException("规格不存在: " + id);
        }
        validate(request);
        ComputeSpec spec = build(id, request);
        spec.setCreatedAt(old.getCreatedAt());
        specMapper.update(spec);
        return response(specMapper.findById(id).orElseThrow());
    }

    public List<SpecResponse> list(String specType) {
        List<ComputeSpec> source;
        if (specType == null || specType.isBlank()) {
            source = specMapper.findAll();
        } else {
            source = specMapper.findBySpecType(specType);
        }

        List<SpecResponse> result = new ArrayList<>();
        for (ComputeSpec spec : source) {
            result.add(response(spec));
        }
        return result;
    }

    public SpecResponse getById(String id) {
        ComputeSpec spec = specMapper.findById(id).orElse(null);
        if (spec == null) {
            throw new ResourceNotFoundException("规格不存在: " + id);
        }
        return response(spec);
    }

    public SpecResponse getByName(String name) {
        ComputeSpec spec = specMapper.findByName(name).orElse(null);
        if (spec == null) {
            throw new ResourceNotFoundException("规格不存在: " + name);
        }
        return response(spec);
    }

    @Transactional
    public void delete(String id) {
        if (quotaMapper.countBySpecId(id) > 0) {
            throw new BadRequestException("规格已分配给租户，不能删除");
        }
        ComputeSpec spec = specMapper.findById(id).orElse(null);
        if (spec == null) {
            throw new ResourceNotFoundException("规格不存在: " + id);
        }
        specMapper.deleteById(id);
    }

    private void validate(SpecRequest request) {
        if (request.getCpuCores() == null || request.getCpuCores() <= 0
                || request.getMemoryGib() == null || request.getMemoryGib() <= 0
                || request.getGpuCount() == null || request.getGpuCount() <= 0) {
            throw new BadRequestException("CPU、内存和 GPU 数量必须大于 0");
        }
        if (request.getGpuCount() != 1) {
            throw new BadRequestException("0.1 版本算力规格只支持单 Gpu");
        }
        ResourcePool pool = poolMapper.findById(request.getResourcePoolId()).orElse(null);
        if (pool == null) {
            throw new ResourceNotFoundException("资源池不存在");
        }
        if (!request.getSpecType().equals(pool.getPoolType())) {
            throw new BadRequestException("规格类型与资源池类型不匹配");
        }
        if ("SHARED".equals(request.getSpecType())) {
            validateGpuShare(request.getGpuShare());
        } else if (request.getGpuShare() != null && !request.getGpuShare().isBlank()) {
            throw new BadRequestException("独占规格不能设置 gpuShare");
        }
    }

    private void validateGpuShare(String gpuShare) {
        if (!"1/8".equals(gpuShare)
                && !"1/4".equals(gpuShare)
                && !"1/2".equals(gpuShare)) {
            throw new BadRequestException("共享规格 gpuShare 只允许 1/8、1/4、1/2");
        }
    }

    private ComputeSpec build(String id, SpecRequest request) {
        return ComputeSpec.builder().id(id == null ? UUID.randomUUID().toString() : id)
                .name(request.getName()).displayName(request.getDisplayName())
                .gpuBrand(request.getGpuBrand()).specType(request.getSpecType())
                .resourcePoolId(request.getResourcePoolId())
                .gpuModel(request.getGpuModel()).gpuMemoryMb(null).gpuShare(request.getGpuShare())
                .gpuCount(1)
                .cpuCores(request.getCpuCores()).memoryGib(request.getMemoryGib())
                .description(request.getDescription()).status("active").build();
    }

    private SpecResponse response(ComputeSpec spec) {
        ResourcePool pool = poolMapper.findById(spec.getResourcePoolId()).orElse(null);
        GpuDevice sourceGpu = gpuMapper.findByComputeSpecId(spec.getId()).orElse(null);

        return SpecResponse.builder().id(spec.getId()).name(spec.getName()).displayName(spec.getDisplayName())
                .gpuBrand(spec.getGpuBrand()).specType(spec.getSpecType())
                .resourcePoolId(spec.getResourcePoolId()).gpuModel(spec.getGpuModel())
                .gpuMemoryMb(spec.getGpuMemoryMb())
                .gpuShare(spec.getGpuShare())
                .gpuCount(spec.getGpuCount()).cpuCores(spec.getCpuCores())
                .memoryGib(spec.getMemoryGib()).description(spec.getDescription())
                .status(spec.getStatus())
                .capacityNodes(capacityNodes(spec))
                .allocatedNodes(quotaMapper.sumTotalBySpecId(spec.getId()))
                .usedNodes(quotaMapper.sumUsedBySpecId(spec.getId()))
                .sourceGpuId(sourceGpu == null ? null : sourceGpu.getId())
                .sourceGpuUuid(sourceGpu == null ? null : sourceGpu.getUuid())
                .sourceGpuIndex(sourceGpu == null ? null : sourceGpu.getGpuIndex())
                .sourceNodeName(sourceGpu == null ? null : sourceGpu.getNodeName())
                .resourcePoolName(pool == null ? null : pool.getName())
                .createdAt(spec.getCreatedAt()).updatedAt(spec.getUpdatedAt()).build();
    }

    /**
     * 一张入池 Gpu 对应一个规格；独享提供 1 个节点，共享按比例提供 2、4 或 8 个节点。
     */
    public int capacityNodes(ComputeSpec spec) {
        if ("EXCLUSIVE".equals(spec.getSpecType())) {
            return 1;
        }
        if ("1/8".equals(spec.getGpuShare())) {
            return 8;
        }
        if ("1/4".equals(spec.getGpuShare())) {
            return 4;
        }
        if ("1/2".equals(spec.getGpuShare())) {
            return 2;
        }
        return 0;
    }
}
