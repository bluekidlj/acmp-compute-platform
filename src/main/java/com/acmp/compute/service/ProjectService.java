package com.acmp.compute.service;

import com.acmp.compute.dto.ProjectRequest;
import com.acmp.compute.dto.ProjectResponse;
import com.acmp.compute.dto.TenantSpecQuotaResponse;
import com.acmp.compute.entity.Project;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ProjectMapper;
import com.acmp.compute.mapper.TenantMapper;
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
public class ProjectService {
    private final ProjectMapper mapper;
    private final TenantMapper tenantMapper;
    private final TenantSpecQuotaService tenantQuotaService;

    @Transactional
    public ProjectResponse create(String tenantId, ProjectRequest request) {
        if (tenantMapper.findById(tenantId).isEmpty()) {
            throw new ResourceNotFoundException("租户不存在: " + tenantId);
        }
        Project project = Project.builder().id(UUID.randomUUID().toString()).tenantId(tenantId)
                .name(request.getName()).description(request.getDescription())
                .createdBy(currentUser()).status("active").build();
        mapper.insert(project);
        if (request.getMemberIds() != null) {
            for (String userId : request.getMemberIds()) {
                mapper.insertMember(project.getId(), userId);
            }
        }
        return response(project);
    }

    @Transactional
    public ProjectResponse update(String id, ProjectRequest request) {
        Project project = entity(id);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        mapper.update(project);
        return response(project);
    }

    @Transactional
    public void delete(String id) {
        entity(id);
        mapper.deleteAllMembers(id);
        mapper.deleteById(id);
    }

    public ProjectResponse getById(String id) {
        return response(entity(id));
    }

    public List<ProjectResponse> listByTenant(String tenantId) {
        if (tenantMapper.findById(tenantId).isEmpty()) {
            throw new ResourceNotFoundException("租户不存在: " + tenantId);
        }
        List<ProjectResponse> result = new ArrayList<>();
        for (Project project : mapper.findByTenantId(tenantId)) {
            result.add(response(project));
        }
        return result;
    }

    public List<TenantSpecQuotaResponse> availableSpecs(String projectId) {
        Project project = entity(projectId);
        return tenantQuotaService.list(project.getTenantId());
    }

    @Transactional
    public void addMember(String id, String userId) {
        entity(id);
        mapper.insertMember(id, userId);
    }

    @Transactional
    public void removeMember(String id, String userId) {
        mapper.deleteMember(id, userId);
    }

    public List<String> listMembers(String id) {
        entity(id);
        return mapper.findMemberIds(id);
    }

    private Project entity(String id) {
        Project project = mapper.findById(id).orElse(null);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        return project;
    }
    private String currentUser() {
        Object value=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return value instanceof UserPrincipal ? ((UserPrincipal)value).getId() : "system";
    }
    private ProjectResponse response(Project p) {
        return ProjectResponse.builder().id(p.getId()).tenantId(p.getTenantId())
                .name(p.getName()).description(p.getDescription()).createdBy(p.getCreatedBy())
                .status(p.getStatus()).memberIds(mapper.findMemberIds(p.getId()))
                .createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt()).build();
    }
}
