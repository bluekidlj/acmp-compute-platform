package com.acmp.compute.service;

import com.acmp.compute.dto.TenantRequest;
import com.acmp.compute.dto.TenantResponse;
import com.acmp.compute.entity.Tenant;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ProjectMapper;
import com.acmp.compute.mapper.TenantMapper;
import com.acmp.compute.mapper.TenantSpecQuotaMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantMapper tenantMapper;
    private final TenantSpecQuotaMapper quotaMapper;
    private final ProjectMapper projectMapper;

    @Transactional
    public TenantResponse create(TenantRequest request) {
        if (tenantMapper.findByName(request.getName()).isPresent()) {
            throw new BadRequestException("租户名称已存在");
        }
        Tenant tenant = Tenant.builder().id(UUID.randomUUID().toString()).name(request.getName())
                .description(request.getDescription()).createdBy(currentUserId()).status("active").build();
        tenantMapper.insert(tenant);
        return response(tenantMapper.findById(tenant.getId()).orElseThrow());
    }

    @Transactional
    public TenantResponse update(String id, TenantRequest request) {
        Tenant tenant = entity(id);
        tenant.setName(request.getName());
        tenant.setDescription(request.getDescription());
        tenantMapper.update(tenant);
        return response(tenantMapper.findById(id).orElseThrow());
    }

    public TenantResponse get(String id) {
        return response(entity(id));
    }

    public List<TenantResponse> list() {
        List<TenantResponse> result = new ArrayList<>();
        for (Tenant tenant : tenantMapper.findAll()) {
            result.add(response(tenant));
        }
        return result;
    }

    @Transactional
    public void delete(String id) {
        entity(id);
        if (!projectMapper.findByTenantId(id).isEmpty()) {
            throw new BadRequestException("租户下存在项目，不能删除");
        }
        if (quotaMapper.countUsedByTenantId(id) > 0) {
            throw new BadRequestException("租户存在已使用配额，不能删除");
        }
        quotaMapper.deleteByTenantId(id);
        tenantMapper.deleteById(id);
    }

    private Tenant entity(String id) {
        Tenant tenant = tenantMapper.findById(id).orElse(null);
        if (tenant == null) {
            throw new ResourceNotFoundException("租户不存在: " + id);
        }
        return tenant;
    }

    private String currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserPrincipal) {
            return ((UserPrincipal) principal).getId();
        }
        return "system";
    }

    private TenantResponse response(Tenant t) {
        return TenantResponse.builder().id(t.getId()).name(t.getName()).description(t.getDescription())
                .createdBy(t.getCreatedBy()).status(t.getStatus()).createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt()).build();
    }
}
