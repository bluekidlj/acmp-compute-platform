package com.acmp.compute.service;

import com.acmp.compute.dto.SpecQuotaEntry;
import com.acmp.compute.dto.SpecRequest;
import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 算力规格服务：规格目录 CRUD + 跨层配额查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComputeSpecService {

    private final ComputeSpecMapper specMapper;

    // ── 规格目录 CRUD ──

    @Transactional
    public SpecResponse create(SpecRequest request) {
        if (specMapper.findByName(request.getName()).isPresent()) {
            throw new IllegalArgumentException("规格已存在: " + request.getName());
        }
        ComputeSpec spec = ComputeSpec.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .displayName(request.getDisplayName())
                .gpuBrand(request.getGpuBrand())
                .memoryGb(request.getMemoryGb())
                .architecture(request.getArchitecture())
                .description(request.getDescription())
                .build();
        specMapper.insert(spec);
        log.info("✓ 新规格注册: {}", spec.getName());
        return toResponse(spec);
    }

    public List<SpecResponse> list() {
        return specMapper.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public SpecResponse getById(String id) {
        ComputeSpec spec = specMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + id));
        return toResponse(spec);
    }

    public SpecResponse getByName(String name) {
        ComputeSpec spec = specMapper.findByName(name)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + name));
        return toResponse(spec);
    }

    @Transactional
    public void delete(String id) {
        specMapper.deleteById(id);
        log.info("✓ 规格已删除: {}", id);
    }

    // ── 物理集群 ↔ 规格 ──

    @Transactional
    public void bindPhysicalCluster(String clusterId, String specName, int totalCount) {
        ComputeSpec spec = specMapper.findByName(specName)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + specName));
        specMapper.insertPhysicalClusterSpec(clusterId, spec.getId(), totalCount);
    }

    public List<SpecResponse> listByPhysicalCluster(String clusterId) {
        return specMapper.findByPhysicalClusterId(clusterId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    // ── 逻辑池 ↔ 规格配额 ──

    @Transactional
    public void setResourcePoolSpecQuota(String poolId, String specName, int totalQuota) {
        ComputeSpec spec = specMapper.findByName(specName)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + specName));
        specMapper.insertResourcePoolSpecQuota(poolId, spec.getId(), totalQuota, 0);
    }

    public List<SpecQuotaEntry> getResourcePoolSpecQuotas(String poolId) {
        return toEntries(specMapper.findSpecQuotasByResourcePoolId(poolId));
    }

    // ── helpers ──

    private int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private List<SpecQuotaEntry> toEntries(List<Map<String, Object>> rows) {
        List<SpecQuotaEntry> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            entries.add(SpecQuotaEntry.builder()
                    .specName((String) row.get("spec_name"))
                    .specId((String) row.get("spec_id"))
                    .totalQuota(row.containsKey("total_quota") ? toInt(row.get("total_quota")) : null)
                    .maxQuota(row.containsKey("max_quota") ? toInt(row.get("max_quota")) : null)
                    .allocatedQuota(row.containsKey("allocated_quota") ? toInt(row.get("allocated_quota")) : null)
                    .usedQuota(row.containsKey("used_quota") ? toInt(row.get("used_quota")) : null)
                    .available(toInt(row.get("available")))
                    .build());
        }
        return entries;
    }

    private SpecResponse toResponse(ComputeSpec s) {
        return SpecResponse.builder()
                .id(s.getId()).name(s.getName()).displayName(s.getDisplayName())
                .gpuBrand(s.getGpuBrand()).memoryGb(s.getMemoryGb())
                .architecture(s.getArchitecture()).description(s.getDescription())
                .createdAt(s.getCreatedAt()).build();
    }
}
