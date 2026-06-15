package com.acmp.compute.service;

import com.acmp.compute.dto.SpecRequest;
import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 1.0 算力规格服务。
 * 规格类型(specType)与池类型(poolType)一一对应：PHYSICAL→EXCLUSIVE, VIRTUAL→SHARED, OVERSELL→OVERSELL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComputeSpecService {

    private final ComputeSpecMapper specMapper;

    @Transactional
    public SpecResponse create(SpecRequest req) {
        if (specMapper.findByName(req.getName()).isPresent()) {
            throw new BadRequestException("规格已存在: " + req.getName());
        }
        String poolType = derivePoolType(req.getSpecType());
        ComputeSpec spec = ComputeSpec.builder()
                .id(UUID.randomUUID().toString())
                .name(req.getName())
                .displayName(req.getDisplayName())
                .gpuBrand(req.getGpuBrand())
                .specType(req.getSpecType())
                .poolType(poolType)
                .defaultGpuCount(req.getDefaultGpuCount())
                .defaultGpumemMb(req.getDefaultGpumemMb())
                .defaultGpucores(req.getDefaultGpucores())
                .defaultCpuCores(req.getDefaultCpuCores())
                .defaultMemoryGib(req.getDefaultMemoryGib())
                .nodeSelector(req.getNodeSelector())
                .tolerations(req.getTolerations())
                .resourceQuotaKey(req.getResourceQuotaKey() != null && !req.getResourceQuotaKey().isEmpty()
                        ? req.getResourceQuotaKey()
                        : "platform.io/" + req.getName())
                .memoryGb(req.getMemoryGb())
                .description(req.getDescription())
                .build();
        specMapper.insert(spec);
        log.info("✓ 规格创建: {} ({}→{})", spec.getName(), spec.getSpecType(), spec.getPoolType());
        return toResponse(spec);
    }

    public List<SpecResponse> list(String poolType) {
        List<ComputeSpec> specs = (poolType == null || poolType.isEmpty())
                ? specMapper.findAll()
                : specMapper.findByPoolType(poolType);
        return specs.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public SpecResponse getById(String id) {
        return toResponse(specMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + id)));
    }

    public SpecResponse getByName(String name) {
        return toResponse(specMapper.findByName(name)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + name)));
    }

    @Transactional
    public void delete(String id) {
        if (specMapper.findById(id).isEmpty()) {
            throw new ResourceNotFoundException("规格不存在: " + id);
        }
        specMapper.deleteById(id);
        log.info("✓ 规格已删除: {}", id);
    }

    private String derivePoolType(String specType) {
        switch (specType) {
            case "PHYSICAL": return "EXCLUSIVE";
            case "VIRTUAL":  return "SHARED";
            case "OVERSELL": return "OVERSELL";
            default: throw new BadRequestException("未知 specType: " + specType);
        }
    }

    private SpecResponse toResponse(ComputeSpec s) {
        return SpecResponse.builder()
                .id(s.getId())
                .name(s.getName())
                .displayName(s.getDisplayName())
                .gpuBrand(s.getGpuBrand())
                .specType(s.getSpecType())
                .poolType(s.getPoolType())
                .defaultGpuCount(s.getDefaultGpuCount())
                .defaultGpumemMb(s.getDefaultGpumemMb())
                .defaultGpucores(s.getDefaultGpucores())
                .defaultCpuCores(s.getDefaultCpuCores())
                .defaultMemoryGib(s.getDefaultMemoryGib())
                .nodeSelector(s.getNodeSelector())
                .tolerations(s.getTolerations())
                .resourceQuotaKey(s.getResourceQuotaKey())
                .memoryGb(s.getMemoryGb())
                .description(s.getDescription())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
