package com.acmp.compute.service;

import com.acmp.compute.dto.TenantSpecQuotaRequest;
import com.acmp.compute.dto.TenantSpecQuotaResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.TenantSpecQuota;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.TenantMapper;
import com.acmp.compute.mapper.TenantSpecQuotaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantSpecQuotaService {
    private final TenantSpecQuotaMapper mapper;
    private final TenantMapper tenantMapper;
    private final ComputeSpecMapper specMapper;
    private final ResourcePoolMapper poolMapper;
    private final ComputeSpecService specService;

    @Transactional
    public TenantSpecQuotaResponse create(String tenantId, TenantSpecQuotaRequest request) {
        requireTenant(tenantId);
        ComputeSpec spec = requireSpec(request.getSpecId());
        if (mapper.findByTenantAndSpec(tenantId, spec.getId()).isPresent()) {
            throw new BadRequestException("租户已拥有该规格配额");
        }
        validateCapacity(spec, 0, request.getTotal());

        TenantSpecQuota quota = TenantSpecQuota.builder().id(UUID.randomUUID().toString())
                .tenantId(tenantId).specId(spec.getId()).total(request.getTotal()).used(0).build();
        mapper.insert(quota);
        return response(quota, spec);
    }

    @Transactional
    public TenantSpecQuotaResponse update(String tenantId, String quotaId, int total) {
        TenantSpecQuota quota = requireQuota(tenantId, quotaId);
        if (total < quota.getUsed()) {
            throw new BadRequestException("总配额不能小于已使用量");
        }
        ComputeSpec spec = requireSpec(quota.getSpecId());
        validateCapacity(spec, quota.getTotal(), total);

        mapper.updateTotal(quotaId, total);
        quota.setTotal(total);
        return response(quota, spec);
    }

    public List<TenantSpecQuotaResponse> list(String tenantId) {
        requireTenant(tenantId);
        List<TenantSpecQuotaResponse> result = new ArrayList<>();
        for (TenantSpecQuota quota : mapper.findByTenantId(tenantId)) {
            result.add(response(quota, requireSpec(quota.getSpecId())));
        }
        return result;
    }

    @Transactional
    public void delete(String tenantId, String quotaId) {
        TenantSpecQuota quota = requireQuota(tenantId, quotaId);
        if (quota.getUsed() > 0) {
            throw new BadRequestException("配额已使用，不能删除");
        }
        mapper.deleteById(quotaId);
    }

    public TenantSpecQuota requireAvailable(String tenantId, String specId, int amount) {
        TenantSpecQuota quota = mapper.findByTenantAndSpec(tenantId, specId).orElse(null);
        if (quota == null) {
            throw new BadRequestException("租户未分配该规格");
        }
        if (quota.getTotal() - quota.getUsed() < amount) {
            throw new BadRequestException("租户规格配额不足");
        }
        return quota;
    }

    @Transactional
    public void changeUsed(String quotaId, int delta) {
        TenantSpecQuota quota = mapper.findById(quotaId).orElse(null);
        if (quota == null) {
            throw new ResourceNotFoundException("租户规格配额不存在");
        }
        int used = quota.getUsed() + delta;
        if (used < 0) {
            used = 0;
        }
        if (used > quota.getTotal()) {
            throw new BadRequestException("租户规格配额不足");
        }
        mapper.updateUsed(quotaId, used);
    }

    private void requireTenant(String id) {
        if (tenantMapper.findById(id).isEmpty()) {
            throw new ResourceNotFoundException("租户不存在: " + id);
        }
    }
    private ComputeSpec requireSpec(String id) {
        ComputeSpec spec = specMapper.findById(id).orElse(null);
        if (spec == null) {
            throw new ResourceNotFoundException("规格不存在: " + id);
        }
        return spec;
    }
    private TenantSpecQuota requireQuota(String tenantId, String id) {
        TenantSpecQuota q = mapper.findById(id).orElse(null);
        if (q == null) {
            throw new ResourceNotFoundException("配额不存在");
        }
        if (!tenantId.equals(q.getTenantId())) {
            throw new BadRequestException("配额不属于该租户");
        }
        return q;
    }
    private TenantSpecQuotaResponse response(TenantSpecQuota q, ComputeSpec s) {
        ResourcePool pool = poolMapper.findById(s.getResourcePoolId()).orElse(null);

        return TenantSpecQuotaResponse.builder().id(q.getId()).tenantId(q.getTenantId()).specId(q.getSpecId())
                .specName(s.getName()).specDisplayName(s.getDisplayName())
                .resourcePoolId(s.getResourcePoolId())
                .resourcePoolName(pool == null ? null : pool.getName())
                .poolType(s.getSpecType())
                .gpuModel(s.getGpuModel()).gpuShare(s.getGpuShare())
                .cpuCores(s.getCpuCores()).memoryGib(s.getMemoryGib())
                .capacityNodes(specService.capacityNodes(s))
                .total(q.getTotal()).used(q.getUsed())
                .remaining(Math.max(0, q.getTotal() - q.getUsed())).build();
    }

    private void validateCapacity(ComputeSpec spec, int oldTotal, int newTotal) {
        int capacity = specService.capacityNodes(spec);
        int allocatedWithoutCurrent = mapper.sumTotalBySpecId(spec.getId()) - oldTotal;

        if (newTotal < 0) {
            throw new BadRequestException("规格节点配额不能小于 0");
        }
        if (allocatedWithoutCurrent + newTotal > capacity) {
            int available = Math.max(0, capacity - allocatedWithoutCurrent);
            throw new BadRequestException("规格最多还能分配 " + available + " 个算力节点");
        }
    }
}
